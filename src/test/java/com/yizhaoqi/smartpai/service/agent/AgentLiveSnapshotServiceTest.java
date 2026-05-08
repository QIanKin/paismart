package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Bug-fix「流式中断不续接」的 Redis 快照服务：
 *  - 写入后能反序列化回 LiveSnapshot；
 *  - chunk 累积；
 *  - tool_call → tool_result 状态切换；
 *  - meta JSON 能保留视频卡片字段；
 *  - clear 后读出 null。
 */
class AgentLiveSnapshotServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private final Map<String, String> backing = new HashMap<>();
    private AgentLiveSnapshotService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        backing.clear();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> backing.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            backing.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> {
            backing.remove(inv.<String>getArgument(0));
            return Boolean.TRUE;
        });
        service = new AgentLiveSnapshotService(redisTemplate, mapper);
    }

    @Test
    void readReturnsNullWhenAbsent() {
        assertNull(service.read(42L));
    }

    @Test
    void startThenChunkAccumulates() {
        service.start(7L, "msg-1");
        service.appendChunk(7L, "msg-1", "Hel");
        service.appendChunk(7L, "msg-1", "lo");
        AgentLiveSnapshotService.LiveSnapshot snap = service.read(7L);
        assertNotNull(snap);
        assertEquals("Hello", snap.partialContent);
        assertEquals("running", snap.status);
        assertEquals("msg-1", snap.messageId);
    }

    @Test
    void toolCallTransitionsToOkAndKeepsMeta() {
        service.start(8L, "msg-2");
        service.recordToolCall(8L, "msg-2", "call_1", null, null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("videoUrl", "https://example.com/v.mp4");
        meta.put("transcriptUrl", "https://example.com/t.txt");
        ToolResult result = ToolResult.of("ok", "已分析", meta);
        service.recordToolResult(8L, "call_1", "xhs_video_analyze", result, 1234L, "preview-text");

        AgentLiveSnapshotService.LiveSnapshot snap = service.read(8L);
        assertEquals(1, snap.toolCalls.size());
        AgentLiveSnapshotService.ToolCallSnapshot c = snap.toolCalls.get(0);
        assertEquals("ok", c.status);
        assertEquals("xhs_video_analyze", c.tool);
        assertEquals("已分析", c.summary);
        assertEquals("preview-text", c.preview);
        assertEquals(1234L, c.durationMs);
        assertEquals("https://example.com/v.mp4", c.meta.get("videoUrl"));
    }

    @Test
    void clearRemovesSnapshot() {
        service.start(9L, "msg-3");
        service.appendChunk(9L, "msg-3", "x");
        assertNotNull(service.read(9L));
        service.clear(9L);
        assertNull(service.read(9L));
        verify(redisTemplate, times(1)).delete(eq("agent:session:live:9"));
    }

    @Test
    void todosAndAskUserCarryThroughSerialization() {
        service.start(10L, "msg-4");
        service.recordTodos(10L, List.of(
                Map.of("id", "t1", "content", "step", "status", "in_progress")
        ));
        service.recordAskUser(10L, "继续吗?", List.of("是", "否"));
        AgentLiveSnapshotService.LiveSnapshot snap = service.read(10L);
        assertEquals(1, snap.todos.size());
        assertNotNull(snap.askUser);
        assertEquals("继续吗?", snap.askUser.question);
        assertEquals(2, snap.askUser.options.size());
        assertTrue(snap.askUser.askedAt > 0);
    }
}
