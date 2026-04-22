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
 * {@code spotlight_campaign_list}：列出当前广告账户下的广告计划（campaign）。
 *
 * <p>底层：{@code POST /api/open/jg/campaign/list}。
 * MAPI 对分页字段有个坑：{@code page} 必须是 <b>嵌套对象</b>
 * {@code {"page":1, "page_size":20}}，直接传字符串或顶层整数都会报 400
 * {@code "required struct with json string format, but got not string or string slice"}。
 * 本 tool 内部已处理，LLM 只要传 integer page/pageSize 即可。
 *
 * <p>返回字段：{@code data.page.total_count} / {@code data.page.page_index} +
 * {@code data.base_campaign_dtos[]}。
 */
@Component
public class SpotlightCampaignListTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightCampaignListTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .integerProp("page", "页码，从 1 开始（默认 1）", false)
                .integerProp("pageSize", "每页条数，默认 20，上限 100", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_campaign_list"; }

    @Override public String description() {
        return "列出当前聚光广告账户下的广告计划（campaign）。只读。"
                + "注意：这是你自家广告投放的计划，不是博主/笔记/竞品数据。需要 org 下有 ACTIVE 的 xhs_spotlight 凭证。";
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
        // MAPI /jg/campaign/list 要求 page 是嵌套对象 {page, page_size} —— 见 javadoc
        ObjectNode pageNode = body.putObject("page");
        pageNode.put("page", page);
        pageNode.put("page_size", pageSize);

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/campaign/list", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode data = r.data();
        JsonNode pageInfo = data.path("page");
        JsonNode campaigns = data.hasNonNull("base_campaign_dtos")
                ? data.path("base_campaign_dtos")
                : data.path("campaign_infos");
        int totalCount = pageInfo.path("total_count").asInt(data.path("total_count").asInt(0));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page);
        out.put("pageSize", pageSize);
        out.put("totalCount", totalCount);
        out.put("campaigns", campaigns);
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_campaign_list: %d campaigns (total=%d, page=%d/%d, %dms)",
                campaigns.size(), totalCount, page, pageSize, r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
