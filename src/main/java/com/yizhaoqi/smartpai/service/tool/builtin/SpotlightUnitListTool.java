package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.SpotlightApiClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code spotlight_unit_list}：列出当前广告账户下的广告单元（unit）。
 *
 * <p>底层：{@code POST /api/open/jg/unit/list}。MAPI 要求 page/page_size 以整数传入即可（与 campaign 不同）。
 */
@Component
public class SpotlightUnitListTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightUnitListTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .integerProp("page", "页码，从 1 开始（默认 1）", false)
                .integerProp("pageSize", "每页条数，默认 20，上限 100", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_unit_list"; }

    @Override public String description() {
        return "列出当前聚光广告账户下的广告单元（unit / adgroup）。"
                + "返回 unit_infos 数组 + total_count。只读。"
                + "注意：这是你自家广告投放的单元，不是博主/笔记。需要 org 下有 ACTIVE 的 xhs_spotlight 凭证。";
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
        int page = Math.max(1, input.path("page").asInt(1));
        int pageSize = Math.min(100, Math.max(1, input.path("pageSize").asInt(20)));
        ObjectNode body = mapper.createObjectNode();
        body.put("page", page);
        body.put("page_size", pageSize);

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/unit/list", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page);
        out.put("pageSize", pageSize);
        out.put("totalCount", r.data().path("total_count").asInt(0));
        out.put("units", r.data().path("unit_infos"));
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_unit_list: %d units (total=%d, page=%d/%d, %dms)",
                r.data().path("unit_infos").size(),
                out.get("totalCount"), page, pageSize, r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
