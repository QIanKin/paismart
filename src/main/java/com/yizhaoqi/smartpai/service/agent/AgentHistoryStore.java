package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 历史存储。Phase 1 兼容 ChatHandler 使用的 Redis JSON 结构
 * （key = "conversation:" + conversationId，value = List&lt;{role, content, timestamp}&gt; JSON）。
 * Phase 2 会迁到 MySQL Session/Message 表，届时本类替换实现即可，不影响 Runtime。
 *
 * 关键策略：
 *  - 上下文裁剪：最多 20 条，最近优先；Phase 2 由 ContextEngine 接管；
 *  - 只保存纯 text 的 user/assistant 消息，不落 tool_call/tool_result（避免污染 LLM 上下文）；
 *  - conversationId 为空时退化为"userId 独立"的默认会话 id，保证即使未传也能工作。
 */
@Component
public class AgentHistoryStore {

    private static final Logger logger = LoggerFactory.getLogger(AgentHistoryStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int MAX_HISTORY = 20;
    private static final Duration TTL = Duration.ofDays(7);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public AgentHistoryStore(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String id = redisTemplate.opsForValue().get(key);
        if (id == null) {
            id = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, id, TTL);
            logger.info("新建会话 user={} conversationId={}", userId, id);
        }
        return id;
    }

    public List<Map<String, Object>> readHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return new ArrayList<>();
        String json = redisTemplate.opsForValue().get("conversation:" + conversationId);
        if (json == null) return new ArrayList<>();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> cleaned = new ArrayList<>(raw.size());
            for (Map<String, Object> m : raw) {
                String role = String.valueOf(m.getOrDefault("role", ""));
                Object content = m.get("content");
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                if (content == null) continue;
                String contentStr = String.valueOf(content);
                if ("assistant".equals(role) && contentStr.isBlank()) continue;
                Map<String, Object> out = new HashMap<>();
                out.put("role", role);
                out.put("content", contentStr);
                cleaned.add(out);
            }
            return cleaned;
        } catch (Exception e) {
            logger.warn("读取历史失败 conversationId={} err={}", conversationId, e.getMessage());
            return new ArrayList<>();
        }
    }

    public void appendTurn(String conversationId, String userContent, String assistantContent) {
        if (conversationId == null || conversationId.isBlank()) return;
        String key = "conversation:" + conversationId;
        List<Map<String, Object>> history;
        String existing = redisTemplate.opsForValue().get(key);
        try {
            history = existing == null
                    ? new ArrayList<>()
                    : objectMapper.readValue(existing, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            logger.warn("历史反序列化失败，重置: {}", e.getMessage());
            history = new ArrayList<>();
        }
        String ts = LocalDateTime.now().format(TS);
        Map<String, Object> u = new HashMap<>();
        u.put("role", "user");
        u.put("content", userContent == null ? "" : userContent);
        u.put("timestamp", ts);
        history.add(u);

        if (assistantContent != null && !assistantContent.isBlank()) {
            Map<String, Object> a = new HashMap<>();
            a.put("role", "assistant");
            a.put("content", assistantContent);
            a.put("timestamp", ts);
            history.add(a);
        }

        if (history.size() > MAX_HISTORY) {
            history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY, history.size()));
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history), TTL);
        } catch (Exception e) {
            logger.warn("写历史失败 conversationId={} err={}", conversationId, e.getMessage());
        }
    }
}
