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
 * {@code spotlight_industry_taxonomy_attribute}：获取行业类目属性。
 *
 * <p>底层：{@code POST /api/open/jg/keyword/industry/taxonomy/attribute}。
 * 给 {@link SpotlightKeywordCommonRecommendTool} 的 industry 推词做细化（attribute_list）。只读。
 */
@Component
public class SpotlightIndustryTaxonomyAttributeTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightIndustryTaxonomyAttributeTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .stringProp("taxonomyId",
                        "行业类目ID，来自 spotlight_industry_taxonomy 返回的 taxonomy_id。", true)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_industry_taxonomy_attribute"; }

    @Override public String description() {
        return "根据行业类目ID查询该类目下的所有可选属性（attribute_name / level）。"
                + "用于为 spotlight_keyword_common_recommend 的行业推词填 attribute_list。只读。";
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
        String taxonomyId = input.path("taxonomyId").asText("").trim();
        if (taxonomyId.isEmpty()) {
            return ToolResult.error("taxonomyId 必填（可先调 spotlight_industry_taxonomy 获取）");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("taxonomy_id", taxonomyId);
        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/keyword/industry/taxonomy/attribute", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode attrs = r.data().path("taxonomy_attribute_dtos");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taxonomyId", taxonomyId);
        out.put("attributes", attrs);
        out.put("count", attrs.size());
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_industry_taxonomy_attribute: %d attrs (taxonomy=%s, %dms)",
                attrs.size(), taxonomyId, r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
