package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * creator_post_batch_upsert：批量写入一个账号的内容（视频/笔记）。
 * 去重：platform + platformPostId。
 *
 * 典型用法：skill 把 xhs 某账号 20 条笔记爬下来后，用这个工具一次性入库，Agent 继续下一步。
 */
@Component
public class CreatorPostBatchUpsertTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CreatorService creatorService;
    private final JsonNode schema;

    public CreatorPostBatchUpsertTool(CreatorService creatorService) {
        this.creatorService = creatorService;
        this.schema = ToolInputSchemas.object()
                .integerProp("accountId", "目标账号 id（creator_search/creator_upsert 返回）", true)
                .stringProp("postsJson",
                        "JSON 数组：[{platformPostId, title, contentText, coverUrl, publishedAt(ISO), likes, comments, views, durationSec, isHit, hashtags:[], hitStructureTags:[], link}]",
                        true)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "creator_post_batch_upsert"; }
    @Override public String description() {
        return "批量 upsert 某账号下的内容（视频/笔记），以 platformPostId 去重。skill 抓到数据后入库的主入口。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文无 orgTag");
        long accountId = input.path("accountId").asLong(-1L);
        if (accountId <= 0) return ToolResult.error("accountId 必填");
        String postsJson = input.hasNonNull("postsJson") ? input.get("postsJson").asText(null) : null;
        if (postsJson == null || postsJson.isBlank()) return ToolResult.error("postsJson 必填");

        List<Map<String, Object>> rows;
        try {
            rows = MAPPER.readValue(postsJson, new TypeReference<>() {});
        } catch (Exception e) {
            return ToolResult.error("postsJson 解析失败：" + e.getMessage());
        }
        if (rows == null || rows.isEmpty()) return ToolResult.error("postsJson 为空数组");
        if (rows.size() > 500) return ToolResult.error("单次最多 500 条，当前 " + rows.size());

        try {
            CreatorService.PostBatchResult r = creatorService.upsertPosts(accountId, orgTag, rows);
            return ToolResult.of(
                    Map.of("accountId", accountId, "inserted", r.inserted(),
                            "updated", r.updated(), "skipped", r.skipped()),
                    String.format("account #%d: +%d 新增 / %d 更新 / %d 跳过",
                            accountId, r.inserted(), r.updated(), r.skipped()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
