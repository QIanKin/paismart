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
 * {@code spotlight_word_bag_list}：查询可用的词包（自建 + 平台）。
 *
 * <p>底层：{@code POST /api/open/jg/keyword/word/bag/list}。
 * 创建搜索推广单元时除了推词，还能直接套用已有词包，这个接口就是列出它们。只读。
 */
@Component
public class SpotlightWordBagListTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightWordBagListTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .stringProp("name", "搜索词包名称（模糊匹配，可选）", false)
                .stringProp("category",
                        "类目：'通用' 表示通用型词包；其他传 spotlight_industry_taxonomy 返回的一级 taxonomy_id。可选。",
                        false)
                .stringProp("startTime", "开始时间，格式 2024-02-01（可选）", false)
                .stringProp("endTime", "结束时间，格式 2024-02-01（可选）", false)
                .integerProp("page", "页码（1-based），默认 1", false)
                .integerProp("pageSize", "每页条数，默认 20，上限 100", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_word_bag_list"; }

    @Override public String description() {
        return "查询聚光可用词包（自建词包 + 平台推荐词包），返回 name / create_time / source / word_list（词详情）。"
                + "可按 category（通用 / 行业）、时间范围、名称模糊搜索。只读。";
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
        putIfText(body, "name", input, "name");
        putIfText(body, "category", input, "category");
        putIfText(body, "start_time", input, "startTime");
        putIfText(body, "end_time", input, "endTime");
        // Go 侧 struct 注释把 page_num / page_size 写反了，按 MAPI 惯例：
        // page_num = 页码(1-based)，page_size = 每页条数
        body.put("page_num", page);
        body.put("page_size", pageSize);

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/keyword/word/bag/list", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode data = r.data();
        JsonNode bags = data.path("word_tag_dto_list");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page);
        out.put("pageSize", pageSize);
        out.put("pageInfo", data.path("page"));
        out.put("wordBags", bags);
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_word_bag_list: %d bags (page=%d, %dms)",
                bags.size(), page, r.latencyMs());
        return ToolResult.of(out, summary);
    }

    private static void putIfText(ObjectNode body, String k, JsonNode input, String f) {
        if (input.hasNonNull(f)) {
            String v = input.get(f).asText("");
            if (!v.isBlank()) body.put(k, v);
        }
    }
}
