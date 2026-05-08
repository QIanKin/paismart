package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.TikhubXhsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xhs_search_notes：按关键词搜索小红书公开笔记。
 *
 * <p>现在默认走 TikHub Web V3，不再依赖 xhs_pc cookie。
 */
@Component
public class XhsSearchNotesTool implements Tool {

    private final TikhubXhsService tikhubService;
    private final JsonNode schema;

    public XhsSearchNotesTool(TikhubXhsService tikhubService) {
        this.tikhubService = tikhubService;
        this.schema = ToolInputSchemas.object()
                .stringProp("keyword", "搜索关键词（必填，例如 '美妆 测评'）", true)
                .integerProp("limit", "返回笔记数，默认 20，上限 100", false)
                .enumProp("sort", "排序：general / time_descending / popularity_descending",
                        List.of("general", "time_descending", "popularity_descending"), false)
                .enumProp("noteType", "类型过滤：0=全部 1=图文 2=视频", List.of("0", "1", "2"), false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_search_notes"; }
    @Override public String description() {
        return "按关键词搜索小红书公开笔记，返回标题/链接/点赞/评论/作者等。"
                + "默认走 TikHub Web V3，不依赖 xhs_pc cookie。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        if (!tikhubService.configured()) {
            return ToolResult.error("provider_not_configured",
                    "TikHub 未配置：请设置 XHS_TIKHUB_ENABLED=true 与 XHS_TIKHUB_API_KEY");
        }
        String keyword = input.path("keyword").asText("");
        if (keyword.isBlank()) return ToolResult.error("bad_input", "keyword 必填");
        int limit = Math.max(1, Math.min(input.path("limit").asInt(20), 100));
        String sort = input.path("sort").asText("general");
        Integer noteType = parseNoteType(input.path("noteType").asText("0"));

        List<Map<String, Object>> rows = new ArrayList<>();
        String searchId = "";
        int page = 1;
        while (rows.size() < limit && page <= 10) {
            TikhubXhsService.SearchNotesResult res = tikhubService.searchNotes(
                    keyword, page, sort, noteType);
            if (page == 1) searchId = res.searchId;
            if (res.notes == null || res.notes.isEmpty()) break;
            for (TikhubXhsService.SearchNote note : res.notes) {
                rows.add(toMap(note));
                if (rows.size() >= limit) break;
            }
            if (!res.hasMore) break;
            page++;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("keyword", keyword);
        payload.put("searchId", searchId);
        payload.put("total", rows.size());
        payload.put("items", rows);
        return ToolResult.of(payload,
                String.format("搜索 '%s' → 拿到 %d 条笔记", keyword, rows.size()));
    }

    private static Integer parseNoteType(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Map<String, Object> toMap(TikhubXhsService.SearchNote note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platformPostId", note.noteId);
        out.put("title", note.title);
        out.put("postType", note.type);
        out.put("coverUrl", note.coverUrl);
        out.put("likes", note.likes);
        out.put("comments", note.comments);
        out.put("shares", note.shares);
        out.put("link", note.link);
        out.put("authorId", note.userId);
        out.put("authorName", note.userName);
        out.put("raw", note.raw);
        return out;
    }
}
