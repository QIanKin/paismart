package com.yizhaoqi.smartpai.service.agent;

/**
 * 前后端 WebSocket 事件类型的单一事实来源。前端需要同步常量。
 *
 * 事件统一信封：
 * {
 *   "type": "&lt;TYPE&gt;",
 *   "ts": 1700000000000,
 *   "messageId": "uuid",       // 本轮 assistant 消息的 id（同一轮内所有事件共享）
 *   "sessionId": "...",        // WS 会话 id
 *   "data": { ... }            // 事件专有字段
 * }
 */
public final class AgentEventType {

    private AgentEventType() {}

    /** WS 连接建立后的握手回执。 */
    public static final String CONNECTION = "connection";

    /** Agent 响应开始（用于前端开一个 message block）。 */
    public static final String START = "start";

    /** LLM 文本内容增量；data.delta。 */
    public static final String CHUNK = "chunk";

    /** Agent 计划/思考阶段输出（plan mode 或 reasoning summary）；data.text。 */
    public static final String PLAN = "plan";

    /** TodoWriteTool 产出的 todo 列表；data.todos。 */
    public static final String TODO = "todo";

    /** Agent 决定调用一个工具；data.toolUseId/tool/userFacingName/input/summary。 */
    public static final String TOOL_CALL = "tool_call";

    /** 工具执行中推送的过程信息；data.toolUseId/type/message/payload。 */
    public static final String TOOL_PROGRESS = "tool_progress";

    /** 工具完成（成功或失败）；data.toolUseId/isError/summary/durationMs/preview。 */
    public static final String TOOL_RESULT = "tool_result";

    /** Agent 向用户反问；data.question/options。 */
    public static final String ASK_USER = "ask_user";

    /** 新增 agent 一个 step 的起止，便于前端折叠展示。 */
    public static final String STEP_START = "step_start";
    public static final String STEP_END = "step_end";

    /** 本轮 agent 完全完成（停止原因：stop / max_steps / cancelled / error）。 */
    public static final String COMPLETION = "completion";

    /** 出错。 */
    public static final String ERROR = "error";

    /** 速率/额度限制，包含 retryAfterSeconds。 */
    public static final String RATE_LIMIT = "rate_limit";

    /** 已确认的 stop 指令执行。 */
    public static final String STOPPED = "stopped";
}
