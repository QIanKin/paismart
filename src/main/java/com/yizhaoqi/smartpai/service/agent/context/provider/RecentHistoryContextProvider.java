package com.yizhaoqi.smartpai.service.agent.context.provider;

import com.yizhaoqi.smartpai.model.agent.AgentMessage;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.service.agent.context.ContextContribution;
import com.yizhaoqi.smartpai.service.agent.context.ContextProvider;
import com.yizhaoqi.smartpai.service.agent.context.ContextRequest;
import com.yizhaoqi.smartpai.service.agent.memory.MessageStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最近历史（来自 MessageStore，L1/L2 混合）。
 *
 * 压缩策略：
 *  - 原始版：最近 30 条；
 *  - 压缩版（allocator 预算不足时使用）：只保留最后 10 条的原文，前面的只塞一个 "[earlier N messages truncated]" 占位。
 *
 * 和 LiveTurnContextProvider 的区别：这里读持久化消息（除去本 turn 新写入的），
 * 本 turn 新增由 LiveTurnContextProvider 单独供出。
 */
@Component
public class RecentHistoryContextProvider implements ContextProvider {

    private static final int MAX_RECENT = 30;
    private static final int COMPRESSED_TAIL = 10;

    private final MessageStore messageStore;
    private final UsageQuotaService usage;

    public RecentHistoryContextProvider(MessageStore messageStore, UsageQuotaService usage) {
        this.messageStore = messageStore;
        this.usage = usage;
    }

    @Override public String name() { return "recent_history"; }
    @Override public int order() { return 50; }

    @Override
    public List<ContextContribution> contribute(ContextRequest req) {
        if (req.sessionId() == null) return List.of();
        List<AgentMessage> recent = messageStore.readRecentForPrompt(req.sessionId(), MAX_RECENT);
        if (recent.isEmpty()) return List.of();

        List<Map<String, Object>> full = new ArrayList<>(recent.size());
        int fullTokens = 0;
        for (AgentMessage m : recent) {
            full.add(messageStore.toOpenAiMessage(m));
            if (m.getTokenEstimate() != null) fullTokens += m.getTokenEstimate();
            else if (m.getContent() != null) fullTokens += usage.estimateTextTokens(m.getContent());
            fullTokens += 8;
        }

        if (recent.size() <= COMPRESSED_TAIL) {
            return List.of(ContextContribution.of("history", 70, fullTokens, full));
        }

        int startOfTail = recent.size() - COMPRESSED_TAIL;
        List<Map<String, Object>> compressed = new ArrayList<>(COMPRESSED_TAIL + 1);
        compressed.add(Map.of(
                "role", "system",
                "content", "[earlier " + startOfTail + " messages truncated due to token budget; see memory summaries above]"
        ));
        int compTokens = 30;
        for (int i = startOfTail; i < recent.size(); i++) {
            AgentMessage m = recent.get(i);
            compressed.add(messageStore.toOpenAiMessage(m));
            if (m.getTokenEstimate() != null) compTokens += m.getTokenEstimate();
            else if (m.getContent() != null) compTokens += usage.estimateTextTokens(m.getContent());
            compTokens += 8;
        }

        return List.of(ContextContribution.compressible(
                "history", 70, fullTokens, full, compressed, compTokens,
                "history(" + recent.size() + "→" + COMPRESSED_TAIL + ")"));
    }
}
