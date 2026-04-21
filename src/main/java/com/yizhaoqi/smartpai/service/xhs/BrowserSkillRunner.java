package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.BrowserBridgeProperties;
import com.yizhaoqi.smartpai.config.SkillProperties;
import com.yizhaoqi.smartpai.service.skill.BashExecutor;
import com.yizhaoqi.smartpai.service.skill.LoadedSkill;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 浏览器自动化 skill 运行器。
 *
 * <p>和 {@link XhsSkillRunner} 的区别：
 * <ul>
 *   <li>XhsSkillRunner → python + Spider_XHS + 公司 XHS cookie 池</li>
 *   <li>BrowserSkillRunner → node + Playwright CDP + 业务员本机 Chrome</li>
 * </ul>
 *
 * <p>适用 skill：
 * <ul>
 *   <li>{@code xhs-outreach-comment} 批量评论外联</li>
 *   <li>{@code qiangua-brand-discover} 千瓜品牌达人发现</li>
 * </ul>
 *
 * <p>环境变量注入：
 * <ul>
 *   <li>{@code CDP_ENDPOINT}：Chrome 的 CDP WebSocket 地址（可被 RunRequest.extraEnv 覆盖）</li>
 *   <li>{@code NODE_PATH}：指向 {@code _shared/playwright-runtime/node_modules} 让 .mjs 能 import</li>
 * </ul>
 */
@Service
public class BrowserSkillRunner {

