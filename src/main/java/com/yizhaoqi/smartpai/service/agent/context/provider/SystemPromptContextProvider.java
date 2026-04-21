package com.yizhaoqi.smartpai.service.agent.context.provider;

import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.service.agent.context.ContextContribution;
import com.yizhaoqi.smartpai.service.agent.context.ContextProvider;
import com.yizhaoqi.smartpai.service.agent.context.ContextRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * System prompt 最高优先级；永远保留。不可压缩。
 */
@Component
public class SystemPromptContextProvider implements ContextProvider {

    private final UsageQuotaService usage;

    public SystemPromptContextProvider(UsageQuotaService usage) {
        this.usage = usage;
    }

    @Override public String name() { return "system_prompt"; }
    @Override public int order() { return 0; }

    @Override
    public List<ContextContribution> contribute(ContextRequest req) {
        String sys = req.systemPrompt();
        if (sys == null || sys.isBlank()) return List.of();
        int tokens = usage.estimateTextTokens(sys) + 8;
        Map<String, Object> msg = Map.of("role", "system", "content", sys);
        return List.of(ContextContribution.of("system", 100, tokens, List.of(msg)));
    }
}
