package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolRegistry;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * tool_search：当已注册的工具数量上升到 15+，Agent 通过名称/关键词找到目标工具。
 * 对标 Open-ClaudeCode ToolSearchTool：
 *  - query="select:fs_grep,fs_read" 直接选取多工具；
 *  - 其他 query 走关键词打分（名称 +10、名称片段 +5、描述命中词 +2）。
 *
 * 返回 List&lt;{name, description, score}&gt;，LLM 据此决定下一步调用哪个 tool。
 */
@Component
public class ToolSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final ToolRegistry registry;
    private final JsonNode schema;

    // @Lazy 打破循环依赖：ToolRegistry 需要所有 Tool bean（包括本类），
    // 本类又反过来需要 ToolRegistry；加 @Lazy 让 Spring 注入代理，实际调用时再解析。
    public ToolSearchTool(@Lazy ToolRegistry registry) {
        this.registry = registry;
        this.schema = ToolInputSchemas.object()
                .stringProp("query", "关键词或 'select:名称1,名称2' 直接选取", true)
                .integerProp("limit", "最大返回数（默认 5，最多 " + MAX_LIMIT + "）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "tool_search"; }
    @Override public String description() {
        return "按关键词或显式 select 语法在所有已注册工具里查找候选工具。"
                + "格式：'select:fs_grep' 精确选取；'读文件' 关键词检索。LLM 常用于超过初始白名单时请求新工具。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String query = input.path("query").asText("").trim();
        if (query.isEmpty()) return ToolResult.error("query 不能为空");
        int limit = input.has("limit") ? input.get("limit").asInt(DEFAULT_LIMIT) : DEFAULT_LIMIT;
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        List<Tool> all = new ArrayList<>(registry.all());
        List<Map<String, Object>> matches = new ArrayList<>();

        if (query.toLowerCase(Locale.ROOT).startsWith("select:")) {
            String tail = query.substring("select:".length());
            for (String raw : tail.split(",")) {
                String wanted = raw.trim();
                if (wanted.isEmpty()) continue;
                registry.find(wanted).ifPresent(t -> matches.add(describe(t, 100)));
            }
            return ToolResult.of(
                    Map.of("query", query, "total", all.size(), "matches", matches),
                    "select → " + matches.size() + " 个工具");
        }

        String q = query.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String t : q.split("\\s+")) if (!t.isBlank()) terms.add(t);

        List<Scored> scored = new ArrayList<>();
        for (Tool t : all) {
            int s = score(t, terms);
            if (s > 0) scored.add(new Scored(t, s));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            Scored sc = scored.get(i);
            matches.add(describe(sc.t, sc.score));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("total", all.size());
        data.put("matches", matches);
        return ToolResult.of(data, "search '" + query + "' → " + matches.size() + " 命中 / 共 " + all.size());
    }

    private int score(Tool t, List<String> terms) {
        String name = t.name().toLowerCase(Locale.ROOT);
        String desc = (t.description() == null ? "" : t.description()).toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        for (String p : name.split("[_\\-]")) parts.add(p);
        int total = 0;
        for (String term : terms) {
            if (name.equals(term)) { total += 20; continue; }
            if (parts.contains(term)) { total += 10; continue; }
            boolean partContains = false;
            for (String p : parts) if (p.contains(term)) { partContains = true; break; }
            if (partContains) total += 5;
            if (desc.contains(term)) total += 2;
        }
        return total;
    }

    private Map<String, Object> describe(Tool t, int score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("description", t.description());
        m.put("score", score);
        return m;
    }

    private record Scored(Tool t, int score) {}
}