    private static final Logger log = LoggerFactory.getLogger(BrowserSkillRunner.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillRegistry skillRegistry;
    private final BashExecutor bashExecutor;
    private final SkillProperties skillProperties;
    private final BrowserBridgeProperties browserProps;

    public BrowserSkillRunner(SkillRegistry skillRegistry,
                              BashExecutor bashExecutor,
                              SkillProperties skillProperties,
                              BrowserBridgeProperties browserProps) {
        this.skillRegistry = skillRegistry;
        this.bashExecutor = bashExecutor;
        this.skillProperties = skillProperties;
        this.browserProps = browserProps;
    }

    public RunResult run(RunRequest req) {
        // 1. 解析 skill 路径
        Optional<LoadedSkill> skill = skillRegistry.find(req.skillName, req.orgTag);
        if (skill.isEmpty()) {
            return RunResult.error("skill_not_found",
                    "skill 未加载：" + req.skillName);
        }
        Path skillRoot = Paths.get(skill.get().rootPath()).toAbsolutePath();
        Path scriptPath = skillRoot.resolve(req.scriptRelative);
        if (!Files.isRegularFile(scriptPath)) {
            return RunResult.error("script_not_found",
                    "脚本不存在: " + scriptPath);
        }

        // 2. 沙箱子目录
        String subDir = "br-" + safeSession(req.sessionId) + "-" + LocalDateTime.now().format(TS);
        Path sandboxWork = Paths.get(skillProperties.getBash().getSandboxRoot())
                .toAbsolutePath().resolve(subDir);
        try {
            Files.createDirectories(sandboxWork);
        } catch (Exception e) {
            return RunResult.error("mkdir_failed", "创建沙箱子目录失败: " + e.getMessage());
        }
        Path outPath = sandboxWork.resolve("out.json");

        // 3. 组命令：node <abs script> <userArgs...> --output out.json
        StringBuilder cmd = new StringBuilder("node");
        cmd.append(' ').append(shellQuote(scriptPath.toString()));
        for (String arg : req.extraArgs) cmd.append(' ').append(shellQuote(arg));
        cmd.append(" --output ").append(shellQuote(outPath.toString()));

        // 4. env：CDP_ENDPOINT + NODE_PATH + 可选 session/project meta
        Map<String, String> env = new HashMap<>();
        env.put("CDP_ENDPOINT", browserProps.getCdpEndpoint());
        env.put("NODE_PATH", browserProps.getNodeModulesPath());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        if (req.sessionId != null) env.put("SMARTPAI_SESSION_ID", req.sessionId);
        if (req.orgTag != null) env.put("SMARTPAI_ORG_TAG", req.orgTag);
        if (req.extraEnv != null) env.putAll(req.extraEnv);

        BashExecutor.Request bashReq = new BashExecutor.Request();
        bashReq.command = cmd.toString();
        bashReq.subDir = subDir;
        bashReq.extraEnv = env;
        bashReq.timeoutSeconds = req.timeoutSeconds > 0
                ? req.timeoutSeconds : browserProps.getDefaultTimeoutSeconds();
        bashReq.cancelled = req.cancelled;

        log.info("Run browser skill {} script={} cmd={}",
                req.skillName, req.scriptRelative, cmd);

        BashExecutor.Result res = bashExecutor.run(bashReq);

        // 5. 解析 out.json
        JsonNode payload = null;
        if (Files.isRegularFile(outPath)) {
            try {
                payload = MAPPER.readTree(Files.readString(outPath, StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("out.json 解析失败 path={} err={}", outPath, e.getMessage());
            }
        }

        // 6. 常见 CDP 错误归因
        String errorType = payload != null && payload.has("errorType")
                ? payload.get("errorType").asText(null) : null;
        boolean payloadOk = payload != null && payload.path("ok").asBoolean(false);

        if (!payloadOk && errorType == null) {
            String scan = String.join(" ",
                    res.stderr() == null ? "" : res.stderr(),
                    res.stdout() == null ? "" : truncate(res.stdout(), 1024));
            String low = scan.toLowerCase(Locale.ROOT);
            if (low.contains("econnrefused") || low.contains("connection refused")
                    || low.contains("failed to connect")) {
                errorType = "cdp_unreachable";
            } else if (low.contains("err_module_not_found") || low.contains("cannot find package 'playwright'")) {
                errorType = "playwright_missing";
            } else if (low.contains("滑块") || low.contains("验证码") || low.contains("verification")) {
                errorType = "captcha";
            }
        }

        if (payload == null) {
            return RunResult.error(
                    res.error() != null ? res.error() : "bash_failed",
                    res.errorMessage() != null ? res.errorMessage()
                            : ("脚本无 out.json 输出。exit=" + res.exitCode()
                            + " stderr=" + truncate(res.stderr(), 400)));
        }
        if (!payloadOk) {
            String msg = payload.has("error") ? payload.get("error").asText("unknown") : "skill 返回 ok=false";
            return new RunResult(false, payload,
                    errorType == null ? "skill_failed" : errorType, msg,
                    sandboxWork.toString(), res);
        }
        return new RunResult(true, payload, null, null, sandboxWork.toString(), res);
    }

    // ---------- helpers ----------

    private String shellQuote(String s) {
        if (s == null) return "\"\"";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            if (s.contains(" ") || s.contains("\\")) {
                return "\"" + s.replace("\"", "\\\"") + "\"";
            }
            return s;
        }
        if (s.contains("'")) {
            return "'" + s.replace("'", "'\\''") + "'";
        }
        return "'" + s + "'";
    }

    private String safeSession(String s) {
        if (s == null || s.isBlank()) return "ad-hoc";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        String t = sb.toString();
        return t.length() > 24 ? t.substring(0, 24) : t;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---------- 传输对象 ----------

    public static final class RunRequest {
        public String orgTag;
        public String sessionId;
        public String skillName;
        public String scriptRelative;
        public List<String> extraArgs = new ArrayList<>();
        public Map<String, String> extraEnv;
        public int timeoutSeconds;
        public AtomicBoolean cancelled;
    }

    public record RunResult(boolean ok, JsonNode payload,
                            String errorType, String errorMessage, String workDir,
                            BashExecutor.Result bash) {
        public static RunResult error(String errorType, String errorMessage) {
            return new RunResult(false, null, errorType, errorMessage, null, null);
        }
    }
}
