package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolErrors;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.SpotlightTokenRefresher;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code spotlight_oauth_refresh}：拿存量 refresh_token 去 MAPI 换新的 access_token。
 *
 * <p>调用三选一（按优先级）：
 * <ol>
 *   <li>{@code id}：精准刷指定 cookie 行</li>
 *   <li>{@code accountLabel}：按标签找当前 org 的 xhs_spotlight 记录（label 应全 org 唯一）</li>
 *   <li>什么都不传：默认找当前 org 下第一条 ACTIVE 的 xhs_spotlight 记录</li>
 * </ol>
 *
 * <p>成功后：
 * <ul>
 *   <li>凭证 JSON 内的 accessToken / refreshToken / expiresAt 三字段全部更新（refreshToken 聚光会轮换）</li>
 *   <li>status 被重置为 ACTIVE，failCount/lastError 清空（走 {@link XhsCookieService#update}）</li>
 * </ul>
 *
 * <p>失败常见 errorType：{@code config_missing / not_found / wrong_platform / missing_refresh_token /
 * remote_error / internal}，文本直接返回给 LLM 让它知道该怎么回复用户。
 *
 * <p>权限：所有登录用户可用。破坏性：是（写回 cookie 行，走二次确认协议）。
 */
@Component
public class SpotlightOauthRefreshTool implements Tool {

    private final SpotlightTokenRefresher refresher;
    private final XhsCookieService cookies;
    private final JsonNode schema;

    public SpotlightOauthRefreshTool(SpotlightTokenRefresher refresher, XhsCookieService cookies) {
        this.refresher = refresher;
        this.cookies = cookies;
        this.schema = ToolInputSchemas.object()
                .integerProp("id",
                        "要刷新的 cookie 行 id。留空则按 accountLabel 或默认策略挑选。",
                        false)
                .stringProp("accountLabel",
                        "按账号标签挑选当前 org 的 xhs_spotlight 记录。id 和 accountLabel 都为空时默认挑第一条 ACTIVE 记录。",
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_oauth_refresh"; }

    @Override public String description() {
        return "用存量 refresh_token 调用小红书聚光 MAPI /oauth2/refresh_token，换一对新的 access_token/refresh_token 并写回数据库。"
                + "适用于 ping 显示 token 快过期或已过期、或调数据工具（spotlight_balance_info / spotlight_campaign_list / "
                + "spotlight_unit_list / spotlight_report_offline_advertiser）返回 cookie_invalid 时的自愈。"
                + "属于破坏性写操作，首次调用会要求二次确认。未配 XHS_SPOTLIGHT_APP_ID/SECRET 时会返回 config_missing。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isDestructive(JsonNode input) { return true; }

    /** 多个并发 refresh 同一条会让 refresh_token 冲突，串行更安全。 */
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        // Agent godmode：OAuth 刷新不再限 admin。二次确认 + 并发串行已经够稳。
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，无法定位当前组织");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        Long id = resolveTarget(ctx.orgTag(), input);
        if (id == null) {
            return ToolResult.error(ToolErrors.NO_TARGET,
                    "当前组织下没有可刷新的聚光凭证。请先去数据源页录入聚光账号（/data-sources → 聚光）。");
        }
        SpotlightTokenRefresher.Result r = refresher.refresh(id, ctx.orgTag());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("ok", r.ok());
        data.put("errorType", r.errorType());
        data.put("message", r.message());
        data.put("newExpiresAt", r.newExpiresAt());
        data.put("accessTokenTtlSeconds", r.accessTokenTtlSeconds());

        if (!r.ok()) {
            // 把 refresher 的 errorType 直接作为 errorCode 透出——它已经是结构化的（config_missing/not_found 等）
            String code = r.errorType() == null ? ToolErrors.INTERNAL : r.errorType();
            String human = humanizeRefreshError(code, r.message(), id);
            return ToolResult.error(code, human, data);
        }
        String summary = String.format("spotlight_oauth_refresh → #%d 刷新成功，新 expiresAt=%s (ttl=%ds)",
                id, r.newExpiresAt(), r.accessTokenTtlSeconds());
        return ToolResult.of(data, summary);
    }

    /** 把 refresher 原始 errorType + message 翻成对用户友好的中文说明。 */
    private String humanizeRefreshError(String code, String rawMsg, Long id) {
        String suffix = rawMsg == null ? "" : "（" + rawMsg + "）";
        return switch (code) {
            case ToolErrors.CONFIG_MISSING ->
                    "聚光开放平台 app_id/secret 未配置。请让运维在后端 .env 里补上 XHS_SPOTLIGHT_APP_ID / XHS_SPOTLIGHT_APP_SECRET 后重启服务。";
            case ToolErrors.NOT_FOUND ->
                    "cookie #" + id + " 不存在或不属于当前组织" + suffix;
            case ToolErrors.WRONG_PLATFORM ->
                    "cookie #" + id + " 不是聚光凭证，无法走 OAuth 刷新流程" + suffix;
            case ToolErrors.MISSING_REFRESH_TOKEN ->
                    "cookie #" + id + " 没有 refresh_token，无法自动续签。请到数据源页重新录入完整聚光凭证。";
            case "remote_error", ToolErrors.UPSTREAM_ERROR, ToolErrors.UPSTREAM_REJECTED ->
                    "聚光 API 返回失败" + suffix + "。可能是 refresh_token 已被使用或过期，需要到聚光开放平台重新授权拿一对新凭证。";
            case ToolErrors.NETWORK, ToolErrors.TIMEOUT ->
                    "聚光 API 网络不可达" + suffix + "。检查服务器到 adapi.xiaohongshu.com 的出站连通性。";
            default ->
                    "聚光凭证刷新失败" + suffix;
        };
    }

    private Long resolveTarget(String orgTag, JsonNode input) {
        if (input.hasNonNull("id")) {
            return input.get("id").asLong();
        }
        String label = null;
        if (input.hasNonNull("accountLabel")) {
            String v = input.get("accountLabel").asText(null);
            if (v != null && !v.isBlank()) label = v.trim();
        }
        List<XhsCookie> list = cookies.list(orgTag);
        XhsCookie match = null;
        for (XhsCookie c : list) {
            if (!"xhs_spotlight".equalsIgnoreCase(c.getPlatform())) continue;
            if (label != null) {
                if (label.equals(c.getAccountLabel())) { match = c; break; }
            } else if (c.getStatus() == XhsCookie.Status.ACTIVE) {
                // 默认挑第一条 ACTIVE（list 已按 id desc 排序，= 最新一条）
                match = c;
                break;
            }
        }
        return match == null ? null : match.getId();
    }
}
