package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业知识库检索工具——复用 PaiSmart 原有的 HybridSearchService（ES BM25 + 向量混合召回），
 * 并把原本硬编码在 ChatHandler 里的"先搜 5 条再拼 context"流程退化成 LLM 可自主调用的工具。
 *
 * 主要设计：
 * - 租户/权限过滤依旧在 HybridSearchService 内部处理（userId 传下去）；
 * - 返回给 LLM 的结构简化为 {docs: [{ref, file, chunk, content, score, source}], total}，
 *   省掉 anchor/pageNumber 等 UI 细节，避免 token 浪费；
 * - summary 里回传命中数和前几个文件名，给 UI tool_result 用。
 */
@Component
public class KnowledgeSearchTool implements Tool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 15;
    private static final int CONTENT_SNIPPET_LIMIT = 600;

    private final HybridSearchService hybridSearchService;
    private final JsonNode schema;

    public KnowledgeSearchTool(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
        this.schema = ToolInputSchemas.object()
                .stringProp("query", "要在企业知识库中检索的自然语言 query", true)
                .integerProp("top_k", "返回的最大结果数，1~15，默认 5", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "knowledge_search"; }

    @Override public String description() {
        return "在企业内部知识库（ES 全文 + 向量混合召回，已带租户权限过滤）中检索和用户问题相关的文档片段。"
                + "当用户问题涉及公司内部资料、规范、历史资料、合同/SOP 等内容时应优先使用本工具。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override public String userFacingName(JsonNode input) { return "搜索知识库"; }

    @Override public String summarizeInvocation(JsonNode input) {
        String q = input == null ? "" : input.path("query").asText("");
        return "knowledge_search: " + q;
    }

    @Override public ToolResult call(ToolContext ctx, JsonNode input) {
        String query = input.path("query").asText("").trim();
        if (query.isEmpty()) return ToolResult.error("query 不能为空");
        int topK = Math.min(MAX_TOP_K, Math.max(1, input.path("top_k").asInt(DEFAULT_TOP_K)));

        List<SearchResult> results = hybridSearchService.searchWithPermission(query, ctx.userId(), topK);

        List<Map<String, Object>> docs = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("ref", i + 1);
            doc.put("file", r.getFileName());
            doc.put("chunk", r.getChunkId());
            doc.put("score", r.getScore());
            String content = r.getMatchedChunkText() != null ? r.getMatchedChunkText() : r.getTextContent();
            if (content != null && content.length() > CONTENT_SNIPPET_LIMIT) {
                content = content.substring(0, CONTENT_SNIPPET_LIMIT) + "…";
            }
            doc.put("content", content);
            if (r.getPageNumber() != null) doc.put("page", r.getPageNumber());
            doc.put("source", r.getOrgTag() == null ? "personal" : r.getOrgTag());
            docs.add(doc);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", docs.size());
        data.put("docs", docs);
        data.put("query", query);

        String summary = docs.isEmpty()
                ? "未命中任何文档"
                : "命中 " + docs.size() + " 条；首条来自：" + docs.get(0).get("file");
        return ToolResult.of(data, summary, Map.of("topK", topK, "hits", docs.size()));
    }
}
