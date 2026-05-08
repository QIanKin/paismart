package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具输出按 token 截断（仅给 LLM 看的版本）。DB 仍保留全量。
 *
 * <p>解决的问题：单个工具一次返回 50KB JSON，本轮 prompt 预算瞬间被打满，
 * 后续每轮 history 重放还会再吃一次同样的代价。
 *
 * <p>策略：超过 {@link AiProperties.Agent#getToolPayloadMaxTokens()} 时按字符切前缀，
 * 尾部追加折叠提示（带 tool_call_id 提示如何拉完整内容）。完整 payload 仍持久化到
 * {@code agent_messages.content}，前端可据 {@code tool_meta.truncated} 弹"展开完整"。
 *
 * <p>同时给 {@link com.yizhaoqi.smartpai.service.agent.AgentRuntime#runOneToolCall} 与
 * {@link com.yizhaoqi.smartpai.service.agent.memory.MessageStore#toOpenAiMessage} 共用，
 * 这样首次 turn 内截断和后续 turn 的 history 重放会得到一致的"截断版"。
 */
@Component
public class ToolPayloadTruncator {

    private static final Logger logger = LoggerFactory.getLogger(ToolPayloadTruncator.class);
    /** 留给折叠提示的 token 余量，避免精算到字节最后还是超 max */
    private static final int TAIL_RESERVE_TOKENS = 200;
    /** 字符细调的最大次数，防止退化成无穷循环 */
    private static final int CALIBRATION_ATTEMPTS = 4;

    private final TokenCounter tokenCounter;
    private final AiProperties aiProperties;

    public ToolPayloadTruncator(TokenCounter tokenCounter, AiProperties aiProperties) {
        this.tokenCounter = tokenCounter;
        this.aiProperties = aiProperties;
    }

    /**
     * 不超过上限直接返回原文；超过则按字符切前缀并追加折叠提示。
     *
     * @param fullPayload 工具产出的完整 payload（resultToLlmPayload）
     * @param toolCallId  关联 tool_call_id，便于折叠提示里引用
     */
    public TruncationOutcome truncateForLlm(String fullPayload, String toolCallId) {
        if (fullPayload == null || fullPayload.isEmpty()) {
            return new TruncationOutcome(fullPayload == null ? "" : fullPayload, 0, 0, false);
        }
        int maxTokens = Math.max(500, aiProperties.getAgent().getToolPayloadMaxTokens());
        int fullTokens = tokenCounter.countText(fullPayload);
        if (fullTokens <= maxTokens) {
            return new TruncationOutcome(fullPayload, fullTokens, fullTokens, false);
        }

        int targetTokens = Math.max(200, maxTokens - TAIL_RESERVE_TOKENS);
        double ratio = (double) targetTokens / Math.max(1, fullTokens);
        int targetChars = Math.max(64, (int) Math.floor(fullPayload.length() * ratio));

        // 字符到 token 的换算并不线性，特别在中英混排时；做最多 N 次细调，确保最终 ≤ targetTokens
        String cut = fullPayload.substring(0, Math.min(targetChars, fullPayload.length()));
        for (int attempt = 0; attempt < CALIBRATION_ATTEMPTS; attempt++) {
            int t = tokenCounter.countText(cut);
            if (t <= targetTokens) break;
            targetChars = Math.max(64, (int) (targetChars * 0.85));
            cut = fullPayload.substring(0, Math.min(targetChars, fullPayload.length()));
        }

        String hint = buildHint(fullTokens, toolCallId);
        String truncated = cut + hint;
        int llmTokens = tokenCounter.countText(truncated);
        if (logger.isDebugEnabled()) {
            logger.debug("Tool payload 截断 toolCallId={} full={}t→llm={}t (limit={}t)",
                    toolCallId, fullTokens, llmTokens, maxTokens);
        }
        return new TruncationOutcome(truncated, fullTokens, llmTokens, true);
    }

    private String buildHint(int fullTokens, String toolCallId) {
        StringBuilder sb = new StringBuilder("\n\n[...输出已折叠：完整约 ").append(fullTokens).append(" tokens");
        if (toolCallId != null && !toolCallId.isBlank()) {
            sb.append("；tool_call_id=").append(toolCallId);
        }
        sb.append("。完整结果已存档于 agent_messages.content，前端可点击展开...]");
        return sb.toString();
    }

    /** 在已有 meta 基础上叠加 truncated/fullTokens/llmTokens/fullStored 字段。 */
    public Map<String, Object> withTruncationMeta(Map<String, Object> baseMeta, TruncationOutcome outcome) {
        if (outcome == null || !outcome.truncated()) {
            return baseMeta;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseMeta != null) merged.putAll(baseMeta);
        merged.put("truncated", true);
        merged.put("fullTokens", outcome.fullTokens());
        merged.put("llmTokens", outcome.llmTokens());
        merged.put("fullStored", true);
        return merged;
    }

    public record TruncationOutcome(String llmPayload, int fullTokens, int llmTokens, boolean truncated) {}
}
