package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsSkillRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xhs_search_notes：按关键词搜索小红书笔记，用于爆款/赛道调研。
 *
 * 只返回，不入库；LLM 拿到 results 后可以自行排序/取样，或串 creator_post_batch_upsert 入库。
 */
@Component
public class XhsSearchNotesTool implements Tool {

    private final XhsSkillRunner runner;
    private final JsonNode schema;

    public XhsSearchNotesTool(XhsSkillRunner runner) {
        this.runner = runner;
        this.schema = ToolInputSchemas.object()
                .stringProp("keyword", "搜索关键词（必填，例如 '美妆 测评'）", true)
                .integerProp("limit", "返回笔记数，默认 20，上限 100", false)
                .enumProp("sort", "排序：general / time_descending / popularity_descending",
                        List.of("general", "time_descending", "popularity_descending"), false)
                .enumProp("noteType", "类型过滤：0=全部 1=视频 2=图文", List.of("0", "1", "2"), false)
                .enumProp("noteTime", "时间窗：0=全部 1=一天 2=一周 3=半年",
                        List.of("0", "1", "2", "3"), false)
                .enumProp("noteRange", "范围：0=全部 1=已看过 2=未看过 3=已关注",
                        List.of("0", "1", "2", "3"), false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_search_notes"; }
    @Override public String description() {
        return "按关键词搜索小红书笔记，返回标题/链接/点赞/评论/发布时间等。用于爆款选题、赛道调研。"
                + "需要后台录入 xhs_pc cookie。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文缺少 orgTag");
        String keyword = input.path("keyword").asText("");
        if (keyword.isBlank()) return ToolResult.error("keyword 必填");
        int limit = Math.max(1, Math.min(input.path("limit").asInt(20), 100));

        XhsSkillRunner.RunRequest req = new XhsSkillRunner.RunRequest();
        req.orgTag = orgTag;
        req.sessionId = ctx.sessionId();
        req.skillName = "xhs-search-notes";
        req.scriptRelative = "scripts/search_notes.py";
        req.cookiePlatform = "xhs_pc";
        req.extraArgs.add("--keyword");
        req.extraArgs.add(keyword);
        req.extraArgs.add("--limit");
        req.extraArgs.add(String.valueOf(limit));
        addOptional(req, input, "sort", "--sort");
        addOptional(req, input, "noteType", "--note-type");
        addOptional(req, input, "noteTime", "--note-time");
        addOptional(req, input, "noteRange", "--note-range");
        req.timeoutSeconds = 180;
        req.cancelled = ctx.cancelled();

        XhsSkillRunner.RunResult res = runner.run(req);
        if (!res.ok()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errorType", res.errorType());
            detail.put("keyword", keyword);
            return ToolResult.error("xhs-search-notes 执行失败: " + res.errorMessage(), detail);
        }
        JsonNode payload = res.payload();
        int total = payload.path("total").asInt(0);
        return ToolResult.of(payload,
                String.format("搜索 '%s' → 拿到 %d 条笔记", keyword, total));
    }

    private static void addOptional(XhsSkillRunner.RunRequest req, JsonNode input,
                                    String field, String flag) {
        if (input.hasNonNull(field)) {
            String v = input.get(field).asText("");
            if (!v.isBlank()) {
                req.extraArgs.add(flag);
                req.extraArgs.add(v);
            }
        }
    }
}
