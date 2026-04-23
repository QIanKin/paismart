package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 蒲公英账号角色探测器。直接 {@code GET https://pgy.xiaohongshu.com/api/solar/user/info}——
 * 这个接口不需要 x-s 签名，只靠 cookie，是我们区分"品牌主 vs KOL"最稳定的方式。
 *
 * <p>为什么不在 Python skill 里做？Python skill 调用链太长（Docker exec → sandbox →
 * virtualenv → Spider_XHS 导入 → 多次 HTTP）；这里只是一个 HTTP GET，用 JDK HttpClient
 * 2~3 秒出结果，而且不会误伤 cookie 状态。
 *
 * <p>Spider_XHS 的 PuGongYingAPI 只对品牌主有效：
 * <ul>
 *   <li>{@code get_user_by_page} / {@code send_invite} 都拿 {@code self_info.data.userId} 当 brandUserId；</li>
 *   <li>博主（KOL）账号登录 pgy.xiaohongshu.com 进的是 KOL 端，没有"选 KOL"页面，
 *       所有 {@code /api/solar/cooperator/*} 接口都会返回业务错误或 403。</li>
 * </ul>
 *
 * <p>这个服务被 {@link com.yizhaoqi.smartpai.service.tool.builtin.XhsPgyWhoamiTool} 以及
 * {@code XhsFetchPgyKolTool} / {@code XhsPgyKolDetailTool} 的"预检"复用，统一判断逻辑。
 */
@Component
public class PgyRoleProbe {

    private static final Logger log = LoggerFactory.getLogger(PgyRoleProbe.class);
    private static final String ENDPOINT = "https://pgy.xiaohongshu.com/api/solar/user/info";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 拿原始 cookie 字符串（{@code "k1=v1; k2=v2"}）去打一把 user/info，解析出角色。
     * 本方法不抛异常——任何网络/解析错误都归一化进 {@link Result}。
     */
    public Result probe(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Result.failed("cookie_missing", "空 cookie", -1, null, null);
        }
        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Cookie", cookieHeader)
                    .header("Accept", "application/json")
                    .header("Referer", "https://pgy.xiaohongshu.com/solar/pre-trade/kol")
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .GET()
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("pgy user/info 请求失败", e);
            return Result.failed("network", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    -1, null, null);
        }
        long latency = System.currentTimeMillis() - t0;
        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();

        if (status == 401 || status == 403) {
            return new Result("unauthorized", false, false,
                    null, null,
                    "HTTP " + status + "，cookie 可能已过期/被风控",
                    status, -1, null, latency, body.substring(0, Math.min(300, body.length())));
        }

        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(body);
        } catch (Exception e) {
            return new Result("parse_error", false, false,
                    null, null,
                    "响应非 JSON：" + body.substring(0, Math.min(200, body.length())),
                    status, -1, null, latency, body.substring(0, Math.min(300, body.length())));
        }
        int code = parsed.path("code").asInt(-1);
        String msg = parsed.path("msg").asText(parsed.path("message").asText(""));
        boolean success = parsed.path("success").asBoolean(code == 0);
        JsonNode data = parsed.path("data");

        if (!success) {
            return new Result("api_rejected", false, false,
                    null, null,
                    String.format("蒲公英 code=%d msg=%s", code, msg),
                    status, code, msg, latency, null);
        }

        String role = classifyRole(data, msg);
        boolean qualified = "brand".equals(role) || "agency".equals(role);
        String userId = text(data, "userId");
        String nickName = text(data, "nickName");
        String reason = qualified
                ? null
                : "当前账号角色 role=" + role + "（预期 brand/agency）。Spider_XHS 的 PuGongYingAPI "
                + "只对'蒲公英品牌主/机构'账号有效，KOL/个人账号无法拉取 KOL 列表与详情。";
        return new Result(role, qualified, true, userId, nickName, reason,
                status, code, msg, latency, null);
    }

    /** 角色分类启发式：字段/角色名/用户类型多管齐下。 */
    private static String classifyRole(JsonNode data, String msg) {
        if (data == null || data.isMissingNode() || data.isNull()) return "anonymous";
        String userId = text(data, "userId");
        if (userId == null || userId.isBlank()) return "anonymous";

        String role = text(data, "role");
        if (role != null) {
            String r = role.toLowerCase();
            if (r.contains("brand") || r.contains("advertiser")) return "brand";
            if (r.contains("agency") || r.contains("mcn")) return "agency";
            if (r.contains("kol") || r.contains("blogger")) return "kol";
        }
        String userType = text(data, "userType");
        if (userType != null) {
            String t = userType.toLowerCase();
            if (t.contains("brand") || "2".equals(userType)) return "brand";
            if (t.contains("agency") || t.contains("mcn") || "3".equals(userType)) return "agency";
            if (t.contains("kol") || "1".equals(userType)) return "kol";
        }
        if (hasAny(data, "brandAuthStatus", "brandUserId", "cooperateBrandId", "brandId")) return "brand";
        if (hasAny(data, "agencyId", "mcnId")) return "agency";
        if (hasAny(data, "kolLevel", "isKol", "kolUserId", "kolAuthStatus")) return "kol";
        return "unknown";
    }

    private static boolean hasAny(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull() && !v.isMissingNode()) return true;
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 探测结果。{@code reachable=false} 表示 HTTP/JSON 层面就挂了；
     * {@code reachable=true && brandQualified=false} 表示接口通了但角色不够——
     * 这两种场景的处理完全不同（前者是 cookie/网络问题，后者是账号资质问题）。
     *
     * @param role          "brand" / "agency" / "kol" / "unknown" / "anonymous"
     *                      / "unauthorized" / "api_rejected" / "parse_error" / "network" / "cookie_missing"
     * @param brandQualified 是否是品牌主/机构（可以用 PuGongYingAPI）
     * @param reachable      HTTP 200 且 JSON 解析成功且 success=true
     * @param userId         蒲公英 userId（成功时非空）
     * @param nickName       蒲公英 nickName（成功时非空）
     * @param reason         失败 / 不合格时的人话原因
     * @param httpStatus     HTTP 状态码（-1 表示压根没发出去）
     * @param apiCode        蒲公英业务 code
     * @param apiMsg         蒲公英业务 msg
     * @param latencyMs      探测耗时（ms）
     * @param bodyHead       失败时响应头部（调试用，最多 300 字符）
     */
    public record Result(
            String role,
            boolean brandQualified,
            boolean reachable,
            String userId,
            String nickName,
            String reason,
            int httpStatus,
            int apiCode,
            String apiMsg,
            long latencyMs,
            String bodyHead
    ) {
        static Result failed(String role, String reason, int httpStatus, String apiMsg, String bodyHead) {
            return new Result(role, false, false, null, null, reason,
                    httpStatus, -1, apiMsg, 0, bodyHead);
        }
    }
}
