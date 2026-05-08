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
 * xhs_user_notes：按 userId 拉某博主公开笔记列表（最近/历史，分页）。
 *
 * <p>这是 TikHub 链路里"已知 userId → 拿笔记"的入口。Agent 工作流：
 * <pre>
 *   - 直接收到 userId 或主页链接 → 直接用
 *   - 没有 userId → 先 xhs_search_users 拿到 userId → 再调本工具
 * </pre>
 */
@Component
public class XhsUserNotesTool implements Tool {

    private final TikhubXhsService tikhubService;
    private final JsonNode schema;

    public XhsUserNotesTool(TikhubXhsService tikhubService) {
        this.tikhubService = tikhubService;
        this.schema = ToolInputSchemas.object()
                .stringProp("userId", "小红书 userId（16+ 位 hex）。必填", true)
                .integerProp("limit", "最多拉几条，默认 30，上限 200", false)
                .stringProp("cursor", "分页游标；首次为空，下次传上次返回的 cursor", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_user_notes"; }

    @Override public String description() {
        return "拉某博主公开笔记列表。需要 userId（16+ 位 hex）；如果只有昵称，先调 xhs_search_users。"
                + "返回每条笔记的标题/封面/互动数/链接和 cursor，便于翻页。走 TikHub 公开 API。";
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
        String userId = input.path("userId").asText("").trim();
        if (userId.isBlank()) return ToolResult.error("bad_input", "userId 必填");
        int limit = Math.max(1, Math.min(input.path("limit").asInt(30), 200));
        String cursor = input.path("cursor").asText("");

        List<Map<String, Object>> rows = new ArrayList<>();
        String nextCursor = cursor;
        int loops = 0;
        while (rows.size() < limit && loops < 10) {
            TikhubXhsService.UserNotesResult page = tikhubService.fetchUserNotes(
                    userId, nextCursor, Math.min(30, limit - rows.size()));
            if (page.notes == null || page.notes.isEmpty()) break;
            for (TikhubXhsService.UserNote n : page.notes) {
                rows.add(toMap(n));
                if (rows.size() >= limit) break;
            }
            if (!page.hasMore || page.cursor == null || page.cursor.isBlank()
                    || page.cursor.equals(nextCursor)) break;
            nextCursor = page.cursor;
            loops++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("total", rows.size());
        out.put("nextCursor", nextCursor);
        out.put("items", rows);
        return ToolResult.of(out, String.format("user %s → 拿到 %d 条笔记", userId, rows.size()));
    }

    private static Map<String, Object> toMap(TikhubXhsService.UserNote n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("noteId", n.noteId);
        out.put("xsecToken", n.xsecToken);
        out.put("type", n.type);
        out.put("title", n.title);
        out.put("coverUrl", n.coverUrl);
        out.put("likes", n.likes);
        out.put("comments", n.comments);
        out.put("shares", n.shares);
        out.put("collects", n.collects);
        out.put("authorId", n.authorId);
        out.put("authorName", n.authorName);
        out.put("link", n.link);
        return out;
    }
}
