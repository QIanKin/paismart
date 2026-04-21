package com.yizhaoqi.smartpai.service.agent.context.provider;

import com.yizhaoqi.smartpai.model.agent.MemoryItem;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.service.agent.context.ContextContribution;
import com.yizhaoqi.smartpai.service.agent.context.ContextProvider;
import com.yizhaoqi.smartpai.service.agent.context.ContextRequest;
import com.yizhaoqi.smartpai.service.agent.memory.MemoryRecallService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆召回：项目级 fact/user_note/preference + compaction 摘要，合入 system 前的一条 system 消息。
 * 若 allowLongTermRecall=false（例如 runtime 选择省流模式），直接不贡献。
 *
 * 压缩版：只保留 title 的 bullet 列表；省 token ≥ 70%。
 */
@Component
public class LongTermMemoryContextProvider implements ContextProvider {

    private static final int MAX_ITEMS = 12;

    private final MemoryRecallService recall;
    private final UsageQuotaService usage;

    public LongTermMemoryContextProvider(MemoryRecallService recall, UsageQuotaService usage) {
        this.recall = recall;
        this.usage = usage;
    }

    @Override public String name() { return "long_term_memory"; }
    @Override public int order() { return 20; }

    @Override
    public List<ContextContribution> contribute(ContextRequest req) {
        if (!req.allowLongTermRecall()) return List.of();
        if (req.userId() == null) return List.of();

        List<MemoryItem> items = recall.recall(req.userMessageRaw(), req.userId(),
                req.projectId(), req.sessionId(), MAX_ITEMS);
        if (items.isEmpty()) return List.of();

        StringBuilder full = new StringBuilder("# 已知背景与长期记忆\n");
        StringBuilder compact = new StringBuilder("# 已知背景（仅标题）\n");
        for (MemoryItem it : items) {
            String tag = "[" + it.getSource().name() + "]";
            full.append(tag).append(' ');
            if (it.getTitle() != null) full.append("**").append(it.getTitle()).append("** ");
            full.append("\n");
            if (it.getFullText() != null) full.append(it.getFullText().trim()).append("\n\n");

            compact.append("- ").append(tag).append(' ');
            compact.append(it.getTitle() == null ? "(untitled)" : it.getTitle());
            compact.append('\n');
        }

        int fullTokens = usage.estimateTextTokens(full.toString()) + 16;
        int compTokens = usage.estimateTextTokens(compact.toString()) + 16;

        List<Map<String, Object>> fullMsgs = new ArrayList<>(1);
        fullMsgs.add(Map.of("role", "system", "content", full.toString()));
        List<Map<String, Object>> compMsgs = new ArrayList<>(1);
        compMsgs.add(Map.of("role", "system", "content", compact.toString()));

        return List.of(ContextContribution.compressible(
                "memory", 85, fullTokens, fullMsgs, compMsgs, compTokens,
                "memory(" + items.size() + ")"));
    }
}
