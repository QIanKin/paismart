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
 * {@code spotlight_industry_taxonomy}：获取聚光行业类目（树）。
 *
 * <p>底层：{@code POST /api/open/jg/keyword/industry/taxonomy}。
 * 主要用来配合 {@link SpotlightKeywordCommonRecommendTool} 的行业推词（需要 taxonomy_id）
 * 以及 {@link SpotlightWordBagListTool} 的词包筛选。只读。
 */
@Component
public class SpotlightIndustryTaxonomyTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightIndustryTaxonomyTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_industry_taxonomy"; }

    @Override public String description() {
        return "获取聚光行业类目树（taxonomy_id / taxonomy_name / full_path_name / children）。"
                + "配合 spotlight_keyword_common_recommend 的 industry 推词使用。只读。";
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
        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/keyword/industry/taxonomy", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode data = r.data();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taxonomy", data.path("ads_industry_taxonomy_dict_dto"));
        out.put("allIndustryTaxonomys", data.path("all_industry_taxonomys").asText(""));
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_industry_taxonomy: ok (%dms)", r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
