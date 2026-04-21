package com.yizhaoqi.smartpai.service.skill;

import com.yizhaoqi.smartpai.config.SkillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bash 执行器：沙箱 + 白名单 + 超时 + 取消。
 *
 * 安全约束：
 *  1. 所有命令必须落在 sandboxRoot 下的某个工作目录里；调用方传 subDir（通常是 sessionId）
 *     保证会话之间互相隔离；
 *  2. 命令首 token 必须在 {@link SkillProperties.Bash#getAllowList}；
 *  3. 命令全文不得出现 {@link SkillProperties.Bash#getDenyTokens}；
 *  4. 绝对路径参数不得落在 {@link SkillProperties.Bash#getDenyPathPrefixes}；
 *  5. 超时由 {@link SkillProperties.Bash#getTimeoutSeconds} 控制，超时 kill。
 *  6. 支持在 PATH 头部注入 skill 的 scripts 目录，便于脚本被 which 命中。
 *
 * 结果：stdout + stderr + exitCode，限制在 4MB 以内，避免灌爆 LLM context。
 */
@Component
public class BashExecutor {

    private static final Logger logger = LoggerFactory.getLogger(BashExecutor.class);
    private static final int MAX_OUTPUT_CHARS = 4 * 1024 * 1024;

    private final SkillProperties properties;

    public BashExecutor(SkillProperties properties) {
        this.properties = properties;
    }

    public Result run(Request req) {
        SkillProperties.Bash cfg = properties.getBash();
        if (!cfg.isEnabled()) {
            return Result.error("bash_disabled", "BashTool 已在 skills.bash.enabled 中被禁用");
        }
        if (req.command == null || req.command.isBlank()) {
            return Result.error("empty_command", "command 为空");
        }

        // 白名单校验
        String first = firstToken(req.command);
        if (!cfg.getAllowList().contains(first)) {
            return Result.error("not_allow_listed",
                    "命令主程序 '" + first + "' 不在白名单。允许的：" + cfg.getAllowList());
        }

        String lower = req.command.toLowerCase(Locale.ROOT);
        for (String deny : cfg.getDenyTokens()) {
            String low = deny.toLowerCase(Locale.ROOT);
            if (low.isEmpty()) continue;
            // 纯字母数字 token 用单词边界匹配，避免 "skill" 命中 "kill"、"user" 命中 "su"、"form" 命中 "rm"
            boolean plain = low.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
            boolean hit;
            if (plain) {
                hit = containsWord(lower, low);
            } else {
                hit = lower.contains(low);
            }
            if (hit) {
                return Result.error("deny_token", "命令包含禁用 token: " + deny);
            }
        }
        for (String prefix : cfg.getDenyPathPrefixes()) {
            if (req.command.contains(prefix)) {
                return Result.error("deny_path", "命令引用了禁止访问的路径前缀: " + prefix);
            }
        }

        // 工作目录
        Path sandboxRoot = Paths.get(cfg.getSandboxRoot()).toAbsolutePath();
        String subDir = req.subDir == null || req.subDir.isBlank() ? "default" : safeDirName(req.subDir);
        Path workDir = sandboxRoot.resolve(subDir);
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            return Result.error("mkdir_failed", "创建沙箱目录失败: " + e.getMessage());
        }

        // PATH 注入
        List<String> pathSegments = new ArrayList<>();
        if (req.extraPathEntries != null) pathSegments.addAll(req.extraPathEntries);
        String origPath = System.getenv("PATH");
        if (origPath != null) pathSegments.add(origPath);
        String finalPath = String.join(System.getProperty("path.separator"), pathSegments);

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        List<String> argv;
        if (windows) {
            // Windows 环境一般没有 bash；用 cmd /c，但 allowList 里第一个如果是 bash 就交给 git-bash 兼容
            argv = List.of("cmd.exe", "/c", req.command);
        } else {
            argv = List.of("sh", "-lc", req.command);
        }

        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(workDir.toFile())
                .redirectErrorStream(false);
        pb.environment().put("PATH", finalPath);
        pb.environment().put("HOME", workDir.toString());
        pb.environment().put("PAISMART_SANDBOX", "1");
        if (req.extraEnv != null) pb.environment().putAll(req.extraEnv);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return Result.error("start_failed", "启动进程失败: " + e.getMessage());
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread tOut = new Thread(() -> readInto(proc.getInputStream(), stdout), "bash-stdout");
        Thread tErr = new Thread(() -> readInto(proc.getErrorStream(), stderr), "bash-stderr");
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        tOut.start();
        tErr.start();

        long timeoutSec = req.timeoutSeconds > 0 ? req.timeoutSeconds : cfg.getTimeoutSeconds();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
        boolean cancelled = false;
        boolean timedOut = false;

        try {
            while (true) {
                if (req.cancelled != null && req.cancelled.get()) {
                    cancelled = true; break;
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true; break;
                }
                if (proc.waitFor(200, TimeUnit.MILLISECONDS)) break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cancelled = true;
        }

        if ((cancelled || timedOut) && proc.isAlive()) {
            proc.destroy();
            try { proc.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (proc.isAlive()) proc.destroyForcibly();
        }

        try { tOut.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { tErr.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        int exitCode = proc.isAlive() ? -1 : proc.exitValue();
        String stopReason = cancelled ? "cancelled" : (timedOut ? "timeout" : "exited");
        return new Result(exitCode, truncate(stdout.toString()), truncate(stderr.toString()),
                stopReason, null, null, workDir.toString());
    }

    private void readInto(java.io.InputStream in, StringBuilder sink) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sink.length() < MAX_OUTPUT_CHARS) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            logger.debug("read sink err={}", e.getMessage());
        }
    }

    private String truncate(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) return s;
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n[... truncated " + (s.length() - MAX_OUTPUT_CHARS) + " chars ...]";
    }

    /**
     * 判断 haystack 里是否出现独立单词 needle（前后是非 [A-Za-z0-9_] 字符或字符串首尾）。
     * 用于 deny token 检测：避免路径里的子串误伤（e.g. 'skill' 被当作 'kill'）。
     */
    static boolean containsWord(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return false;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return false;
            boolean leftOk = idx == 0 || !isWordChar(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean rightOk = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = idx + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private String firstToken(String cmd) {
        int i = 0;
        while (i < cmd.length() && Character.isWhitespace(cmd.charAt(i))) i++;
        int start = i;
        while (i < cmd.length() && !Character.isWhitespace(cmd.charAt(i))) i++;
        return cmd.substring(start, i);
    }

    private String safeDirName(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "default" : sb.toString();
    }

    public static final class Request {
        public String command;
        public String subDir;
        public List<String> extraPathEntries;
        public Map<String, String> extraEnv;
        public int timeoutSeconds;
        public AtomicBoolean cancelled;

        public static Request of(String command, String subDir) {
            Request r = new Request();
            r.command = command;
            r.subDir = subDir;
            return r;
        }
    }

    public record Result(int exitCode, String stdout, String stderr, String stopReason,
                         String error, String errorMessage, String workDir) {
        public static Result error(String code, String msg) {
            return new Result(-1, "", "", "error", code, msg, null);
        }
        public boolean success() { return exitCode == 0 && error == null; }
    }
}
