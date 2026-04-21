package com.yizhaoqi.smartpai.service.agent;

import java.util.Collections;
import java.util.List;

/**
 * 一次 agent 用户轮输入。由 ChatWebSocketHandler/ChatHandler/REST 构造后投喂给 AgentRuntime。
 *
 * 字段规范：
 *  - userId / orgTag / role 通用信息（userId 可以是 JWT claim 里的数值 id 或 username）
 *  - sessionId：传入字符串形式的 {@link com.yizhaoqi.smartpai.model.agent.ChatSession#getId()}；
 *               为空时 Runtime 会通过 {@link ChatSessionService#getOrCreateDefaultSession} 自动 resolve。
 *  - projectId：同理，可为空。
 *  - conversationId：Phase 1 Redis 会话 id；保留向后兼容（L1 未落 DB 的分支仍会用到）。
 *  - enabledTools：项目级工具白名单，最终会与全局白名单再做交集。
 */
public record AgentRequest(
        String userId,
        String orgTag,
        String role,
        String projectId,
        String sessionId,
        String conversationId,
        String userMessage,
        List<String> enabledTools,
        String systemPromptOverride,
        int maxSteps,
        long turnTimeoutMs
) {

    public AgentRequest {
        if (enabledTools == null) enabledTools = List.of();
        else enabledTools = List.copyOf(enabledTools);
        if (maxSteps <= 0) maxSteps = 12;
        if (turnTimeoutMs <= 0) turnTimeoutMs = 600_000L;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String userId;
        private String orgTag;
        private String role = "user";
        private String projectId;
        private String sessionId;
        private String conversationId;
        private String userMessage;
        private List<String> enabledTools = Collections.emptyList();
        private String systemPromptOverride;
        private int maxSteps = 12;
        private long turnTimeoutMs = 600_000L;

        public Builder userId(String v) { this.userId = v; return this; }
        public Builder orgTag(String v) { this.orgTag = v; return this; }
        public Builder role(String v) { this.role = v; return this; }
        public Builder projectId(String v) { this.projectId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder conversationId(String v) { this.conversationId = v; return this; }
        public Builder userMessage(String v) { this.userMessage = v; return this; }
        public Builder enabledTools(List<String> v) { this.enabledTools = v == null ? List.of() : v; return this; }
        public Builder systemPromptOverride(String v) { this.systemPromptOverride = v; return this; }
        public Builder maxSteps(int v) { this.maxSteps = v; return this; }
        public Builder turnTimeoutMs(long v) { this.turnTimeoutMs = v; return this; }

        public AgentRequest build() {
            return new AgentRequest(userId, orgTag, role, projectId, sessionId, conversationId,
                    userMessage, enabledTools, systemPromptOverride, maxSteps, turnTimeoutMs);
        }
    }

    public Long sessionIdNumeric() { return parseLong(sessionId); }
    public Long projectIdNumeric() { return parseLong(projectId); }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
