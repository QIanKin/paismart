package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 小红书聚光 MarketingAPI OAuth2 token 刷新器。
 *
 * <p>封装 {@code POST https://adapi.xiaohongshu.com/api/open/oauth2/refresh_token}：
 * 请求体 {@code {app_id, secret, refresh_token}}，响应 {@code data.access_token / refresh_token /
 * access_token_expires_in / refresh_token_expires_in}。成功后把新的凭证（含新 expiresAt）重新加密落回
 * {@link XhsCookie}。refresh_token 同时会被轮换（MAPI 文档约定：refresh 一次会下发新 refresh_token）。
 *
 * <h3>失败语义</h3>
 * <ul>
 *   <li>没配 app-id/secret → {@link Result#configMissing()}，让上层提示"联系运维配 XHS_SPOTLIGHT_APP_ID/SECRET"</li>
 *   <li>cookie 不存在 / 跨 org → {@link Result#notFound(Long)}</li>
 *   <li>cookie 不是 xhs_spotlight 平台 → {@link Result#wrongPlatform(String)}</li>
 *   <li>存储的 JSON 里找不到 refresh_token → {@link Result#missingRefreshToken()}</li>
 *   <li>远端 HTTP 非 200 / success=false → {@link Result#remoteError(int, String)}</li>
 *   <li>任何其他异常 → {@link Result#internal(String)}</li>
 * </ul>
 *
 * <p><b>为什么不走 spotlight-mapi Go SDK？</b>因为项目本身是 Java，拉一个 Go runtime 做单次 HTTP 请求
 * 是杀鸡用牛刀。我们直接按 SDK 定义的 URL + 请求体格式 POST 就行。
 */
@Service
public class SpotlightTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(SpotlightTokenRefresher.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String DEFAULT_URL = "https://adapi.xiaohongshu.com/api/open/oauth2/refresh_token";

    private final XhsCookieService cookieService;
    private final CookieCipher cipher;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${smartpai.xhs.spotlight.app-id:}")
    private String appId;

    @Value("${smartpai.xhs.spotlight.app-secret:}")
    private String appSecret;

    @Value("${smartpai.xhs.spotlight.refresh-url:" + DEFAULT_URL + "}")
    private String refreshUrl;

    public SpotlightTokenRefresher(XhsCookieService cookieService, CookieCipher cipher) {
        this.cookieService = cookieService;
        this.cipher = cipher;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 刷新指定 cookie 行上的 spotlight OAuth token。
     * 成功时把新 access_token / refresh_token / expiresAt 写回同一行（JSON 内嵌字段）。
     *
     * @param cookieId    xhs_cookies.id
     * @param ownerOrgTag 调用方 org（用于鉴权隔离）
     * @return {@link Result}
     */
    public Result refresh(Long cookieId, String ownerOrgTag) {
        if (isBlank(appId) || isBlank(appSecret)) {
            return Result.configMissing();
        }
        Optional<XhsCookie> opt = cookieService.findById(cookieId, ownerOrgTag);
        if (opt.isEmpty()) {
            return Result.notFound(cookieId);
        }
        XhsCookie c = opt.get();
        if (!"xhs_spotlight".equalsIgnoreCase(c.getPlatform())) {
            return Result.wrongPlatform(c.getPlatform());
        }

        String plainJson;
        try {
            plainJson = cookieService.decryptFor(cookieId, ownerOrgTag).orElse(null);
        } catch (Exception e) {
            return Result.internal("解密失败：" + e.getMessage());
        }
        if (isBlank(plainJson)) {
            return Result.internal("凭证解密结果为空");
        }

        JsonNode root;
        try {
            root = mapper.readTree(plainJson);
        } catch (Exception e) {
            return Result.internal("凭证 JSON 解析失败：" + e.getMessage());
        }
        String refreshToken = text(root, "refreshToken");
        if (isBlank(refreshToken)) refreshToken = text(root, "refresh_token");
        if (isBlank(refreshToken)) {
            return Result.missingRefreshToken();
        }
        String advertiserId = text(root, "advertiserId");
        if (isBlank(advertiserId)) advertiserId = text(root, "advertiser_id");

        // --- 调远端 ---
        long appIdNum;
        try {
            appIdNum = Long.parseLong(appId.trim());
        } catch (NumberFormatException e) {
            return Result.configMissing();
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("app_id", appIdNum);
        body.put("secret", appSecret.trim());
        body.put("refresh_token", refreshToken);

        HttpRequest req;
        HttpResponse<String> resp;
        try {
            req = HttpRequest.newBuilder(URI.create(refreshUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return Result.internal("HTTP 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        int code = resp.statusCode();
        String respBody = resp.body() == null ? "" : resp.body();
        if (code < 200 || code >= 300) {
            return Result.remoteError(code, truncate(respBody, 512));
        }

        JsonNode respJson;
        try {
            respJson = mapper.readTree(respBody);
        } catch (Exception e) {
            return Result.internal("响应 JSON 无法解析：" + truncate(respBody, 256));
        }
        if (!respJson.path("success").asBoolean(false)) {
            int errCode = respJson.path("code").asInt(respJson.path("errorCode").asInt(-1));
            String msg = firstNonBlank(
                    respJson.path("message").asText(null),
                    respJson.path("errorMsg").asText(null),
                    truncate(respBody, 256));
            return Result.remoteError(errCode, msg);
        }
        JsonNode data = respJson.path("data");
        String newAccess = text(data, "access_token");
        String newRefresh = text(data, "refresh_token");
        long accessTtl = data.path("access_token_expires_in").asLong(7200);
        if (isBlank(newAccess) || isBlank(newRefresh)) {
            return Result.remoteError(200, "data.access_token / refresh_token 缺失：" + truncate(respBody, 256));
        }

        // --- 写回 ---
        String newExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(accessTtl).format(ISO);
        ObjectNode updated = mapper.createObjectNode();
        if (!isBlank(advertiserId)) updated.put("advertiserId", advertiserId);
        updated.put("accessToken", newAccess);
        updated.put("refreshToken", newRefresh);
        updated.put("expiresAt", newExpiresAt);
        String updatedJson = updated.toString();

        // 走 service.update 保证密文 / preview / keys 全部重算
        Optional<XhsCookie> saved = cookieService.update(
                cookieId, ownerOrgTag, updatedJson, null, null, null, XhsCookie.Status.ACTIVE);
        if (saved.isEmpty()) {
            return Result.internal("update 返回空，疑似并发删除");
        }
        log.info("[SpotlightTokenRefresher] 刷新成功 cookieId={} org={} advertiserId={} newExpiresAt={}",
                cookieId, ownerOrgTag, mask(advertiserId), newExpiresAt);
        return Result.ok(newExpiresAt, accessTtl);
    }

    // ---------- 工具函数 ----------

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) if (!isBlank(x)) return x;
        return "";
    }

    private static String mask(String id) {
        if (id == null || id.length() <= 10) return "****";
        return id.substring(0, 4) + "..." + id.substring(id.length() - 4);
    }

    /**
     * 给上层（tool / controller）统一的返回值，避免抛异常。
     * {@code errorType} 对齐 agent tool 失败回灌格式。
     */
    public record Result(
            boolean ok,
            String errorType,
            String message,
            String newExpiresAt,
            Long accessTokenTtlSeconds
    ) {
        public static Result ok(String newExpiresAt, long ttl) {
            return new Result(true, null, null, newExpiresAt, ttl);
        }
        public static Result configMissing() {
            return new Result(false, "config_missing",
                    "未配置 XHS_SPOTLIGHT_APP_ID / XHS_SPOTLIGHT_APP_SECRET，无法调用 MAPI /oauth2/refresh_token。"
                            + "请联系运维在 .env 里补上聚光开放平台分配的 app_id / secret（不是 advertiser_id）。",
                    null, null);
        }
        public static Result notFound(Long id) {
            return new Result(false, "not_found", "cookie #" + id + " 不存在或无权限", null, null);
        }
        public static Result wrongPlatform(String actual) {
            return new Result(false, "wrong_platform",
                    "只能刷新 platform=xhs_spotlight 的凭证，当前 platform=" + actual, null, null);
        }
        public static Result missingRefreshToken() {
            return new Result(false, "missing_refresh_token",
                    "存储的凭证 JSON 里找不到 refreshToken/refresh_token，请重新授权获取完整 token", null, null);
        }
        public static Result remoteError(int code, String body) {
            return new Result(false, "remote_error",
                    "MAPI /oauth2/refresh_token 返回异常 code=" + code + " body=" + body, null, null);
        }
        public static Result internal(String msg) {
            return new Result(false, "internal", msg, null, null);
        }
    }
}
