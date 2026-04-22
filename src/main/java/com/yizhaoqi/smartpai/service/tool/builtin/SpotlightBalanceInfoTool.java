package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolErrors;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.SpotlightApiClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code spotlight_balance_info}：查询"自家"广告账户的余额信息（不是博主数据！）。
 *
 * <p>底层走 {@code POST /api/open/jg/account/balance/info}，是验证 access_token 是否仍有效
 * 的最轻量探针，同时也是运营最常问"今天还能花多少钱"的接口。
 */
@Component
public class SpotlightBalanceInfoTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightBalanceInfoTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_balance_info"; }

    @Override public String description() {
        return "查询当前组织的小红书聚光广告账户余额（cash / available / today_spend 等）。"
                + "注意：这是你自家广告账户的充值余额，不是博主/笔记/竞品数据——"
                + "要抓博主笔记请用 xhs_refresh_creator / xhs_search_notes。"
                + "需要 org 下已有 ACTIVE 的 xhs_spotlight 凭证；access_token 过期时会返回 unauthorized，"
                + "可继续调 spotlight_oauth_refresh 后重试。只读。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，无法定位聚光凭证");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        ObjectNode body = mapper.createObjectNode();
        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/account/balance/info", body);
        if (!r.ok()) {
            return mapError(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        out.put("balance", r.data());  // 原样返回（total_balance 等，单位：分）
        String summary = String.format(
                "spotlight_balance_info: available=%s today_spend=%s (req_id=%s, %dms)",
                r.data().path("available_balance").asText("?"),
                r.data().path("today_spend").asText("?"),
                r.requestId(), r.latencyMs());
        return ToolResult.of(out, summary);
    }

    static ToolResult mapError(SpotlightApiClient.Result r) {
        String code = switch (r.errorType()) {
            case "no_credential" -> ToolErrors.NO_TARGET;
            case "unauthorized" -> ToolErrors.COOKIE_INVALID;
            case "not_found_endpoint" -> ToolErrors.NOT_FOUND;
            case "network" -> "network";
            default -> ToolErrors.INTERNAL;
        };
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("errorType", r.errorType());
        if (r.bizCode() != null) extra.put("bizCode", r.bizCode());
        if (r.requestId() != null) extra.put("requestId", r.requestId());
        if (r.latencyMs() != null) extra.put("latencyMs", r.latencyMs());
        return ToolResult.error(code, r.message(), extra);
    }
}
