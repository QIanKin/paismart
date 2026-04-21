package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.repository.xhs.XhsCookieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * XhsCookie 连通性自检服务：用当前 cookie 真打一次平台轻量 API，验活不降权。
 *
 * <h3>设计原则</h3>
 * <ol>
 *   <li><b>失败不降权</b>：测试失败只写 lastCheckedAt + lastError，不增加 failCount、不标 EXPIRED。
 *       否则"测试本身"就会污染线上运行时状态——调度策略已经自带失败降权，再在这里叠加是灾难。</li>
 *   <li><b>短超时</b>：单次 10s，避免业务员点按钮后一直等；反爬超时往往也就是 10s 左右。</li>
 *   <li><b>只做"有登录态"级别的探测</b>：不追求等同于真实 Spider_XHS 调用的严格验证——
 *       那需要带 a1 签名，成本高。本 ping 能检出 "cookie 完全过期/被吊销/主机不通" 三大问题就达标。</li>
 * </ol>
 *
 * <h3>各平台探测策略</h3>
 * <ul>
 *   <li>{@code xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan}：以 cookie 访问平台根页，200 + body 长度 > 512 且 URL 未被重定向到 /login 即 OK</li>
 *   <li>{@code xhs_spotlight}：解析 JSON 凭证的 expiresAt，离 now 还有 &gt; 0 秒则认为可用；&lt;= 0 就提示该刷 OAuth</li>
 *   <li>{@code xhs_competitor}：HEAD {supabaseUrl}/rest/v1/ 带 apikey，200 即通</li>
 * </ul>
 */
@Service
public class XhsCookieHealthService {

    private static final Logger log = LoggerFactory.getLogger(XhsCookieHealthService.class);

    /** 根路径探测：key = platform，value = 要 GET 的主页 URL。cookie 整串原样塞 Cookie header。 */
    private static final java.util.Map<String, String> WEB_PROBE_URLS = java.util.Map.of(
            "xhs_pc", "https://www.xiaohongshu.com/",
            "xhs_creator", "https://creator.xiaohongshu.com/creator-center",
            "xhs_pgy", "https://pgy.xiaohongshu.com/",
            "xhs_qianfan", "https://qianfan.xiaohongshu.com/"
    );

