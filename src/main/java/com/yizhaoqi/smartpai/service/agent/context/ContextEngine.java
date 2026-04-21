package com.yizhaoqi.smartpai.service.agent.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Context 引擎。
 *
 * 流水线：
 *   providers.forEach(contribute) → flat list of Contribution
 *   → TokenBudgetAllocator.allocate(budget)
 *   → AssembledContext { messages, usedTokens, dropped }
 *
 * 和 claude-code 的差异：
 *  - 这里不做运行时 LLM 压缩；每个 provider 自己按需提供 compressedAlt（或不提供，就走硬丢弃）；
 *  - 真正的消息摘要由离线 MemoryCompactor 负责，写入 MemoryItem，下一次 assemble 时从 LongTermMemoryProvider 流入。
 *  - 这样 inline path 始终 O(ms)，避免"组装一次 prompt 先等 LLM 压缩"的尾延迟。
 */
@Component
public class ContextEngine {

    private static final Logger logger = LoggerFactory.getLogger(ContextEngine.class);

    private final List<ContextProvider> providers;

    public ContextEngine(List<ContextProvider> providers) {
        // 按 order 升序，name 去重
        List<ContextProvider> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparingInt(ContextProvider::order));
        List<ContextProvider> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ContextProvider p : sorted) {
            if (seen.add(p.name())) deduped.add(p);
        }
        this.providers = List.copyOf(deduped);
        logger.info("ContextEngine 启动，已注册 providers: {}",
                deduped.stream().map(ContextProvider::name).toList());
    }

    public AssembledContext assemble(ContextRequest req) {
        List<ContextContribution> contributions = new ArrayList<>();
        for (ContextProvider p : providers) {
            try {
                List<ContextContribution> out = p.contribute(req);
                if (out != null) contributions.addAll(out);
            } catch (Exception ex) {
                logger.warn("ContextProvider {} 抛出异常，已跳过: {}", p.name(), ex.getMessage(), ex);
            }
        }

        TokenBudgetAllocator.AllocationResult alloc =
                TokenBudgetAllocator.allocate(contributions, req.totalBudgetTokens());

        if (alloc.dropped() > 0 || alloc.compressed() > 0) {
            logger.info("ContextEngine budget={} used={} kept={} compressed={} dropped={} decisions={}",
                    req.totalBudgetTokens(), alloc.usedTokens(), alloc.kept(),
                    alloc.compressed(), alloc.dropped(), alloc.decisions());
        }

        return new AssembledContext(alloc.messages(), alloc.usedTokens(),
                alloc.kept(), alloc.compressed(), alloc.dropped(), alloc.decisions());
    }

    public record AssembledContext(
            List<Map<String, Object>> messages,
            int usedTokens,
            int keptLayers,
            int compressedLayers,
            int droppedLayers,
            Map<String, String> decisions
    ) {}
}
