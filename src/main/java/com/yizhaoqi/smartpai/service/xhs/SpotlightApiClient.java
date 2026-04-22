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
import java.util.List;
import java.util.Optional;

/**
 * 小红书聚光 MarketingAPI 统一调用客户端。
 *
 * <p>所有聚光数据类 tool（balance / campaign / unit / report 等）都经过这里，统一负责：
 * <ol>
 *   <li>按 orgTag 挑一条 ACTIVE 的 xhs_spotlight 凭证；</li>
 *   <li>解密凭证 JSON，抽出 {@code accessToken} 和 {@code advertiserId}；</li>
 *   <li>对 {@code adapi.xiaohongshu.com/api/open} + 指定 gw 路径发 POST JSON；
 *       MAPI 全部接口都是 POST，即使 SDK 注释写 GET（SDK 已过时）；</li>
 *   <li>解析 BaseResponse {@code {code, success, msg, data, request_id}}；</li>
 *   <li>把成功 data 原样返回；失败情况用 {@link Result} 结构化输出。</li>
 * </ol>
 *
 * <p><b>为什么每次都去拿最新 cookie？</b>因为其他 tool（如 {@code spotlight_oauth_refresh}）
 * 可能刚刚轮换过 access_token，缓存 token 会导致刚刷新完立刻就 401。
 */
@Service
public class SpotlightApiClient {

    private static final Logger log = LoggerFactory.getLogger(SpotlightApiClient.class);
    private static final String DEFAULT_BASE = "https://adapi.xiaohongshu.com/api/open";

    private final XhsCookieService cookieService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    @Value("${smartpai.xhs.spotlight.api-base:" + DEFAULT_BASE + "}")
    private String apiBase;

