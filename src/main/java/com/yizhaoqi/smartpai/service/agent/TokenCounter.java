package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 精确 token 计数器。
 *
 * <p>之前的实现（{@code UsageQuotaService.estimateTextTokens}）基于字符类别比例做"经验估算"，
 * 在纯中文与"中英混排 + JSON 工具调用"场景下误差通常 ±30%。这套链路上有两类决策依赖
 * token 数：
 * <ul>
 *   <li>{@link com.yizhaoqi.smartpai.service.agent.context.TokenBudgetAllocator} 决定哪些层
 *       要保留、压缩或丢弃；</li>
 *   <li>{@link com.yizhaoqi.smartpai.service.agent.AgentRuntime} 判断 prompt 是否接近模型 context
 *       窗口需要触发同步压缩。</li>
 * </ul>
 *
 * <p>因此把它换成 jtokkit（OpenAI tiktoken 的纯 JVM 实现）：
 * <ul>
 *   <li>默认 cl100k_base（GPT-4 / GPT-3.5-turbo / DeepSeek / SiliconFlow 现行模型都用它），
 *       可以通过 {@code ai.tokenizer.encoding} 配成 {@code o200k_base}（GPT-4o 系列）；</li>
 *   <li>对 OpenAI Chat Messages 做"每条 +4，总体 +2"的官方修正，与
 *       <a href="https://github.com/openai/openai-cookbook/blob/main/examples/How_to_count_tokens_with_tiktoken.ipynb">
 *       openai-cookbook</a> 中的经验值一致；</li>
 *   <li>失败时降级到旧的字符比例估算，绝不让运行时因为 tokenizer 异常而崩。</li>
 * </ul>
 */
@Component
public class TokenCounter {

    private static final Logger logger = LoggerFactory.getLogger(TokenCounter.class);

    /** OpenAI Chat 完整体系：每条消息额外 4 token，整体收尾 +2（含 assistant 占位） */
    private static final int CHAT_PER_MESSAGE_OVERHEAD = 4;
    private static final int CHAT_REPLY_OVERHEAD = 2;

    private final Encoding encoding;
    private final String encodingName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TokenCounter(@Value("${ai.tokenizer.encoding:cl100k_base}") String configured) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        EncodingType picked = pickEncoding(configured);
        this.encoding = registry.getEncoding(picked);
        this.encodingName = picked.getName();
        logger.info("TokenCounter 初始化完成：encoding={}", encodingName);
    }

    private EncodingType pickEncoding(String configured) {
        if (configured == null) return EncodingType.CL100K_BASE;
        return switch (configured.trim().toLowerCase()) {
            case "o200k_base" -> EncodingType.O200K_BASE;
            case "p50k_base" -> EncodingType.P50K_BASE;
            case "p50k_edit" -> EncodingType.P50K_EDIT;
            case "r50k_base" -> EncodingType.R50K_BASE;
            default -> EncodingType.CL100K_BASE;
        };
    }

    /** 统计单段文本 token 数；空字符串返回 0。 */
    public int countText(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            return encoding.countTokens(text);
        } catch (Exception e) {
            logger.warn("jtokkit 计数失败，回退字符比例估算：{}", e.getMessage());
            return fallbackEstimate(text);
        }
    }

    /**
     * 统计 OpenAI 风格 Chat Messages 总 token 数（含 role / name / content / tool_calls）。
     * 入参形状与 {@link com.yizhaoqi.smartpai.service.LlmProviderRouter#streamChat} 的消息列表一致。
     */
    public int countChatMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;
        for (Map<String, Object> m : messages) {
            total += CHAT_PER_MESSAGE_OVERHEAD;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof CharSequence cs) {
                    total += countText(cs.toString());
                } else {
                    // tool_calls / 多模态 content 数组等结构化字段，序列化成 JSON 后再计数。
                    // 这比单纯按字符估算精确得多，也忠实反映"模型实际看到的 prompt"。
                    try {
                        total += countText(objectMapper.writeValueAsString(v));
                    } catch (Exception ex) {
                        total += countText(String.valueOf(v));
                    }
                }
                // role / name / function 这种 key 也算 1 token（OpenAI 经验值）
                if ("name".equals(e.getKey())) total += 1;
            }
        }
        total += CHAT_REPLY_OVERHEAD;
        return total;
    }

    /** 工具 manifest（function calling schema）也要计入 prompt token —— 序列化后再算。 */
    public int countToolManifest(Object manifestNode) {
        if (manifestNode == null) return 0;
        try {
            String json = objectMapper.writeValueAsString(manifestNode);
            return countText(json);
        } catch (Exception e) {
            return 0;
        }
    }

    public String encodingName() { return encodingName; }

    /**
     * 退化估算：与原 UsageQuotaService 思路一致，仅在 jtokkit 出错时用。
     */
    static int fallbackEstimate(String text) {
        if (text == null || text.isBlank()) return 0;
        int ascii = 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            Character.UnicodeScript s = Character.UnicodeScript.of(c);
            if (s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                    || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL) cjk++;
            else if (c <= 0x7F) ascii++;
            else other++;
        }
        double est = ascii * 0.30d + cjk * 0.95d + other * 0.55d + 8;
        return Math.max(1, (int) Math.ceil(est));
    }
}
