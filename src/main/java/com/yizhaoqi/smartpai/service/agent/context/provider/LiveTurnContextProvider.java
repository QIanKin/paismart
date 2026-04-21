package com.yizhaoqi.smartpai.service.agent.context.provider;

import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.service.agent.context.ContextContribution;
import com.yizhaoqi.smartpai.service.agent.context.ContextProvider;
import com.yizhaoqi.smartpai.service.agent.context.ContextRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本 turn 的"正在进行消息"（新 user + step 中已写入的 assistant/tool）。
 * priority=99，仅次于 system，确保模型看到当前 turn 的完整上下文。不可压缩。
 */
@Component
public class LiveTurnContextProvider implements ContextProvider {

    private final UsageQuotaService usage;

    public LiveTurnContextProvider(UsageQuotaService usage) {
        this.usage = usage;
    }

    @Override public String name() { return "live_turn"; }
    @Override public int order() { return 100; }

    @Override
    public List<ContextContribution> contribute(ContextRequest req) {
        List<Map<String, Object>> live = req.liveTurnMessages();
        if (live == null || live.isEmpty()) return List.of();
        int tokens = 0;
        for (Map<String, Object> m : live) {
            Object c = m.get("content");
            if (c instanceof String s) tokens += usage.estimateTextTokens(s);
            tokens += 8;
        }
        return List.of(ContextContribution.of("live_turn", 99, tokens, new ArrayList<>(live)));
    }
}
