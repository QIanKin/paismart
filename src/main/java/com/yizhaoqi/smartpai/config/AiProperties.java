package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Generation generation = new Generation();
    private Agent agent = new Agent();

    @Data
    public static class Prompt {
        /** 规则文案 */
        private String rules;
        /** 引用开始分隔符 */
        private String refStart;
        /** 引用结束分隔符 */
        private String refEnd;
        /** 无检索结果时的占位文案 */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 最大输出 tokens */
        private Integer maxTokens = 2000;
        /** nucleus top-p */
        private Double topP = 0.9;
    }

    /**
     * Agent 运行时参数。可在 application.yml 下通过 ai.agent.* 覆盖。
     */
    @Data
    public static class Agent {
        /** 单轮最多 LLM+tool 循环次数 */
        private int maxSteps = 12;
        /** 单轮总超时（毫秒） */
        private long turnTimeoutMs = 600_000L;
        /** 默认所有 tool 启用；若为非空，则作为全局白名单（项目级再叠加） */
        private java.util.List<String> enabledTools = java.util.List.of();
        /** 附加到 system prompt 末尾的企业专属行为契约（会覆盖默认 MCN 行为契约中冲突的条目） */
        private String behaviorContract;

        /**
         * 模型 context window 总大小（token）。用于推算 prompt 侧可用预算：
         * {@code prompt 预算 = contextWindowTokens - generation.maxTokens - 1000(reserve)}。
         * 默认 128k 适配 GPT-4o / DeepSeek / GLM-4.5 等主流模型；接 32k 模型时调小。
         */
        private int contextWindowTokens = 128_000;

        /**
         * 轮内 auto-compact 的触发阈值（已用预算占比）。本 turn 内一旦
         * {@code ctx.usedTokens / totalBudget >= 此值}，或 ContextEngine 已经在丢弃层，
         * Runtime 会同步触发 MemoryCompactor 一次再 re-assemble，不再"撑过这轮再说"。
         * 取值过低会带来无谓的同步压缩延迟（每次约 1-3s），过高会逼近模型硬上限。
         */
        private double inTurnCompactUsageRatio = 0.92d;

        /**
         * 单条 tool 消息回灌给 LLM 的 token 上限。超过则截断成"前缀 + 折叠提示"。
         * DB 中仍持久化完整 payload，前端可据 {@code tool_meta.truncated} 弹出"展开完整结果"。
         * 防止 50KB 工具响应一次把上下文打满。
         */
        private int toolPayloadMaxTokens = 4000;
    }
} 