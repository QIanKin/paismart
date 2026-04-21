package com.yizhaoqi.smartpai.service.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 WebSocket session id 管理 Agent 轮次的取消标志。
 * 用户点"停止"时通过 {@link #cancel(String)} 设置位；AgentRuntime 主循环每步检查一次；
 * 各 tool 也可以通过 ToolContext.cancelled 主动让步。
 */
@Component
public class AgentCancellationRegistry {

    private final Map<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    public AtomicBoolean obtainForSession(String sessionId) {
        return flags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
    }

    public void cancel(String sessionId) {
        AtomicBoolean flag = flags.get(sessionId);
        if (flag != null) flag.set(true);
    }

    public void release(String sessionId) {
        flags.remove(sessionId);
    }

    public boolean isCancelled(String sessionId) {
        AtomicBoolean flag = flags.get(sessionId);
        return flag != null && flag.get();
    }
}
