package com.yizhaoqi.smartpai.service.agent.memory;

import com.yizhaoqi.smartpai.model.agent.MemoryItem;
import com.yizhaoqi.smartpai.repository.agent.MemoryItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 长期记忆召回服务。
 *
 * 当前策略（已升级为三层召回）：
 *  1. 显式 pin 的（source=user_note / fact / preference / entity）全量命中本项目/本会话——
 *     这部分按"硬约束"召回，确保企业沉淀过的事实不会被语义召回挤掉；
 *  2. 跨会话 ES KNN 语义召回：当本轮 user query 非空、{@link AgentMemoryEsService} 可用且
 *     索引里有数据时，按"问句的向量"在 agent_memory 索引里 KNN+BM25 混合召回 top N，
 *     确保"上次别的会话讨论过的相关结论"能被复用；
 *  3. 兜底：本 user / project 维度下最新的 compaction 摘要（source=compaction）。
 *
 *  expires_at 已过期的条目会在最后统一过滤。
 *
 * <p>注：{@link AgentMemoryEsService} 是 {@code ConditionalOnBean(ElasticsearchClient.class)}，
 * 测试或没起 ES 时它会缺失，这里用 {@link ObjectProvider} 避免硬依赖。
 */
@Service
public class MemoryRecallService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryRecallService.class);

    private final MemoryItemRepository memoryItemRepository;
    private final ObjectProvider<AgentMemoryEsService> agentMemoryEsServiceProvider;

    public MemoryRecallService(MemoryItemRepository memoryItemRepository,
                               ObjectProvider<AgentMemoryEsService> agentMemoryEsServiceProvider) {
        this.memoryItemRepository = memoryItemRepository;
        this.agentMemoryEsServiceProvider = agentMemoryEsServiceProvider;
    }

    /**
     * @param query        本轮 user query，用于将来向量召回；当前未使用
     * @param userId       当前 user
     * @param projectId    当前 project；可空
     * @param sessionId    当前 session；可空（null 表示新 session，仍需要召回项目级记忆）
     * @param maxItems     最多返回条数
     * @return 按优先级排序的 MemoryItem 列表
     */
    public List<MemoryItem> recall(String query, Long userId, Long projectId, Long sessionId, int maxItems) {
        if (userId == null) return List.of();
        int cap = Math.max(1, maxItems);

        LocalDateTime now = LocalDateTime.now();
        List<MemoryItem> out = new ArrayList<>(cap);
        Set<Long> seen = new HashSet<>();

        // ---- 1) 本会话 + 本项目硬约束 ----
        if (sessionId != null) {
            for (MemoryItem m : memoryItemRepository.findBySessionIdOrderByIdDesc(sessionId)) {
                if (seen.add(m.getId())) out.add(m);
            }
        }
        if (projectId != null) {
            List<MemoryItem> projectNotes = memoryItemRepository
                    .findByProjectIdAndSourceInOrderByIdDesc(projectId,
                            List.of(MemoryItem.Source.fact, MemoryItem.Source.preference, MemoryItem.Source.user_note,
                                    MemoryItem.Source.entity));
            for (MemoryItem m : projectNotes) {
                if (seen.add(m.getId())) out.add(m);
            }
        }

        // ---- 2) ES KNN 跨会话语义召回（按 user 维度 + 可选 project 限定）----
        AgentMemoryEsService esService = agentMemoryEsServiceProvider.getIfAvailable();
        if (esService != null && query != null && !query.isBlank()) {
            int knnTop = Math.max(4, Math.min(8, cap));
            List<Long> knnIds = esService.knnSearch(query, userId, projectId, sessionId, knnTop);
            if (!knnIds.isEmpty()) {
                List<MemoryItem> hits = memoryItemRepository.findAllById(knnIds);
                // 保持 ES 给出的相关性顺序
                java.util.Map<Long, MemoryItem> byId = new java.util.HashMap<>();
                for (MemoryItem m : hits) byId.put(m.getId(), m);
                for (Long id : knnIds) {
                    MemoryItem m = byId.get(id);
                    if (m == null) continue;
                    if (!seen.add(m.getId())) continue;
                    // 仅对当前 user 的条目放行（防御性双检）
                    if (m.getUserId() == null || !m.getUserId().equals(userId)) continue;
                    out.add(m);
                }
            }
        }

        // ---- 3) 兜底：项目/用户维度的 compaction 摘要 ----
        List<MemoryItem> compactions = projectId != null
                ? memoryItemRepository.findByProjectIdAndSourceOrderByIdDesc(
                        projectId, MemoryItem.Source.compaction,
                        PageRequest.of(0, cap, Sort.by(Sort.Direction.DESC, "id")))
                : memoryItemRepository.findByUserIdAndSourceOrderByIdDesc(
                        userId, MemoryItem.Source.compaction,
                        PageRequest.of(0, cap, Sort.by(Sort.Direction.DESC, "id")));
        for (MemoryItem m : compactions) {
            if (seen.add(m.getId())) out.add(m);
        }

        // ---- 过滤过期 / 截断 ----
        List<MemoryItem> filtered = new ArrayList<>();
        for (MemoryItem m : out) {
            if (m.getExpiresAt() != null && m.getExpiresAt().isBefore(now)) continue;
            filtered.add(m);
            if (filtered.size() >= cap) break;
        }
        if (!filtered.isEmpty()) {
            logger.debug("MemoryRecallService 召回 {} 条 userId={} projectId={} sessionId={} esAvail={}",
                    filtered.size(), userId, projectId, sessionId, esService != null);
        }
        return filtered;
    }
}
