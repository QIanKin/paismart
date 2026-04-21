package com.yizhaoqi.smartpai.service;

/**
 * Agent 模式下 LLM 流式响应的回调。对标 OpenAI function calling 流式协议：
 * - delta.content → onContent
 * - delta.tool_calls[i] → onToolCallDelta（可多次；args 字符串是增量拼接）
 * - finish_reason → onFinishReason（"stop" / "tool_calls" / "length" / ...）
 * - usage → onUsage
 *
 * 实现类必须是幂等的（onComplete 可能因超时/取消被重入，需自行去重）。
 */
public interface LlmStreamCallback {

    /**
     * 文本内容增量。
     */
    void onContent(String delta);

    /**
     * tool_calls 增量。OpenAI 流式 tool_calls 的结构：
     *   choices[0].delta.tool_calls[i] = { index, id?, type?, function: { name?, arguments? } }
     * 其中 id 和 name 在第一次出现时带值，后续只带 arguments 增量（字符串，可能是 JSON 片段）。
     *
     * @param index    tool_calls 数组内的索引
     * @param id       tool_call.id（仅首包），后续为 null
     * @param name     function.name（仅首包），后续为 null
     * @param argumentsDelta function.arguments 的字符串增量（可能为空串）
     */
    default void onToolCallDelta(int index, String id, String name, String argumentsDelta) {}

    /**
     * 本轮结束原因：stop / tool_calls / length / content_filter / ...
     */
    default void onFinishReason(String reason) {}

    /**
     * usage 信息；有些供应商只在最后一个 chunk 带 usage，有些不带。
     */
    default void onUsage(int promptTokens, int completionTokens) {}

    /**
     * 流正常结束。
     */
    void onComplete();

    /**
     * 流异常结束。
     */
    void onError(Throwable error);
}
