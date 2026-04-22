package com.yizhaoqi.smartpai.service.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 工具调用上下文。由 AgentRuntime 在触发 tool_call 时构造。
 *
 * 通过 record + Builder 保证不可变 + 按需扩展。
 * - {@code cancelled} 供工具长时间运行时主动判断是否被用户点停；
 * - {@code progressSink} 让工具推 ToolProgress 事件给 Runtime，Runtime 再转成 WS tool_progress 事件；
 * - {@code askUserSink} 把"向用户结构化提问"走 WS ask_user 事件通道（非 tool_progress，前端专门渲染按钮气泡）；
 * - {@code todoSink} 把 TodoWriteTool 的列表走 WS todo 事件通道，前端实时渲染活体清单；
 * - {@code attributes} 预留给内部实现之间互传（如 skill scripts 目录注入）。
 *
 * <h3>为什么要把 askUser / todo 拆成独立 sink？</h3>
 * 最早只有 {@code progressSink}，然后 {@link com.yizhaoqi.smartpai.service.tool.builtin.AskUserQuestionTool}
 * 和 {@link com.yizhaoqi.smartpai.service.tool.builtin.TodoWriteTool} 都偷借这个通道（type="data",
 * message="ask_user"/"todo_updated"）。后端 {@link com.yizhaoqi.smartpai.service.agent.AgentEventPublisher}
 * 本身有 {@code publishAskUser} / {@code publishTodo}，但没人真调——于是走的全是 {@code tool_progress}
 * 事件，前端 switch 又正好 default 丢掉 → 两个工具哑火。
 * 这里把它们提升为 ToolContext 上的一等方法，Runtime 分别接到专用 publisher。
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
        BiConsumer<String, List<String>> askUserSink,
        Consumer<Object> todoSink,
        Map<String, Object> attributes
) {

    /** 推一条工具执行过程信息（长任务进度、阶段切换），对应 WS tool_progress 事件。 */
    public void emitProgress(String type, String message, Object data) {
        if (progressSink != null) {
            progressSink.accept(new ToolProgress(toolUseId, type, message, data));
        }
    }

    /**
     * 向用户发起结构化反问，对应 WS ask_user 事件。前端看到后会渲染问题气泡，
     * 若 options 非空则以按钮呈现。不要用 emitProgress 走这个语义。
     */
    public void askUser(String question, List<String> options) {
        if (askUserSink != null) {
            askUserSink.accept(question, options == null ? List.of() : options);
        }
    }

    /** 实时更新本会话 TODO 清单，对应 WS todo 事件。 */
    public void updateTodos(Object todos) {
        if (todoSink != null) {
            todoSink.accept(todos);
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
        private BiConsumer<String, List<String>> askUserSink = (q, opts) -> {};
        private Consumer<Object> todoSink = t -> {};
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
        public Builder askUserSink(BiConsumer<String, List<String>> v) { this.askUserSink = v; return this; }
        public Builder todoSink(Consumer<Object> v) { this.todoSink = v; return this; }
        public Builder attributes(Map<String, Object> v) { this.attributes = v; return this; }

        public ToolContext build() {
            return new ToolContext(userId, orgTag, role, projectId, sessionId,
                    messageId, toolUseId, cancelled,
                    progressSink, askUserSink, todoSink, attributes);
        }
    }
}
