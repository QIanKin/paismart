package com.yizhaoqi.smartpai.service.agent.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 预算分配器。策略：
 *  1. 按 priority 降序，从高到低把 contributions 放进"入选集"；
 *  2. 若加入后 token 超预算：
 *     - 若 contribution.compressible 且有 compressedAlt 且压缩版能装下 → 用压缩版；
 *     - 否则跳过（drop）。
 *  3. 最终产出 AllocationResult：保留/压缩/丢弃的分布 + 最终 messages 顺序（按 provider 给出的"自然顺序"）。
 *
 * 关键：分配决策按 priority，但最终消息顺序按 {@code naturalOrder}（system 最前、user 最后、memory/history 中间）。
 * 这两个正交的维度让 provider 可以独立定义：我多重要（priority） vs 我长什么样（顺序）。
 */
public final class TokenBudgetAllocator {

    private static final Logger logger = LoggerFactory.getLogger(TokenBudgetAllocator.class);

    private TokenBudgetAllocator() {}

    public static AllocationResult allocate(List<ContextContribution> contributions, int budget) {
        if (contributions == null || contributions.isEmpty()) {
            return new AllocationResult(List.of(), 0, 0, 0, 0, Map.of());
        }

        // 按 priority 降序。natural 顺序的保留通过 index 记录。
        List<Indexed> indexed = new ArrayList<>();
        for (int i = 0; i < contributions.size(); i++) indexed.add(new Indexed(i, contributions.get(i)));
        indexed.sort(Comparator.<Indexed>comparingInt(ix -> ix.c.priority()).reversed());

        int used = 0;
        int kept = 0;
        int compressed = 0;
        int dropped = 0;
        Map<String, String> decisions = new LinkedHashMap<>();
        List<KeptEntry> keptEntries = new ArrayList<>();

        for (Indexed ix : indexed) {
            ContextContribution c = ix.c;
            int cost = c.tokenEstimate();
            if (used + cost <= budget) {
                used += cost;
                kept++;
                keptEntries.add(new KeptEntry(ix.order, c, c.messages(), cost, "kept"));
                decisions.put(c.layer() + "#" + ix.order, "kept(" + cost + ")");
                continue;
            }
            if (c.compressible() && c.compressedAlt() != null && !c.compressedAlt().isEmpty()
                    && used + c.compressedAltTokens() <= budget) {
                used += c.compressedAltTokens();
                compressed++;
                keptEntries.add(new KeptEntry(ix.order, c, c.compressedAlt(), c.compressedAltTokens(), "compressed"));
                decisions.put(c.layer() + "#" + ix.order,
                        "compressed(" + cost + "→" + c.compressedAltTokens() + ")");
                continue;
            }
            dropped++;
            decisions.put(c.layer() + "#" + ix.order, "dropped(" + cost + ")");
            logger.debug("TokenBudgetAllocator drop layer={} priority={} cost={} usedBefore={}",
                    c.layer(), c.priority(), cost, used);
        }

        // 按 natural order 输出
        keptEntries.sort(Comparator.comparingInt(e -> e.order));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (KeptEntry e : keptEntries) messages.addAll(e.messages);
        return new AllocationResult(messages, used, kept, compressed, dropped, decisions);
    }

    public record AllocationResult(
            List<Map<String, Object>> messages,
            int usedTokens,
            int kept,
            int compressed,
            int dropped,
            Map<String, String> decisions
    ) {}

    private record Indexed(int order, ContextContribution c) {}
    private record KeptEntry(int order, ContextContribution src, List<Map<String, Object>> messages,
                             int tokens, String decision) {}
}
