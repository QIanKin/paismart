package com.yizhaoqi.smartpai.service.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.agent.AgentMessage;
import com.yizhaoqi.smartpai.model.agent.ChatSession;
import com.yizhaoqi.smartpai.repository.agent.AgentMessageRepository;
import com.yizhaoqi.smartpai.repository.agent.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息存储：混合两层。
 *  - L1 Redis cache：按 sessionId 缓存最近 {@link #L1_CACHE_SIZE} 条未被压缩的消息 JSON 数组；
 *    命中即用，避免常态化查 DB。TTL 7 天，防止长期占用。
 *  - L2 MySQL：所有消息按 seq 持久化；Redis 失效时回灌 L1。
 *
 * 写路径：一次 turn 调用 {@link #appendAssistantTurn}，事务性把 assistant+tool messages 追加到 MySQL
 * 并更新 session 的 messageCount/nextSeq/lastActiveAt，再刷 L1。
 *
 * 读路径：{@link #readRecentForPrompt} 先查 L1；miss 时读 MySQL 最近 N 条（compacted=false）并写 L1。
 */
@Component
public class MessageStore {

    private static final Logger logger = LoggerFactory.getLogger(MessageStore.class);
    private static final int L1_CACHE_SIZE = 40;
    private static final Duration L1_TTL = Duration.ofDays(7);

    private final ChatSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper mapper;

    public MessageStore(ChatSessionRepository sessionRepository,
                        AgentMessageRepository messageRepository,
                        RedisTemplate<String, String> redis,
                        ObjectMapper mapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.redis = redis;
        this.mapper = mapper;
    }

    // --------------------- 读 ---------------------

    /**
     * 拉本会话最近 {@code limit} 条未被压缩的消息（按 seq 升序），用于组装 prompt。
     * 优先走 Redis L1；miss 时读 MySQL 并回填 L1。
     */
    public List<AgentMessage> readRecentForPrompt(Long sessionId, int limit) {
        if (sessionId == null) return List.of();
        List<AgentMessage> cached = readL1(sessionId);
        if (cached == null) {
            // L1 miss：从 DB 读最近 L1_CACHE_SIZE 条
            List<AgentMessage> recent = messageRepository
                    .findBySessionIdAndCompactedFalseOrderBySeqDesc(sessionId, PageRequest.of(0, L1_CACHE_SIZE));
            Collections.reverse(recent);
            writeL1(sessionId, recent);
            cached = recent;
        }
        if (cached.size() <= limit) return cached;
        return cached.subList(cached.size() - limit, cached.size());
    }

    public List<AgentMessage> readAll(Long sessionId) {
        return messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
    }

    // --------------------- 写 ---------------------

    /**
     * 把一轮对话（user + 可能的多条 assistant/tool 交替）事务性写入。
     * - 所有消息共享一个 messageGroupId
     * - seq 在 session 里单调递增
     * - 同步刷新 L1 缓存
     */
    @Transactional
    public TurnWriteResult appendTurn(Long sessionId, String groupId, List<NewMessage> messages) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session 不存在: " + sessionId));
        int seq = session.getNextSeq() == null ? 1 : session.getNextSeq();

        List<AgentMessage> persisted = new ArrayList<>(messages.size());
        for (NewMessage nm : messages) {
            AgentMessage msg = new AgentMessage();
            msg.setSessionId(sessionId);
            msg.setSeq(seq++);
            msg.setMessageGroupId(groupId);
            msg.setRole(nm.role);
            msg.setContent(nm.content);
            msg.setToolCallsJson(nm.toolCallsJson);
            msg.setToolCallId(nm.toolCallId);
            msg.setToolName(nm.toolName);
            msg.setToolDurationMs(nm.toolDurationMs);
            msg.setCompacted(false);
            msg.setTokenEstimate(nm.tokenEstimate);
            persisted.add(messageRepository.save(msg));
        }
        session.setNextSeq(seq);
        session.setMessageCount(session.getMessageCount() + persisted.size());
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(session);

        refreshL1AfterAppend(sessionId, persisted);
        return new TurnWriteResult(session, persisted);
    }

    public void invalidateL1(Long sessionId) {
        redis.delete(cacheKey(sessionId));
    }

    // --------------------- L1 ---------------------

    private String cacheKey(Long sessionId) {
        return "agent:session:msgs:" + sessionId;
    }

    private List<AgentMessage> readL1(Long sessionId) {
        String raw = redis.opsForValue().get(cacheKey(sessionId));
        if (raw == null) return null;
        try {
            return mapper.readValue(raw, new TypeReference<List<AgentMessage>>() {});
        } catch (Exception e) {
            logger.warn("L1 反序列化失败 sessionId={} err={}", sessionId, e.getMessage());
            redis.delete(cacheKey(sessionId));
            return null;
        }
    }

    private void writeL1(Long sessionId, List<AgentMessage> messages) {
        try {
            redis.opsForValue().set(cacheKey(sessionId), mapper.writeValueAsString(messages), L1_TTL);
        } catch (Exception e) {
            logger.warn("L1 序列化失败 sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    private void refreshL1AfterAppend(Long sessionId, List<AgentMessage> newMessages) {
        List<AgentMessage> cached = readL1(sessionId);
        if (cached == null) {
            // 初始化 L1
            cached = new ArrayList<>(newMessages);
        } else {
            cached = new ArrayList<>(cached);
            cached.addAll(newMessages);
        }
        if (cached.size() > L1_CACHE_SIZE) {
            cached = new ArrayList<>(cached.subList(cached.size() - L1_CACHE_SIZE, cached.size()));
        }
        writeL1(sessionId, cached);
    }

    // --------------------- DTO ---------------------

    /** Runtime → Store 的写入 DTO。tokenEstimate 可传 null，让 store 自己估。 */
    public record NewMessage(
            AgentMessage.Role role,
            String content,
            String toolCallsJson,
            String toolCallId,
            String toolName,
            Long toolDurationMs,
            Integer tokenEstimate
    ) {
        public static NewMessage user(String content, int tokenEstimate) {
            return new NewMessage(AgentMessage.Role.user, content, null, null, null, null, tokenEstimate);
        }
        public static NewMessage assistant(String content, String toolCallsJson, int tokenEstimate) {
            return new NewMessage(AgentMessage.Role.assistant, content, toolCallsJson, null, null, null, tokenEstimate);
        }
        public static NewMessage tool(String toolCallId, String toolName, String content, long durationMs, int tokenEstimate) {
            return new NewMessage(AgentMessage.Role.tool, content, null, toolCallId, toolName, durationMs, tokenEstimate);
        }
    }

    public record TurnWriteResult(ChatSession session, List<AgentMessage> persisted) {}

    /**
     * 把 AgentMessage 转回 OpenAI messages 格式（role/content[/tool_calls/tool_call_id]）。
     */
    public Map<String, Object> toOpenAiMessage(AgentMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", m.getRole().name());
        if (m.getContent() != null) out.put("content", m.getContent());
        if (m.getRole() == AgentMessage.Role.tool) {
            out.put("tool_call_id", m.getToolCallId());
        }
        if (m.getRole() == AgentMessage.Role.assistant && m.getToolCallsJson() != null && !m.getToolCallsJson().isBlank()) {
            try {
                out.put("tool_calls", mapper.readValue(m.getToolCallsJson(), new TypeReference<List<Map<String, Object>>>() {}));
            } catch (Exception e) {
                logger.warn("tool_calls 反序列化失败 id={} err={}", m.getId(), e.getMessage());
            }
        }
        return out;
    }
}