    /** 通用反爬兼容 UA。Spider_XHS 风格。 */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final XhsCookieService cookieService;
    private final XhsCookieRepository repo;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public XhsCookieHealthService(XhsCookieService cookieService, XhsCookieRepository repo) {
        this.cookieService = cookieService;
        this.repo = repo;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    /** 单条 cookie 连通性测试入口。 */
    public PingResult ping(Long id, String ownerOrgTag) {
        Optional<XhsCookie> opt = cookieService.findById(id, ownerOrgTag);
        if (opt.isEmpty()) {
            return PingResult.fail("not_found", "凭证不存在或无权限");
        }
        XhsCookie c = opt.get();
        String plain;
        try {
            plain = cookieService.decryptFor(id, ownerOrgTag).orElse(null);
        } catch (Exception e) {
            return recordAndReturn(id, PingResult.fail("decrypt_error", "解密失败：" + e.getMessage()));
        }
        if (plain == null || plain.isBlank()) {
            return recordAndReturn(id, PingResult.fail("empty", "凭证内容为空"));
        }

        PingResult result;
        try {
            result = switch (c.getPlatform() == null ? "" : c.getPlatform().toLowerCase()) {
                case "xhs_pc", "xhs_creator", "xhs_pgy", "xhs_qianfan" -> probeWebCookie(c.getPlatform(), plain);
                case "xhs_spotlight" -> probeSpotlight(plain);
                case "xhs_competitor" -> probeCompetitor(plain);
                default -> PingResult.fail("unsupported_platform", "未接入自检：" + c.getPlatform());
            };
        } catch (Exception e) {
            result = PingResult.fail("internal", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return recordAndReturn(id, result);
    }

    /** 只更新 lastCheckedAt + lastError（若失败），不修改 successCount / failCount / status。 */
    @Transactional
    protected PingResult recordAndReturn(Long id, PingResult r) {
        repo.findById(id).ifPresent(c -> {
            c.setLastCheckedAt(LocalDateTime.now());
            if (!r.ok && r.message != null) {
                c.setLastError(("ping:" + r.errorType + " " + r.message)
                        .substring(0, Math.min(255, ("ping:" + r.errorType + " " + r.message).length())));
            }
            repo.save(c);
        });
        return r.withCheckedAt(LocalDateTime.now());
    }

    // ---------- 平台特化的探测实现 ----------

    private PingResult probeWebCookie(String platform, String cookieString) {
        String url = WEB_PROBE_URLS.get(platform.toLowerCase());
        if (url == null) return PingResult.fail("unsupported_platform", "未配置探测 URL：" + platform);

        long t0 = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", UA)
                    .header("Cookie", cookieString)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - t0;
            int code = resp.statusCode();
            String finalUrl = resp.uri().toString();
            if (code >= 500) return PingResult.fail("http_5xx", "平台侧 " + code, latency);
            if (code >= 400) return PingResult.fail("http_4xx", "被拒：" + code, latency);
            if (finalUrl.contains("/login") || finalUrl.contains("passport")) {
                return PingResult.fail("cookie_invalid", "被跳转到登录页：" + finalUrl, latency);
            }
            String body = resp.body() == null ? "" : resp.body();
            if (body.length() < 512) {
                return PingResult.fail("suspicious_body", "返回体过短 (" + body.length() + "B)，可能被风控", latency);
            }
            // 业务信号：小红书 SSR 的页面会把 userId / red_id 嵌入 __INITIAL_STATE__
            String signal = extractSignal(body);
            return PingResult.ok(latency, signal);
        } catch (java.net.http.HttpTimeoutException e) {
            return PingResult.fail("timeout", "10s 内平台未响应", System.currentTimeMillis() - t0);
        } catch (java.net.ConnectException e) {
            return PingResult.fail("network", "连接失败：" + e.getMessage(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return PingResult.fail("internal", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - t0);
        }
    }

    /**
     * 聚光 OAuth2 的凭证以 JSON 存放：
     *   {"advertiserId":"...","accessToken":"...","refreshToken":"...","expiresAt":"2026-05-01T00:00:00Z"}
     * 首版只做离线判断 expiresAt 是否过期；联网的 token introspect 等有了 AppID 再补。
     */
    private PingResult probeSpotlight(String credentialJson) {
        try {
            JsonNode node = mapper.readTree(credentialJson);
            String expStr = textOrNull(node, "expiresAt");
            if (expStr == null) return PingResult.fail("cookie_invalid", "缺字段 expiresAt，凭证格式不对");
            OffsetDateTime exp;
            try {
                exp = OffsetDateTime.parse(expStr);
            } catch (Exception e) {
                return PingResult.fail("cookie_invalid", "expiresAt 无法解析：" + expStr);
            }
            long remainSec = (exp.toEpochSecond() - OffsetDateTime.now().toEpochSecond());
            if (remainSec <= 0) {
                return PingResult.fail("cookie_invalid", "token 已过期 " + (-remainSec) + "s，请重新授权");
            }
            String advId = textOrNull(node, "advertiserId");
            String signal = "advertiserId=" + (advId == null ? "?" : advId) + ", 剩余 " + remainSec + "s";
            // 离线判断耗时接近 0，给 1ms 占位，避免前端显示 0ms 让人疑惑
            return PingResult.ok(1L, signal);
        } catch (Exception e) {
            return PingResult.fail("cookie_invalid", "JSON 解析失败：" + e.getMessage());
        }
    }

    /**
     * 竞品通道：JSON 里存 {"supabaseUrl":"...","apikey":"..."}，HEAD {url}/rest/v1/ 带 apikey。
     */
    private PingResult probeCompetitor(String credentialJson) {
        long t0 = System.currentTimeMillis();
        try {
            JsonNode node = mapper.readTree(credentialJson);
            String url = textOrNull(node, "supabaseUrl");
            String key = textOrNull(node, "apikey");
            if (url == null || key == null) {
                return PingResult.fail("cookie_invalid", "缺字段 supabaseUrl / apikey");
            }
            String probe = url.endsWith("/") ? url + "rest/v1/" : url + "/rest/v1/";
            HttpRequest req = HttpRequest.newBuilder(URI.create(probe))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", UA)
                    .header("apikey", key)
                    .header("Authorization", "Bearer " + key)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            long latency = System.currentTimeMillis() - t0;
            int code = resp.statusCode();
            if (code >= 500) return PingResult.fail("http_5xx", "Supabase " + code, latency);
            if (code == 401 || code == 403) return PingResult.fail("cookie_invalid", "apikey 被拒：" + code, latency);
            if (code >= 400) return PingResult.fail("http_4xx", "HEAD 失败：" + code, latency);
            return PingResult.ok(latency, "supabase " + code);
        } catch (java.net.http.HttpTimeoutException e) {
            return PingResult.fail("timeout", "10s 内 Supabase 未响应", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return PingResult.fail("internal", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - t0);
        }
    }

    private static String extractSignal(String html) {
        // 试着在 __INITIAL_STATE__ 里抠 userId / red_id。找不到就返回 null，不 fatal
        try {
            int idx = html.indexOf("\"red_id\"");
            if (idx < 0) idx = html.indexOf("\"user\":{\"userId\"");
            if (idx < 0) return null;
            return "html-hit@" + idx;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * ping 结果 DTO。和前端 Api.Xhs.CookiePingResult 字段对齐。
     */
    public record PingResult(
            boolean ok,
            Long latencyMs,
            String errorType,
            String message,
            String platformSignal,
            LocalDateTime checkedAt
    ) {
        public static PingResult ok(long latencyMs, String signal) {
            return new PingResult(true, latencyMs, null, null, signal, null);
        }
        public static PingResult fail(String type, String msg) {
            return new PingResult(false, null, type, msg, null, null);
        }
        public static PingResult fail(String type, String msg, long latencyMs) {
            return new PingResult(false, latencyMs, type, msg, null, null);
        }
        public PingResult withCheckedAt(LocalDateTime t) {
            return new PingResult(ok, latencyMs, errorType, message, platformSignal, t);
        }
    }
}
