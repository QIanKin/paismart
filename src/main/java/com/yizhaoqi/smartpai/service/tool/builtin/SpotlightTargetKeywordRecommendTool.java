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
import java.util.Map;

/**
 * {@code spotlight_target_keyword_recommend}：根据搜索词 / 笔记推荐关键词（带覆盖人数）。
 *
 * <p>底层：{@code POST /api/open/jg/target/keyword/recommend}。
 * 与 {@link SpotlightKeywordCommonRecommendTool} 的区别：这个接口主打"定向层"，
 * 返回 {@code target_word / recommend_reason / cover_num（覆盖人数）}，更偏"定向圈选辅助"。只读。
 */
@Component
public class SpotlightTargetKeywordRecommendTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightTargetKeywordRecommendTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .stringProp("keyword", "种子搜索词（可选，与 noteIds 二选一）", false)
                .arrayProp("noteIds", "笔记 ID 数组（可选，与 keyword 二选一）",
                        ToolInputSchemas.stringType(), false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_target_keyword_recommend"; }

    @Override public String description() {
        return "聚光定向层关键词推荐——根据种子词或笔记ID推出一批 target_word + 覆盖人数 + 推荐理由。"
                + "用于定向配置层快速扩词。只读。";
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
        String keyword = input.path("keyword").asText("").trim();
        JsonNode noteIds = input.path("noteIds");
        boolean hasNotes = noteIds.isArray() && noteIds.size() > 0;
        if (keyword.isEmpty() && !hasNotes) {
            return ToolResult.error("keyword 与 noteIds 至少提供一个");
        }
        ObjectNode body = mapper.createObjectNode();
        if (!keyword.isEmpty()) body.put("keyword", keyword);
        if (hasNotes) {
            ArrayNode arr = body.putArray("note_ids");
            noteIds.forEach(e -> {
                if (e.isTextual()) arr.add(e.asText());
                else arr.add(e.toString());
            });
        }

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/target/keyword/recommend", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        // data 本身就是数组（见 KeywordRecommendResponse.Data []KeywordRecommend）
        JsonNode list = r.data();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seedKeyword", keyword);
        out.put("seedNoteIds", noteIds);
        out.put("count", list.isArray() ? list.size() : 0);
        out.put("keywords", list);
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_target_keyword_recommend: %d suggestions (%dms)",
                list.isArray() ? list.size() : 0, r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
