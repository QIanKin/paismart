package com.yizhaoqi.smartpai.service.agent.memory;

import com.yizhaoqi.smartpai.model.agent.MemoryItem;
import com.yizhaoqi.smartpai.repository.agent.MemoryItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆召回服务。
 *
 * 当前策略（足够上线，后续可叠加 ES 向量召回）：
 *  1. 显式 pin 的（source=user_note / fact / preference / entity）全量命中本项目/本会话。
 *  2. 压缩摘要（source=compaction）按 createdAt DESC 取最近 N 条。
 *  3. expires_at 过期的自动丢弃。
 *
 * 后续升级：引入 ES agent_memory_items 索引做 KNN + bm25 二次重排，
 * {@link #recall(String, Long, Long, Long, int)} 即可无损替换。
 */
@Service
public class MemoryRecallService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryRecallService.class);

    private final MemoryItemRepository memoryItemRepository;

    public MemoryRecallService(MemoryItemRepository memoryItemRepository) {
        this.memoryItemRepository = memoryItemRepository;
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

        // 1) 本会话 + 本项目的 user_note / fact / preference / entity：全量拉，上限优先给它们
        if (sessionId != null) {
            out.addAll(memoryItemRepository.findBySessionIdOrderByIdDesc(sessionId));
        }
        if (projectId != null) {
            List<MemoryItem> projectNotes = memoryItemRepository
                    .findByProjectIdAndSourceInOrderByIdDesc(projectId,
                            List.of(MemoryItem.Source.fact, MemoryItem.Source.preference, MemoryItem.Source.user_note,
                                    MemoryItem.Source.entity));
            for (MemoryItem m : projectNotes) {
                if (out.stream().noneMatch(x -> x.getId().equals(m.getId()))) out.add(m);
            }
        }

        // 2) 本 user + projectId 作用域内的 compaction 摘要（最近 N 条）
        List<MemoryItem> compactions = projectId != null
                ? memoryItemRepository.findByProjectIdAndSourceOrderByIdDesc(
                        projectId, MemoryItem.Source.compaction,
                        PageRequest.of(0, cap, Sort.by(Sort.Direction.DESC, "id")))
                : memoryItemRepository.findByUserIdAndSourceOrderByIdDesc(
                        userId, MemoryItem.Source.compaction,
                        PageRequest.of(0, cap, Sort.by(Sort.Direction.DESC, "id")));
        for (MemoryItem m : compactions) {
            if (out.stream().noneMatch(x -> x.getId().equals(m.getId()))) out.add(m);
        }

        // 3) 过滤过期 / 截断
        List<MemoryItem> filtered = new ArrayList<>();
        for (MemoryItem m : out) {
            if (m.getExpiresAt() != null && m.getExpiresAt().isBefore(now)) continue;
            filtered.add(m);
            if (filtered.size() >= cap) break;
        }
        if (!filtered.isEmpty()) {
            logger.debug("MemoryRecallService 召回 {} 条 userId={} projectId={} sessionId={}",
                    filtered.size(), userId, projectId, sessionId);
        }
        return filtered;
    }
}
