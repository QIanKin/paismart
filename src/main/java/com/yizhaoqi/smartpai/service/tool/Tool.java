package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具抽象：对标 claude-code packages/agent-tools 的 CoreTool 接口。
 * 每个工具声明自己的 name/description/inputSchema，Agent Runtime 会把这些编译成
 * OpenAI `tools` 清单投递给 LLM；LLM 决定调用后，Runtime 通过 {@link #call} 执行并把结果回灌。
 *
 * 设计选择：
 * - 同步 call() 返回 ToolResult，长任务通过 ctx.progressSink 推进度，不强制全链路 Reactor；
 * - schema 用 JSON Schema（Jackson JsonNode），和 OpenAI function calling 协议零转换；
 * - 所有可变行为都通过 default 方法开放默认值，避免实现类过重。
 */
public interface Tool {

    /**
     * 全局唯一的工具名（对应 OpenAI tool_calls 的 function.name）。建议 snake_case。
     */
    String name();

    /**
     * 面向 LLM 的简短说明；写明"何时该用"而不是"怎么实现"。
     * 这段文本直接进 LLM 的 tools manifest，会影响 LLM 决策，措辞要干净。
     */
    String description();

    /**
     * JSON Schema (Draft 2020-12 子集) 描述的参数结构。对象的 properties/required/type 必填。
     * 由 {@link ToolInputSchemas} 辅助构建。
     */
    JsonNode inputSchema();

    /**
     * 真正执行。抛出异常会被 Runtime 捕获并转成一个 tool 错误消息回灌给 LLM，让 LLM 自愈。
     */
    ToolResult call(ToolContext ctx, JsonNode input) throws Exception;

    /**
     * 是否只读（无副作用）。用于调度：只读工具可以并行。
     */
    default boolean isReadOnly(JsonNode input) {
        return true;
    }

    /**
     * 是否破坏性（如 rm、发邮件、扣款）。破坏性工具默认强制二次确认/权限审核。
     */
    default boolean isDestructive(JsonNode input) {
        return false;
    }

    /**
     * 并发安全：多个并发调用是否互不干扰。否则 Runtime 会串行执行同一工具的多次调用。
     */
    default boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 权限检查：在实际 call 之前被调用，返回 Deny 则中断本次工具调用。
     */
    default PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        return PermissionResult.allow();
    }

    /**
     * 是否需要用户二次确认。非空表示"本次调用必须等用户明确放行"，{@link ToolExecutor} 会：
     * <ol>
     *   <li>短路 {@link #call} —— 工具不会真的执行；</li>
     *   <li>回灌一个 {@code confirmation_required} 的 {@link ToolResult}，携带 summary/reason/risks 和
     *       运行时生成的 {@code confirmToken}；</li>
     *   <li>LLM 下一轮重新调用，必须带 {@code _confirm=true} + {@code _confirmToken=<原 token>}。</li>
     * </ol>
     *
     * <p>{@code _confirmToken} 对 (toolName + 规范化 input) 做 SHA-256，防止 LLM 在二次调用时
     * 偷偷改掉参数——改了 token 就对不上，Executor 会再次要求确认。
     *
     * <p>默认返回 null 表示本工具不需要确认。破坏性工具（{@code isDestructive=true}）建议覆盖，
     * 至少声明一句 summary 让终端用户一眼看懂"要干嘛"。
     *
     * <p>方法接收的 input 已经剥离了 _confirm/_confirmToken 等保留字段，实现侧看到的是"业务参数"。
     */
    default ConfirmationRequest requiresConfirmation(ToolContext ctx, JsonNode input) {
        return null;
    }

    /**
     * 是否启用。可通过运行时配置关闭某个工具。ToolRegistry 启动时只收集启用的工具。
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * UI 上展示给用户的"友好名"，默认 == name()。
     */
    default String userFacingName(JsonNode input) {
        return name();
    }

    /**
     * 用户语义的简短摘要（例如 "Search knowledge: 关于xxx"），用于 WS 事件 tool_call 的 summary 字段。
     * 默认不提供，由 Runtime 兜底用工具名。
     */
    default String summarizeInvocation(JsonNode input) {
        return null;
    }
}
