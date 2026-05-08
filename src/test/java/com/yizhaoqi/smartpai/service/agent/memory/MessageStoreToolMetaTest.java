package com.yizhaoqi.smartpai.service.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.agent.AgentMessage;
import com.yizhaoqi.smartpai.model.agent.ChatSession;
import com.yizhaoqi.smartpai.repository.agent.AgentMessageRepository;
import com.yizhaoqi.smartpai.repository.agent.ChatSessionRepository;
import com.yizhaoqi.smartpai.service.agent.AgentMessageContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 Bug-fix「视频卡片刷新后消失」的链路：
 * MessageStore.appendTurn 收到的 NewMessage.tool 携带 toolMetaJson 时，落库到 AgentMessage.toolMetaJson 字段。
 */
class MessageStoreToolMetaTest {

    private MessageStore store;
    private AgentMessageRepository messageRepository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(AgentMessageRepository.class);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        ObjectMapper mapper = new ObjectMapper();
        AgentMessageContentService contentService = mock(AgentMessageContentService.class);

        ChatSession session = new ChatSession();
        session.setId(123L);
        session.setNextSeq(1);
        session.setMessageCount(0);
        when(sessionRepository.findById(123L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicLong idSeq = new AtomicLong(1);
        when(messageRepository.save(any(AgentMessage.class))).thenAnswer(inv -> {
            AgentMessage m = inv.getArgument(0);
            m.setId(idSeq.getAndIncrement());
            return m;
        });

        store = new MessageStore(sessionRepository, messageRepository, redis, mapper, contentService);
    }

    @Test
    void toolMetaJsonPropagatesToEntity() {
        String metaJson = "{\"videoUrl\":\"https://example.com/v.mp4\",\"transcriptUrl\":\"https://example.com/t.txt\"}";
        MessageStore.TurnWriteResult res = store.appendTurn(123L, "g-1", List.of(
                MessageStore.NewMessage.user("hi", 1),
                MessageStore.NewMessage.tool("call_1", "xhs_video_analyze",
                        "{\"summary\":\"ok\"}", 9999L, metaJson, 5)
        ));
        List<AgentMessage> persisted = res.persisted();
        assertEquals(2, persisted.size());
        AgentMessage user = persisted.get(0);
        AgentMessage tool = persisted.get(1);
        assertEquals(AgentMessage.Role.user, user.getRole());
        assertNull(user.getToolMetaJson());
        assertEquals(AgentMessage.Role.tool, tool.getRole());
        assertEquals(metaJson, tool.getToolMetaJson());
        assertEquals(9999L, tool.getToolDurationMs());
        assertEquals("xhs_video_analyze", tool.getToolName());
        assertEquals("call_1", tool.getToolCallId());
    }
}
