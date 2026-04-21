package com.yizhaoqi.smartpai.service.agent.context;

import java.util.List;
import java.util.Map;

/**
 * Context 层组装的输入。由 AgentRuntime 在每个 step 开始前构造。
 * 同一 turn 内多次 step（多轮 tool_call）共享同一个 request，但 pendingLiveMessages 每次变更
 * （包含最新 assistant / tool_result），由 Runtime 追加。
 */
public record ContextRequest(
        Long sessionId,
        Long projectId,
        Long userId,
        String orgTag,
        String userMessageRaw,

        /** Runtime 本 step 之前已经写入 DB / 将要写入 DB 的 "本轮产生" 的消息（含 user）。
         *  长期 history 走 L1/L2 读取；本字段只包含本 turn 内的最新动态。 */
        List<Map<String, Object>> liveTurnMessages,

        /** 总 token 预算（prompt 侧）。由 runtime 按 model context window - maxCompletionTokens 计算 */
        int totalBudgetTokens,

        /** 基础 system prompt（Runtime 已经叠加了 project / behaviorContract） */
        String systemPrompt,

        /** 本次启用的工具 name 列表（进 system prompt 的工具提示行用） */
        List<String> enabledToolNames,

        /** 是否在召回阶段允许调用向量库（节流开关） */
        boolean allowLongTermRecall
) {
}
