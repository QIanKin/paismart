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
 * xhs_note_comments：拉某条小红书笔记的一级评论 / 子评论。走 TikHub 公开 API。
 *
 * <p>useCase：分析爆款笔记的真实用户反馈、抓取负向评价、抽样评论做语义分析。
 *
 * <p>scope=root（默认）→ 拉一级评论列表，cursor 用于翻页；
 * scope=sub → 需要传 rootCommentId，拉某条评论的楼中楼。
 */
@Component
public class XhsNoteCommentsTool implements Tool {

    private final TikhubXhsService tikhubService;
    private final JsonNode schema;

    public XhsNoteCommentsTool(TikhubXhsService tikhubService) {
        this.tikhubService = tikhubService;
        this.schema = ToolInputSchemas.object()
                .stringProp("noteId", "笔记 noteId（小红书笔记 id）。必填", true)
                .stringProp("xsecToken", "笔记的 xsec_token；从分享链接解析得到，强烈建议传，未传也可以试", false)
                .enumProp("scope", "root=一级评论；sub=子评论（楼中楼）", List.of("root", "sub"), false)
                .stringProp("rootCommentId", "scope=sub 时必填：父评论 commentId", false)
                .stringProp("cursor", "翻页游标。首次空，下次传上次返回的 cursor", false)
                .integerProp("maxRounds", "最多翻几页（每页 ≈10 条）；默认 1，上限 5", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_note_comments"; }

    @Override public String description() {
        return "拉小红书笔记的一级评论或子评论（TikHub 公开 API）。"
                + "scope=root 拉一级评论；scope=sub 需传 rootCommentId 拿子评论。"
                + "支持 cursor 翻页，maxRounds 限制翻几页。";
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
        String noteId = input.path("noteId").asText("").trim();
        if (noteId.isBlank()) return ToolResult.error("bad_input", "noteId 必填");
        String xsecToken = input.path("xsecToken").asText("");
        String scope = input.path("scope").asText("root");
        String rootCommentId = input.path("rootCommentId").asText("").trim();
        String cursor = input.path("cursor").asText("");
        int maxRounds = Math.max(1, Math.min(input.path("maxRounds").asInt(1), 5));

        if ("sub".equalsIgnoreCase(scope) && rootCommentId.isBlank()) {
            return ToolResult.error("bad_input", "scope=sub 时 rootCommentId 必填");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        String nextCursor = cursor;
        int rounds = 0;
        boolean more = true;
        while (more && rounds < maxRounds) {
            TikhubXhsService.CommentsResult res = "sub".equalsIgnoreCase(scope)
                    ? tikhubService.fetchSubComments(noteId, rootCommentId, nextCursor)
                    : tikhubService.fetchNoteComments(noteId, xsecToken, nextCursor);
            if (res.comments != null) {
                for (TikhubXhsService.CommentItem c : res.comments) rows.add(toMap(c));
            }
            more = res.hasMore && res.cursor != null && !res.cursor.isBlank() && !res.cursor.equals(nextCursor);
            nextCursor = res.cursor;
            rounds++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("noteId", noteId);
        out.put("scope", scope);
        out.put("total", rows.size());
        out.put("nextCursor", nextCursor);
        out.put("hasMore", more);
        out.put("items", rows);
        return ToolResult.of(out, String.format("评论 (%s) → %d 条", scope, rows.size()));
    }

    private static Map<String, Object> toMap(TikhubXhsService.CommentItem c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commentId", c.commentId);
        out.put("noteId", c.noteId);
        out.put("content", c.content);
        out.put("likeCount", c.likeCount);
        out.put("subCount", c.subCount);
        out.put("createdAt", c.createdAt);
        out.put("ipLocation", c.ipLocation);
        out.put("userId", c.userId);
        out.put("userName", c.userName);
        out.put("userAvatar", c.userAvatar);
        return out;
    }
}
