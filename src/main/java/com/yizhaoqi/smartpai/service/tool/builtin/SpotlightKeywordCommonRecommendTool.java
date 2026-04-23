package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.SpotlightApiClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code spotlight_keyword_common_recommend}：定向推词（以词推词 / 笔记推词 / 行业推词 / 上下游推词）。
 *
 * <p>底层：{@code POST /api/open/jg/keyword/common/recommend}。
 * 这是<b>创建搜索推广单元前选词</b>的核心接口，用于给 LLM 扩展关键词池。
 *
 * <h3>四种推词模式（{@code requestType}）</h3>
 * <ul>
 *   <li>{@code search} 以词推词：必填 {@code keyword}，LLM 最常用</li>
 *   <li>{@code session} 上下游推词：必填 {@code keyword}（拿上游、下游扩展）</li>
 *   <li>{@code industry} 行业推词：必填 {@code taxonomyId}（由 spotlight_industry_taxonomy 获取）</li>
 *   <li>{@code note} 笔记推词：必填 {@code itemIds} + {@code promotionTarget}</li>
 * </ul>
 */
@Component
public class SpotlightKeywordCommonRecommendTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightKeywordCommonRecommendTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .enumProp("requestType",
                        "推词模式：search=以词推词 / session=上下游推词 / industry=行业推词 / note=笔记推词",
                        List.of("search", "session", "industry", "note"), true)
                .stringProp("keyword",
                        "种子词。search / session 模式必填。", false)
                .stringProp("taxonomyId",
                        "行业ID。industry 模式必填，来自 spotlight_industry_taxonomy 结果。", false)
                .stringProp("attributeList",
                        "行业属性列表，逗号分隔。可选，来自 spotlight_industry_taxonomy_attribute。", false)
                .stringProp("attributeNameList",
                        "行业属性名列表，逗号分隔。可选。", false)
                .arrayProp("itemIds",
                        "笔记ID数组。note 模式必填。",
                        ToolInputSchemas.stringType(), false)
                .arrayProp("recommendReasonFilter",
                        "推荐理由过滤：note→'高点击'，industry→'高点击'，search→'高点击'，session→'上游'/'下游'。",
                        ToolInputSchemas.stringType(), false)
                .integerProp("promotionTarget",
                        "推广目标（note 必填）：1=笔记 / 2=商品 / 7=自由链接 / 9=落地页 / 18=直播间", false)
                .integerProp("rank",
                        "排序：1=pv 降 / 2=pv 升 / 3=竞争指数降 / 4=竞争指数升", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_keyword_common_recommend"; }

    @Override public String description() {
        return "聚光定向推词——根据种子词 / 行业ID / 笔记ID 扩展一批候选关键词，返回 keyword+月pv+竞争指数+市场出价（分）。"
                + "做搜索推广单元选词 / 补齐关键词池最常用的接口。只读。"
                + "需要 org 下有 ACTIVE 的 xhs_spotlight 凭证。";
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
        String requestType = input.path("requestType").asText("").trim();
        if (requestType.isEmpty()) {
            return ToolResult.error("requestType 必填（search / session / industry / note）");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("request_type", requestType);
        putIfText(body, "keyword", input, "keyword");
        putIfText(body, "taxonomy_id", input, "taxonomyId");
        putIfText(body, "attribute_list", input, "attributeList");
        putIfText(body, "attribute_name_list", input, "attributeNameList");
        putArray(body, "item_ids", input, "itemIds");
        putArray(body, "recommend_reason_filter", input, "recommendReasonFilter");
        putIfInt(body, "promotion_target", input, "promotionTarget");
        putIfInt(body, "rank", input, "rank");

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/keyword/common/recommend", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode data = r.data();
        JsonNode wordList = data.path("word_list");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestType", requestType);
        out.put("wordNum", data.path("word_num").asInt(wordList.size()));
        out.put("bagMonthPv", data.path("bag_month_pv").asLong(0));
        out.put("words", wordList);
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format(
                "spotlight_keyword_common_recommend[%s]: %d words (month_pv=%d, %dms)",
                requestType, wordList.size(), data.path("bag_month_pv").asLong(0), r.latencyMs());
        return ToolResult.of(out, summary);
    }

    // ---- helpers ----
    private static void putIfText(ObjectNode body, String k, JsonNode input, String f) {
        if (input.hasNonNull(f)) {
            String v = input.get(f).asText("");
            if (!v.isBlank()) body.put(k, v);
        }
    }
    private static void putIfInt(ObjectNode body, String k, JsonNode input, String f) {
        if (input.hasNonNull(f)) body.put(k, input.get(f).asInt());
    }
    private void putArray(ObjectNode body, String k, JsonNode input, String f) {
        if (!input.hasNonNull(f)) return;
        JsonNode n = input.get(f);
        if (!n.isArray() || n.size() == 0) return;
        ArrayNode arr = body.putArray(k);
        n.forEach(e -> {
            if (e.isTextual()) arr.add(e.asText());
            else arr.add(e.toString());
        });
    }
}
