package com.yizhaoqi.smartpai.service.tool;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 工具调用上下文。由 AgentRuntime 在触发 tool_call 时构造。
 *
 * 通过 record + Builder 保证不可变 + 按需扩展。
 * - {@code cancelled} 供工具长时间运行时主动判断是否被用户点停；
 * - {@code progressSink} 让工具推 ToolProgress 事件给 Runtime，Runtime 再转成 WS tool_progress 事件；
 * - {@code attributes} 预留给内部实现之间互传（如 skill scripts 目录注入）。
 */
public record ToolContext(
        String userId,
        String orgTag,
        String role,                       // admin / user（来自 JWT）
        String projectId,
        String sessionId,                  // 会话 id（DB 主键，非 WebSocket session）
        String messageId,                  // 触发本次 tool call 的 assistant 消息 id
        String toolUseId,                  // OpenAI tool_call.id，用来和 LLM 响应对齐
        AtomicBoolean cancelled,
        Consumer<ToolProgress> progressSink,
        Map<String, Object> attributes
) {

    public void emitProgress(String type, String message, Object data) {
        if (progressSink != null) {
            progressSink.accept(new ToolProgress(toolUseId, type, message, data));
        }
    }

    public boolean isCancelled() {
        return cancelled != null && cancelled.get();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String userId;
        private String orgTag;
        private String role = "user";
        private String projectId;
        private String sessionId;
        private String messageId;
        private String toolUseId;
        private AtomicBoolean cancelled = new AtomicBoolean(false);
        private Consumer<ToolProgress> progressSink = e -> {};
        private Map<String, Object> attributes = Map.of();

        public Builder userId(String v) { this.userId = v; return this; }
        public Builder orgTag(String v) { this.orgTag = v; return this; }
        public Builder role(String v) { this.role = v; return this; }
        public Builder projectId(String v) { this.projectId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder messageId(String v) { this.messageId = v; return this; }
        public Builder toolUseId(String v) { this.toolUseId = v; return this; }
        public Builder cancelled(AtomicBoolean v) { this.cancelled = v; return this; }
        public Builder progressSink(Consumer<ToolProgress> v) { this.progressSink = v; return this; }
        public Builder attributes(Map<String, Object> v) { this.attributes = v; return this; }

        public ToolContext build() {
            return new ToolContext(userId, orgTag, role, projectId, sessionId,
                    messageId, toolUseId, cancelled, progressSink, attributes);
        }
    }
}