    public SpotlightApiClient(XhsCookieService cookieService) {
        this.cookieService = cookieService;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 给 tool 用的门面：自动挑 org 内 ACTIVE 的聚光凭证 + POST gwPath + 返回结构化结果。 */
    public Result post(String orgTag, String gwPath, ObjectNode body) {
        Optional<Credential> cred = pickActiveCredential(orgTag);
        if (cred.isEmpty()) {
            return Result.noCredential(orgTag);
        }
        return post(cred.get(), gwPath, body);
    }

    /** 明确指定 credential（token 等）的底层调用入口，便于测试和将来自动 refresh。 */
    public Result post(Credential credential, String gwPath, ObjectNode body) {
        String url = apiBase + (gwPath.startsWith("/") ? gwPath : "/" + gwPath);
        // advertiser_id 大部分接口都要，帮 LLM 少踩一次 400
        if (body != null && !body.has("advertiser_id") && credential.advertiserId() != null) {
            try {
                body.put("advertiser_id", Long.parseLong(credential.advertiserId()));
            } catch (NumberFormatException ignored) {
                body.put("advertiser_id", credential.advertiserId());
            }
        }
        String payload = body == null ? "{}" : body.toString();

        HttpResponse<String> resp;
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Access-Token", credential.accessToken())
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            return Result.network("超时 20s：" + url);
        } catch (Exception e) {
            return Result.network(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long latency = System.currentTimeMillis() - t0;
        int http = resp.statusCode();
        String rawBody = resp.body() == null ? "" : resp.body();

        if (http == 404) {
            return Result.notFoundEndpoint(gwPath, latency);
        }
        if (http == 401 || http == 403) {
            return Result.unauthorized(http, latency, truncate(rawBody, 256));
        }
        // 非 JSON 直接视为网关/反向代理错误
        JsonNode parsed;
        try {
            parsed = mapper.readTree(rawBody);
        } catch (Exception e) {
            return Result.remoteNonJson(http, latency, truncate(rawBody, 512));
        }
        boolean success = parsed.path("success").asBoolean(false);
        int code = parsed.path("code").asInt(-1);
        String msg = parsed.path("msg").asText(parsed.path("message").asText(""));
        String reqId = parsed.path("request_id").asText(null);
        if (!success) {
            return Result.businessError(code, msg, latency, reqId, truncate(rawBody, 512));
        }
        return Result.ok(parsed.path("data"), latency, reqId);
    }

    /** 拿当前 org 下第一条 ACTIVE 的 xhs_spotlight 凭证并解密。 */
    public Optional<Credential> pickActiveCredential(String orgTag) {
        List<XhsCookie> candidates = cookieService.list(orgTag).stream()
                .filter(c -> "xhs_spotlight".equalsIgnoreCase(c.getPlatform()))
                .filter(c -> c.getStatus() == XhsCookie.Status.ACTIVE)
                .toList();
        for (XhsCookie c : candidates) {
            Optional<String> plain = cookieService.decryptFor(c.getId(), orgTag);
            if (plain.isEmpty()) continue;
            try {
                JsonNode root = mapper.readTree(plain.get());
                String access = text(root, "accessToken");
                if (access == null) access = text(root, "access_token");
                if (access == null || access.isBlank()) continue;
                String advId = text(root, "advertiserId");
                if (advId == null) advId = text(root, "advertiser_id");
                return Optional.of(new Credential(c.getId(), advId, access));
            } catch (Exception e) {
                log.warn("[SpotlightApiClient] 解析聚光凭证失败 id={} org={} err={}", c.getId(), orgTag, e.toString());
            }
        }
        return Optional.empty();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 解密出的聚光调用凭证（cookieId 保留用于后续错误回填）。 */
    public record Credential(Long cookieId, String advertiserId, String accessToken) {}

    /** 统一返回结构：tool 直接把它包装成 ToolResult。*/
    public record Result(
            boolean ok,
            String errorType,   // no_credential / unauthorized / not_found_endpoint / network / remote_non_json / business_error
            String message,     // 给人看的错误描述
            Integer bizCode,    // MAPI 的 code 字段（成功 = 0）
            String requestId,   // MAPI 的 request_id，方便对单
            Long latencyMs,
            JsonNode data
    ) {
        public static Result ok(JsonNode data, long latency, String reqId) {
            return new Result(true, null, null, 0, reqId, latency, data);
        }
        public static Result noCredential(String orgTag) {
            return new Result(false, "no_credential",
                    "当前组织 [" + orgTag + "] 下没有 ACTIVE 的 xhs_spotlight 凭证。"
                            + "请先到 /data-sources → 聚光广告 OAuth 面板录入 access_token / refresh_token / advertiser_id。",
                    null, null, null, null);
        }
        public static Result unauthorized(int http, long latency, String bodyHead) {
            return new Result(false, "unauthorized",
                    "聚光 API 返回 " + http + "，access_token 可能已过期或无权访问该 advertiser。"
                            + "可先调 spotlight_oauth_refresh 刷新；如仍失败请到聚光开放平台重新授权。 body=" + bodyHead,
                    null, null, latency, null);
        }
        public static Result notFoundEndpoint(String gwPath, long latency) {
            return new Result(false, "not_found_endpoint",
                    "聚光 API 路径 " + gwPath + " 返回 404。SDK 或文档已过时，需要核对最新 MAPI 接口定义。",
                    null, null, latency, null);
        }
        public static Result network(String msg) {
            return new Result(false, "network",
                    "聚光 API 网络不可达：" + msg + "。检查服务器出站到 adapi.xiaohongshu.com 的连通性。",
                    null, null, null, null);
        }
        public static Result remoteNonJson(int http, long latency, String bodyHead) {
            return new Result(false, "remote_non_json",
                    "聚光 API HTTP=" + http + " 返回非 JSON：" + bodyHead,
                    null, null, latency, null);
        }
        public static Result businessError(int code, String msg, long latency, String reqId, String bodyHead) {
            return new Result(false, "business_error",
                    "聚光 API 业务失败 code=" + code + " msg=" + msg + " req_id=" + reqId + " body=" + bodyHead,
                    code, reqId, latency, null);
        }
    }
}
