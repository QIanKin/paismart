package com.yizhaoqi.smartpai.service.agent.context;

import java.util.List;
import java.util.Map;

/**
 * 单个 ContextProvider 贡献的一组消息（可能为 0~N 条）及其元数据。
 * 一个 provider 可以一次性产出多个 Contribution（例如把多条长期记忆拆成多个 contribution，
 * 由 allocator 按 token 粒度裁剪）。
 *
 * 字段释义：
 *  - layer    分层名（system/memory/history/user/...）——便于日志与调试
 *  - priority 0~100，100=必选；allocator 优先保留高 priority 的
 *  - compressible 是否允许被压缩（摘要）；不可压缩的只能整体丢弃或整体保留
 *  - tokenEstimate 估算的 token 数（使用 UsageQuotaService.estimateTextTokens）
 *  - messages 产出的 OpenAI messages；allocator 只基于 token 决策是否丢弃
 *  - compressedAlt 预先准备好的"压缩替代品"——provider 可以同时给原始和压缩版本；
 *                  allocator 预算不足时直接用压缩版，省得再跑 LLM 压缩。
 *  - displayTag 调试用的简短摘要
 */
public record ContextContribution(
        String layer,
        int priority,
        boolean compressible,
        int tokenEstimate,
        List<Map<String, Object>> messages,
        List<Map<String, Object>> compressedAlt,
        int compressedAltTokens,
        String displayTag
) {
    public static ContextContribution of(String layer, int priority, int tokens, List<Map<String, Object>> msgs) {
        return new ContextContribution(layer, priority, false, tokens, msgs, null, 0, layer);
    }
    public static ContextContribution compressible(String layer, int priority, int tokens,
                                                   List<Map<String, Object>> msgs,
                                                   List<Map<String, Object>> compressed,
                                                   int compressedTokens,
                                                   String displayTag) {
        return new ContextContribution(layer, priority, true, tokens, msgs,
                compressed, compressedTokens, displayTag);
    }
}
