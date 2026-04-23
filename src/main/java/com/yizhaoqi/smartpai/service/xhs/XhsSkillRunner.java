package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.SkillProperties;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
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
 * xhs-* skill 的公共执行器：
 *  1. 解析 skill 路径（指向 `skills-bundled/_shared/Spider_XHS`）；
 *  2. 挑一个 cookie（按 platform）并塞到子进程环境变量 COOKIES；
 *  3. 在沙箱里生成 out.json；
 *  4. 用 BashExecutor 跑 `python {scriptRel} {args}`；
 *  5. 读 out.json 解析为 JsonNode，并回写 cookie 健康度。
 *
 * 设计：所有 xhs tool（refresh/search/pgy）复用 {@link #run} 即可，不必各自操心 cookie 轮转/BashExecutor 协议。
 */
@Service
public class XhsSkillRunner {

    private static final Logger log = LoggerFactory.getLogger(XhsSkillRunner.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillRegistry skillRegistry;
    private final BashExecutor bashExecutor;
    private final XhsCookieService cookieService;
    private final SkillProperties skillProperties;

    public XhsSkillRunner(SkillRegistry skillRegistry,
                          BashExecutor bashExecutor,
                          XhsCookieService cookieService,
                          SkillProperties skillProperties) {
        this.skillRegistry = skillRegistry;
        this.bashExecutor = bashExecutor;
        this.cookieService = cookieService;
        this.skillProperties = skillProperties;
    }

    /**
     * 执行一个 xhs-* skill 脚本。
     *
     * @param req 请求参数
     * @return {@link RunResult}：ok + JsonNode + cookieId；错误时 ok=false + error
     */
    public RunResult run(RunRequest req) {
        // 1. 解析 skill 路径
        Optional<LoadedSkill> skill = skillRegistry.find(req.skillName, req.orgTag);
        if (skill.isEmpty()) {
            return RunResult.error("skill_not_found",
                    "skill 未加载：" + req.skillName + "。请确认 skills.enabled=true 并重载");
        }
        Path skillRoot = Paths.get(skill.get().rootPath()).toAbsolutePath();
        Path scriptPath = skillRoot.resolve(req.scriptRelative);
        if (!Files.isRegularFile(scriptPath)) {
            return RunResult.error("script_not_found",
                    "脚本不存在: " + scriptPath);
        }

        // 2. 挑 cookie
        XhsCookieService.Picked picked = cookieService
                .pickAvailable(req.orgTag, req.cookiePlatform)
                .orElse(null);
        if (picked == null) {
            return RunResult.error("no_cookie",
                    "当前 org=" + req.orgTag + " 没有可用的 " + req.cookiePlatform
                            + " cookie。请管理员到 '小蜜蜂 · XHS Cookie' 页面录入");
        }

        // 3. 解析 Spider_XHS 共享库路径（默认放在 _shared/Spider_XHS 下）
        Path spiderHome = resolveSpiderHome();

        // 4. 沙箱子目录（每次生成独立目录避免 out.json 互相覆盖）
        String subDir = "xhs-" + safeSession(req.sessionId) + "-" + LocalDateTime.now().format(TS);
        Path sandboxWork = Paths.get(skillProperties.getBash().getSandboxRoot())
                .toAbsolutePath().resolve(subDir);
        try {
            Files.createDirectories(sandboxWork);
        } catch (Exception e) {
            return RunResult.error("mkdir_failed", "创建沙箱子目录失败: " + e.getMessage());
        }
        Path outPath = sandboxWork.resolve("out.json");

        // 5. 组命令：python <abs script> <userArgs...> --output out.json
        StringBuilder cmd = new StringBuilder(pickPython());
        cmd.append(' ').append(shellQuote(scriptPath.toString()));
        for (String arg : req.extraArgs) cmd.append(' ').append(shellQuote(arg));
        cmd.append(" --output ").append(shellQuote(outPath.toString()));

        // 6. env + 运行
        Map<String, String> env = new HashMap<>();
        env.put("COOKIES", picked.cookie());
        env.put("SPIDER_XHS_HOME", spiderHome.toString());
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUNBUFFERED", "1");
        if (req.extraEnv != null) env.putAll(req.extraEnv);

        BashExecutor.Request bashReq = new BashExecutor.Request();
        bashReq.command = cmd.toString();
        bashReq.subDir = subDir;
        bashReq.extraEnv = env;
        bashReq.timeoutSeconds = req.timeoutSeconds > 0 ? req.timeoutSeconds : 120;
        bashReq.cancelled = req.cancelled;
        bashReq.extraPathEntries = null;

        log.info("Run xhs skill {} script={} cookieId={} cmd={}",
                req.skillName, req.scriptRelative, picked.cookieId(), cmd);

        BashExecutor.Result res = bashExecutor.run(bashReq);

        // 7. 解析 out.json 优先；bash Result 次要
        JsonNode payload = null;
        if (Files.isRegularFile(outPath)) {
            try {
                payload = MAPPER.readTree(Files.readString(outPath, StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("out.json 解析失败 path={} err={}", outPath, e.getMessage());
            }
        }

        // 8. cookie 反馈 —— 先看 payload/stderr 有没有反爬明确信号，有就硬失效（一次见血）
        String errorType = payload != null && payload.has("errorType") ? payload.get("errorType").asText(null) : null;
        boolean payloadOk = payload != null && payload.path("ok").asBoolean(false);
        if (payloadOk) {
            cookieService.reportSuccess(picked.cookieId());
        } else {
            String reason = payload != null && payload.has("error") ? payload.get("error").asText("unknown")
                    : ("bash exit=" + res.exitCode() + " stderr=" + truncate(res.stderr(), 160));

            // 脚本已经自我归类为"cookie 没问题、挂在别的原因"的场景 → 不再走反爬扫描，
            // 避免 error 文案里偶然出现 "cookie"/"登录" 等字样被关键词误伤冤杀 cookie。
            // 举例：pgy_kol_detail.py 在 cookie_alive=true 时返 "signature_failed"，
            // 文案里含 "cookie 是活的"，扫了容易擦枪走火。
            if (isScriptClassifiedNonCookieFault(errorType)) {
                cookieService.reportFailure(picked.cookieId(),
                        "skill non-cookie fault: " + errorType + " (" + truncate(reason, 120) + ")");
                log.debug("Skill {} 返回非 cookie 故障 errorType={}，跳过反爬扫描，软失败 cookie=#{}",
                        req.skillName, errorType, picked.cookieId());
            } else {
                // 凭证/反爬硬信号扫描：扫 payload.error + stderr + stdout。
                // 注意不再把 errorType 本身扫进去——errorType 是脚本分类结果，
                // 若真的是 cookie_invalid，脚本该直接走自己的 cookie_invalid 分支，不靠关键词回扫二次确认。
                String scanText = String.join(" ",
                        reason == null ? "" : reason,
                        res.stderr() == null ? "" : res.stderr(),
                        res.stdout() == null ? "" : truncate(res.stdout(), 1024));
                AntiBotHit hit = detectAntiBot(scanText);
                // errorType 本身若明确是 cookie_invalid / login_required，也视为硬信号
                boolean structuralCookieBad = "cookie_invalid".equals(errorType)
                        || "login_required".equals(errorType);
                if (hit != null || structuralCookieBad) {
                    String signal = hit != null ? hit.signal : errorType;
                    XhsCookie.Status targetStatus = hit != null ? hit.targetStatus : XhsCookie.Status.EXPIRED;
                    cookieService.markDead(picked.cookieId(),
                            "xhs signal: " + signal + " (from " + req.skillName + ")",
                            targetStatus);
                    if (errorType == null && hit != null) errorType = hit.errorType;
                    log.warn("Cookie #{} 命中反爬/凭证信号 '{}' → {} （skill={}）",
                            picked.cookieId(), signal, targetStatus, req.skillName);
                } else {
                    cookieService.reportFailure(picked.cookieId(), reason);
                }
            }
        }

        // 9. 构造结果
        if (payload == null) {
            return RunResult.error(
                    res.error() != null ? res.error() : "bash_failed",
                    res.errorMessage() != null ? res.errorMessage()
                            : ("脚本无 out.json 输出。stopReason=" + res.stopReason()
                            + " exit=" + res.exitCode()
                            + " stderr=" + truncate(res.stderr(), 200)));
        }
        if (!payloadOk) {
            String msg = payload.has("error") ? payload.get("error").asText("unknown") : "skill 返回 ok=false";
            return new RunResult(false, payload, picked.cookieId(),
                    errorType == null ? "skill_failed" : errorType,
                    msg,
                    sandboxWork.toString(),
                    res);
        }
        return new RunResult(true, payload, picked.cookieId(), null, null,
                sandboxWork.toString(), res);
    }

    /**
     * 脚本自我分类出的"cookie 是好的、挂在别的原因"的 errorType 白名单。
     * 命中这些就不跑反爬关键词扫描，避免人类文案里的"cookie"/"登录"字样被误伤。
     *
     * <p>白名单里的都是"要给用户看、但不要废 cookie"的错误——签名失效、
     * 接口被改 / 被拦、账号权限不够、Spider_XHS import 失败、上游返非 JSON、
     * 解析错误、无搜索结果等。
     */
    private static boolean isScriptClassifiedNonCookieFault(String errorType) {
        if (errorType == null) return false;
        return switch (errorType) {
            case "signature_failed",
                 "blocked_or_api_changed",
                 "api_changed",
                 "insufficient_permission",
                 "not_brand_account",
                 "remote_non_json",
                 "parse_error",
                 "bootstrap",
                 "bad_input",
                 "ffmpeg_missing",
                 "yt_dlp_missing",
                 "yt_dlp_failed",
                 "url_invalid",
                 "not_found",
                 "no_result",
                 "quota_exceeded",
                 "rate_limit",
                 "network" -> true;
            default -> false;
        };
    }

    // ---------- 反爬信号归因 ----------

    /**
     * 扫描 skill 输出 / stderr / stdout 里的反爬信号，用来决定 cookie 是否需要硬失效。
     * 规则来自 openclaw 备份 {@code batch_comment_v2.mjs:detectRateLimit} + xhs 实际运营经验。
     *
     * <p>命中 BANNED 级别的信号 → {@link XhsCookie.Status#BANNED}（账号异常/封禁）
     * <p>命中 EXPIRED 级别的信号 → {@link XhsCookie.Status#EXPIRED}（凭证过期/验证码/滑块）
     * <p>命中软限流 → 不硬失效，走普通 reportFailure 路径（由连续 5 次失败再升级）
     */
    static AntiBotHit detectAntiBot(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT);

        // BANNED 级（账号已经被限，换 cookie 没用但得停止用它）
        String[] bannedSignals = {"账号异常", "已注销", "账号被封", "被封禁", "账号受限"};
        for (String s : bannedSignals) {
            if (lower.contains(s.toLowerCase(Locale.ROOT))) {
                return new AntiBotHit(s, "account_banned", XhsCookie.Status.BANNED);
            }
        }

        // EXPIRED 级（凭证失效 / 触发人机验证，必须换 cookie 才能恢复）
        String[] expiredSignals = {
                "请重新登录", "登录失效", "未登录",
                "验证码", "人机验证", "安全验证", "滑块验证", "滑块",
                "captcha", "verify", "verification",
                "cookie invalid", "cookie_invalid", "cookie 失效", "cookie已失效"
        };
        for (String s : expiredSignals) {
            if (lower.contains(s.toLowerCase(Locale.ROOT))) {
                return new AntiBotHit(s, "cookie_invalid", XhsCookie.Status.EXPIRED);
            }
        }

        // 软限流（不硬失效，让调用方知道是频率问题）
        String[] softSignals = {
                "操作频繁", "请稍后再试", "系统繁忙", "访问受限",
                "网络异常", "频率过高", "rate limit", "too many requests"
        };
        for (String s : softSignals) {
            if (lower.contains(s.toLowerCase(Locale.ROOT))) {
                // 返回 null 让外层走 reportFailure 的软失败路径；但在 errorType 上打标
                return null;
            }
        }
        return null;
    }

    /**
     * 反爬命中信息。
     */
    record AntiBotHit(String signal, String errorType, XhsCookie.Status targetStatus) {}

    // ---------- helpers ----------

    private Path resolveSpiderHome() {
        // 扫描所有 skill roots，找到第一个含 _shared/Spider_XHS 的
        for (String root : skillProperties.getRoots()) {
            Path cand = Paths.get(root).toAbsolutePath().resolve("_shared").resolve("Spider_XHS");
            if (Files.isDirectory(cand)) return cand;
        }
        // 退路：直接用 ./skills-bundled/_shared/Spider_XHS
        return Paths.get("./skills-bundled/_shared/Spider_XHS").toAbsolutePath();
    }

    private String pickPython() {
        // Windows 上 python3 不一定存在；先试 python，不行再 python3
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("windows") ? "python" : "python3";
    }

    private String shellQuote(String s) {
        if (s == null) return "\"\"";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            // cmd.exe /c 对引号处理简单粗暴
            if (s.contains(" ") || s.contains("\\")) {
                return "\"" + s.replace("\"", "\\\"") + "\"";
            }
            return s;
        }
        // POSIX shell 用单引号最安全
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
        public String skillName;          // e.g. "xhs-user-notes"
        public String scriptRelative;     // e.g. "scripts/fetch_user_notes.py"
        public String cookiePlatform;     // xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan
        public List<String> extraArgs = new ArrayList<>();
        public Map<String, String> extraEnv;
        public int timeoutSeconds;
        public AtomicBoolean cancelled;
    }

    public record RunResult(boolean ok, JsonNode payload, Long cookieId,
                            String errorType, String errorMessage, String workDir,
                            BashExecutor.Result bash) {
        public static RunResult error(String errorType, String errorMessage) {
            return new RunResult(false, null, null, errorType, errorMessage, null, null);
        }
    }
}
