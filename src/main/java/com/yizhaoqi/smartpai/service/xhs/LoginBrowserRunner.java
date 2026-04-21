package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.XhsLoginProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 扫码登录流式 runner。
 *
 * <p>作用：拉起 {@code skills-bundled/xhs-qr-login/run.mjs} 子进程，
 * 把每一行 stdout（NDJSON）解析成 {@link LoginEvent}，透传给业务层。
 *
 * <p>和 {@link BrowserSkillRunner} 的差异：
 * <ul>
 *   <li>BrowserSkillRunner 是"一把梭"：等进程结束、读 out.json</li>
 *   <li>LoginBrowserRunner 是"流式"：一边跑一边回调，便于 WS 推送二维码、扫码状态</li>
 * </ul>
 *
 * <p>Runner 自带进程注册表（{@code running}），
 * 调用方可通过 {@link #cancel(String)} 主动杀掉某个会话（用户点取消、定时器超时等）。
 */
@Service
public class LoginBrowserRunner {

    private static final Logger log = LoggerFactory.getLogger(LoginBrowserRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final XhsLoginProperties props;
    private final ConcurrentMap<String, Handle> running = new ConcurrentHashMap<>();

    public LoginBrowserRunner(XhsLoginProperties props) {
        this.props = props;
    }

    /**
     * 启动子进程。非阻塞：方法立即返回，stdout 解析在 daemon 线程里跑。
     * 成功启动返回 true；脚本缺失 / 已有同 session 运行 → 返回 false，onEvent 会收到 error。
     */
    public boolean start(StartRequest req, Consumer<LoginEvent> onEvent) {
        if (!props.isEnabled()) {
            onEvent.accept(LoginEvent.error("disabled", "xhs-login 已在配置中被禁用"));
            return false;
        }
        if (running.containsKey(req.sessionId)) {
            onEvent.accept(LoginEvent.error("duplicate_session", "同一会话已经在跑了"));
            return false;
        }
        Path script = Paths.get(props.getSkillPath()).toAbsolutePath();
        if (!Files.isRegularFile(script)) {
            onEvent.accept(LoginEvent.error("script_not_found",
                    "登录脚本不存在：" + script + "，请确认镜像构建时装好了 skills-bundled/xhs-qr-login"));
            return false;
        }

        List<String> argv = new ArrayList<>();
        argv.add("node");
        argv.add(script.toString());
        argv.add("--session");
        argv.add(req.sessionId);
        argv.add("--platforms");
        argv.add(req.platforms);
        argv.add("--timeout");
        argv.add(String.valueOf(req.timeoutSeconds > 0 ? req.timeoutSeconds : props.getExpiresSeconds()));

        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.put("NODE_PATH", props.getNodeModulesPath());
        env.put("PLAYWRIGHT_BROWSERS_PATH", props.getBrowsersPath());
        // 不走下载；镜像里已经 install chromium
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        if (req.extraEnv != null) env.putAll(req.extraEnv);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            onEvent.accept(LoginEvent.error("start_failed", "无法启动 node 子进程：" + e.getMessage()));
            return false;
        }

        Handle h = new Handle(proc, new AtomicBoolean(false));
        running.put(req.sessionId, h);

        Thread reader = new Thread(() -> pumpStdout(req.sessionId, proc, onEvent), "xhs-login-" + req.sessionId);
        reader.setDaemon(true);
        reader.start();

        // stderr 只打 log，不进入事件流
        Thread errReader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.warn("[xhs-login {}] stderr: {}", req.sessionId, line);
                }
            } catch (IOException ignored) {
            }
        }, "xhs-login-err-" + req.sessionId);
        errReader.setDaemon(true);
        errReader.start();

        return true;
    }

    /**
     * 通过 stdin 发送一行 JSON 指令（比如 cancel）。
     * Node 脚本在主循环里 readline 消费。
     */
    public void sendCommand(String sessionId, Map<String, Object> cmd) {
        Handle h = running.get(sessionId);
        if (h == null || !h.proc.isAlive()) return;
        try {
            Writer w = new OutputStreamWriter(h.proc.getOutputStream(), StandardCharsets.UTF_8);
            w.write(MAPPER.writeValueAsString(cmd));
            w.write("\n");
            w.flush();
        } catch (IOException e) {
            log.warn("sendCommand 写入 stdin 失败 session={} err={}", sessionId, e.getMessage());
        }
    }

    /** 强制杀掉子进程；已经终止的话返回 false。 */
    public boolean cancel(String sessionId) {
        Handle h = running.remove(sessionId);
        if (h == null) return false;
        h.cancelled.set(true);
        if (h.proc.isAlive()) {
            // 先给 Node 发一条 cancel，让它有机会清 context；超时再硬杀
            try {
                Writer w = new OutputStreamWriter(h.proc.getOutputStream(), StandardCharsets.UTF_8);
                w.write("{\"type\":\"cancel\"}\n");
                w.flush();
            } catch (IOException ignored) {
            }
            h.proc.destroy();
            try {
                if (!h.proc.waitFor(3, TimeUnit.SECONDS)) {
                    h.proc.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                h.proc.destroyForcibly();
            }
        }
        return true;
    }

    /** 外部查询是否还在跑。 */
    public boolean isRunning(String sessionId) {
        Handle h = running.get(sessionId);
        return h != null && h.proc.isAlive();
    }

    // ---------- 内部实现 ----------

    private void pumpStdout(String sessionId, Process proc, Consumer<LoginEvent> onEvent) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String type = node.path("type").asText("");
                    onEvent.accept(new LoginEvent(type, node));
                } catch (Exception parseErr) {
                    log.debug("[xhs-login {}] 非 JSON 行丢弃: {}", sessionId, line);
                }
            }
        } catch (IOException ioe) {
            log.warn("[xhs-login {}] stdout 读取中断 err={}", sessionId, ioe.getMessage());
        } finally {
            try {
                proc.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            int exit = proc.isAlive() ? -1 : proc.exitValue();
            running.remove(sessionId);
            // 发送一个 "closed" 事件，让上层无论成功失败都能收尾
            Map<String, Object> m = new HashMap<>();
            m.put("exitCode", exit);
            onEvent.accept(new LoginEvent("closed", MAPPER.valueToTree(m)));
            log.info("[xhs-login {}] 子进程退出 exit={}", sessionId, exit);
        }
    }

    private record Handle(Process proc, AtomicBoolean cancelled) {}

    // ---------- 传输对象 ----------

    public static final class StartRequest {
        public String sessionId;
        public String platforms;
        public int timeoutSeconds;
        public Map<String, String> extraEnv;
    }

    /**
     * 单条 skill → Java 的事件。
     * <p>type 已知枚举：
     * <ul>
     *     <li>qr_ready：payload.dataUrl = "data:image/png;base64,..."</li>
     *     <li>status：payload.status = SCANNED/CONFIRMED/...</li>
     *     <li>success：payload.cookies = {xhs_pc: "...", xhs_creator: "...", ...}</li>
     *     <li>error：payload.errorType / payload.message</li>
     *     <li>closed：payload.exitCode（由 runner 合成，skill 自己不会发）</li>
     * </ul>
     */
    public record LoginEvent(String type, JsonNode payload) {
        public static LoginEvent error(String errorType, String message) {
            Map<String, Object> m = new HashMap<>();
            m.put("errorType", errorType);
            m.put("message", message);
            return new LoginEvent("error", MAPPER.valueToTree(m));
        }
    }
}
