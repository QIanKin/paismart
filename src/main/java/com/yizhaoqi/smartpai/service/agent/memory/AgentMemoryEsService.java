package com.yizhaoqi.smartpai.service.agent.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.model.agent.MemoryItem;
import com.yizhaoqi.smartpai.repository.agent.MemoryItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 长期记忆 ES 服务。
 *
 * <p>之前的 {@link MemoryRecallService} 只能"按 session/project 拉全部 + compaction 拉 N 条"，
 * 跨会话相似主题召不回来。本类是补全：
 * <ul>
 *   <li>{@link #indexAsync(MemoryItem)}：在 MemoryItem 写库后，异步把 fullText 嵌入向量，索引到
 *       ES 的 {@code agent_memory}；写完会回填 {@link MemoryItem#setEsDocId(String)}；</li>
 *   <li>{@link #knnSearch(String, Long, Long, Long, int)}：用当前问句做 KNN + BM25 混合召回，
 *       同时用 userId/projectId/orgTag 过滤，返回 ES 视角下相关度最高的 memoryId 列表。</li>
 * </ul>
 *
 * <p>设计原则：
 * <ul>
 *   <li>幂等：以 MemoryItem.id 作为 ES doc id（{@code mem-{id}}），重复 indexAsync 直接覆盖；</li>
 *   <li>容错：嵌入或 ES 任一失败都不会影响主流程（compaction / runOneTurn），只记录日志；</li>
 *   <li>可观测：每次 KNN 输出 hit 数量与最高分数，便于排查"召回为空"问题。</li>
 * </ul>
 */
@Service
@ConditionalOnBean(ElasticsearchClient.class)
public class AgentMemoryEsService {

    private static final Logger logger = LoggerFactory.getLogger(AgentMemoryEsService.class);
    public static final String INDEX = "agent_memory";

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final MemoryItemRepository memoryItemRepository;
    private final ThreadPoolTaskExecutor backgroundExecutor;

    public AgentMemoryEsService(ElasticsearchClient esClient,
                                EmbeddingClient embeddingClient,
                                MemoryItemRepository memoryItemRepository,
                                @Qualifier("chatMonitorExecutor") ThreadPoolTaskExecutor backgroundExecutor) {
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
        this.memoryItemRepository = memoryItemRepository;
        this.backgroundExecutor = backgroundExecutor;
    }

    /** 异步嵌入 + 索引；调用方拿到结果不需要等。 */
    public void indexAsync(MemoryItem item) {
        if (item == null || item.getId() == null) return;
        backgroundExecutor.execute(() -> {
            try {
                indexBlocking(item);
            } catch (Exception e) {
                logger.warn("agent_memory 索引失败 memoryId={} err={}",
                        item.getId(), e.getMessage(), e);
            }
        });
    }

    /** 同步索引；测试 / MemoryCompactor 内联调用方使用。 */
    public String indexBlocking(MemoryItem item) {
        if (item == null || item.getId() == null) return null;
        String text = (item.getTitle() == null ? "" : item.getTitle() + "\n")
                + (item.getFullText() == null ? "" : item.getFullText());
        if (text.isBlank()) {
            logger.debug("agent_memory 跳过空文本 memoryId={}", item.getId());
            return null;
        }
        // 走 EmbeddingClient（统一走当前激活的 embedding provider）
        String requesterId = item.getUserId() == null ? "system" : String.valueOf(item.getUserId());
        List<float[]> vectors = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.UPLOAD);
        if (vectors.isEmpty()) {
            logger.warn("EmbeddingClient 返回空向量 memoryId={}", item.getId());
            return null;
        }
        float[] vec = vectors.get(0);

        Map<String, Object> doc = new HashMap<>();
        doc.put("memoryId", item.getId());
        doc.put("userId", item.getUserId() == null ? null : String.valueOf(item.getUserId()));
        doc.put("orgTag", item.getOrgTag());
        doc.put("sessionId", item.getSessionId());
        doc.put("projectId", item.getProjectId());
        doc.put("source", item.getSource() == null ? null : item.getSource().name());
        doc.put("title", item.getTitle());
        doc.put("fullText", item.getFullText());
        doc.put("fromSeq", item.getFromSeq());
        doc.put("toSeq", item.getToSeq());
        doc.put("tokenEstimate", item.getTokenEstimate());
        doc.put("createdAt", item.getCreatedAt());
        doc.put("expiresAt", item.getExpiresAt());
        doc.put("vector", toBoxedFloatArray(vec));
        doc.put("modelVersion", embeddingClient.currentModelVersion());

        String docId = "mem-" + item.getId();
        IndexRequest<Map<String, Object>> req = IndexRequest.of(b -> b
                .index(INDEX)
                .id(docId)
                .document(doc)
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.False));
        try {
            esClient.index(req);
        } catch (Exception e) {
            logger.warn("ES index 失败 memoryId={} err={}", item.getId(), e.getMessage(), e);
            return null;
        }

        if (item.getEsDocId() == null || !docId.equals(item.getEsDocId())) {
            try {
                item.setEsDocId(docId);
                memoryItemRepository.save(item);
            } catch (Exception ignored) {
                /* 即便 docId 没回填也不影响下次召回，因为我们以 memoryId 字段反查 */
            }
        }
        logger.info("agent_memory 索引完成 memoryId={} docId={} dims={} model={}",
                item.getId(), docId, vec.length, doc.get("modelVersion"));
        return docId;
    }

    /**
     * 跨会话语义召回。
     * @param query     当前用户问句；空字符串/空 query 直接返回空，避免 ES KNN 报错
     * @param userId    必填，作为权限边界（只看自己的 memory）
     * @param projectId 可空：非空则进一步限定本项目
     * @param sessionId 可空：用于排除当前会话的 memory（避免重复注入"本会话刚刚压缩出来的内容"）
     * @param topK      返回的 memoryId 数量上限
     */
    public List<Long> knnSearch(String query, Long userId, Long projectId, Long sessionId, int topK) {
        if (query == null || query.isBlank() || userId == null) return List.of();
        int k = Math.max(1, topK);
        try {
            List<Float> qvec = embedQuery(query, userId);
            if (qvec == null) return List.of();

            String userIdStr = String.valueOf(userId);
            int candidates = Math.max(50, k * 10);

            SearchResponse<Map> response = esClient.search(s -> {
                s.index(INDEX);
                s.size(k);
                s.knn(kn -> {
                    kn.field("vector").queryVector(qvec).k(candidates).numCandidates(candidates);
                    kn.filter(f -> f.bool(bf -> {
                        bf.must(mu -> mu.term(t -> t.field("userId").value(userIdStr)));
                        if (projectId != null) {
                            bf.must(mu -> mu.term(t -> t.field("projectId").value(projectId)));
                        }
                        return bf;
                    }));
                    return kn;
                });
                // BM25 rescore：把当前问句作为软约束，避免纯向量召回引入完全无关的语义近邻
                s.rescore(r -> r
                        .windowSize(candidates)
                        .query(rq -> rq
                                .queryWeight(0.3d)
                                .rescoreQueryWeight(1.0d)
                                .query(rqq -> rqq.match(m -> m
                                        .field("fullText")
                                        .query(query)
                                        .operator(Operator.Or)))));
                return s;
            }, Map.class);

            List<Long> ids = new ArrayList<>(k);
            for (Hit<Map> h : response.hits().hits()) {
                Object src = h.source();
                if (src instanceof Map<?, ?> m) {
                    Object mid = m.get("memoryId");
                    if (mid instanceof Number n) {
                        long id = n.longValue();
                        if (sessionId != null) {
                            Object docSession = m.get("sessionId");
                            if (docSession instanceof Number ns && ns.longValue() == sessionId) {
                                continue; // 跳过本 session 的 memory
                            }
                        }
                        // 过滤显式过期
                        if (m.get("expiresAt") instanceof String expiresStr && !expiresStr.isBlank()) {
                            try {
                                if (LocalDateTime.parse(expiresStr).isBefore(LocalDateTime.now())) continue;
                            } catch (Exception ignored) { /* 解析失败就视为未过期 */ }
                        }
                        ids.add(id);
                    }
                }
            }
            logger.debug("agent_memory KNN userId={} projectId={} sessionId={} q='{}' hits={}",
                    userId, projectId, sessionId,
                    query.length() > 80 ? query.substring(0, 80) + "..." : query, ids.size());
            return ids;
        } catch (Exception e) {
            logger.warn("agent_memory KNN 失败 userId={} projectId={} q={} err={}",
                    userId, projectId, query, e.getMessage());
            return List.of();
        }
    }

    private List<Float> embedQuery(String query, Long userId) {
        try {
            List<float[]> embeds = embeddingClient.embed(List.of(query),
                    String.valueOf(userId), EmbeddingClient.UsageType.QUERY);
            if (embeds.isEmpty()) return null;
            float[] vec = embeds.get(0);
            List<Float> boxed = new ArrayList<>(vec.length);
            for (float v : vec) boxed.add(v);
            return boxed;
        } catch (Exception e) {
            logger.warn("agent_memory 查询向量化失败 q='{}' err={}",
                    query.length() > 80 ? query.substring(0, 80) + "..." : query, e.getMessage());
            return null;
        }
    }

    private static List<Float> toBoxedFloatArray(float[] arr) {
        List<Float> out = new ArrayList<>(arr.length);
        for (float v : arr) out.add(v);
        return out;
    }
}
