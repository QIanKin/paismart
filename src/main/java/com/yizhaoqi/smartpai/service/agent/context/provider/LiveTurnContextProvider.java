package com.yizhaoqi.smartpai.service.agent.context.provider;

import com.yizhaoqi.smartpai.service.agent.TokenCounter;
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

    private final TokenCounter tokenCounter;

    public LiveTurnContextProvider(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    @Override public String name() { return "live_turn"; }
    @Override public int order() { return 100; }

    @Override
    public List<ContextContribution> contribute(ContextRequest req) {
        List<Map<String, Object>> live = req.liveTurnMessages();
        if (live == null || live.isEmpty()) return List.of();
        // 用 TokenCounter.countChatMessages 一次性把 role/content/tool_calls/+4-per-msg overhead 都算进来，
        // 避免 contribution 估算口径低于"模型实际看到"。
        int tokens = tokenCounter.countChatMessages(live);
        return List.of(ContextContribution.of("live_turn", 99, tokens, new ArrayList<>(live)));
    }
}
