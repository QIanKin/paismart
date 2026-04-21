package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolRegistry;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSearchToolTest {

    private static class Fake implements Tool {
        private final String n; private final String d;
        Fake(String n, String d) { this.n = n; this.d = d; }
        @Override public String name() { return n; }
        @Override public String description() { return d; }
        @Override public JsonNode inputSchema() { return ToolInputSchemas.object().build(); }
        @Override public ToolResult call(ToolContext c, JsonNode i) { return ToolResult.text("ok"); }
    }

    private ToolSearchTool buildTool() {
        ToolRegistry reg = new ToolRegistry(List.of(
                new Fake("fs_read", "读取沙箱里的文本文件，带行号"),
                new Fake("fs_grep", "在沙箱目录里按正则搜索文本内容"),
                new Fake("bash", "执行 shell 命令"),
                new Fake("knowledge_search", "在企业知识库 RAG 检索")
        ));
        return new ToolSearchTool(reg);
    }

    private ToolContext ctx() {
        return ToolContext.builder().userId("u").orgTag("demo").build();
    }

    @Test
    void selectSyntaxHitsExact() throws Exception {
        ObjectNode in = new ObjectMapper().createObjectNode();
        in.put("query", "select:fs_read,bash");
        ToolResult r = buildTool().call(ctx(), in);
        assertTrue(r.data() instanceof Map);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) ((Map<String, Object>) r.data()).get("matches");
        assertEquals(2, matches.size());
        assertEquals("fs_read", matches.get(0).get("name"));
        assertEquals("bash", matches.get(1).get("name"));
    }

    @Test
    void keywordSearchPrefersNameOverDescription() throws Exception {
        ObjectNode in = new ObjectMapper().createObjectNode();
        in.put("query", "grep");
        ToolResult r = buildTool().call(ctx(), in);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) ((Map<String, Object>) r.data()).get("matches");
        assertTrue(matches.size() >= 1);
        assertEquals("fs_grep", matches.get(0).get("name"));
    }

    @Test
    void chineseKeywordFallsBackToDescription() throws Exception {
        ObjectNode in = new ObjectMapper().createObjectNode();
        in.put("query", "知识库");
        ToolResult r = buildTool().call(ctx(), in);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) ((Map<String, Object>) r.data()).get("matches");
        assertEquals("knowledge_search", matches.get(0).get("name"));
    }

    @Test
    void emptyQueryIsError() throws Exception {
        ObjectNode in = new ObjectMapper().createObjectNode();
        in.put("query", "   ");
        ToolResult r = buildTool().call(ctx(), in);
        assertTrue(r.isError());
    }

    @Test
    void noMatchReturnsEmptyList() throws Exception {
        ObjectNode in = new ObjectMapper().createObjectNode();
        in.put("query", "zzzzz-nomatch");
        ToolResult r = buildTool().call(ctx(), in);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(4, data.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) data.get("matches");
        assertTrue(matches.isEmpty());
    }
}
