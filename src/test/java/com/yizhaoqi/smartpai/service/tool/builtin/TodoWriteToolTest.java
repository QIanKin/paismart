package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolProgress;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 4a 关键回归：TodoWriteTool 必须走专用 todo 通道（ToolContext.updateTodos），
 * 而不是 emitProgress（tool_progress）——前端 switch 里 'todo' 和 'tool_progress' 是两回事。
 */
class TodoWriteToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TodoWriteTool tool;
    private final Map<String, String> fakeRedis = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // 读：fakeRedis.get(key)
        when(valueOps.get(anyString())).thenAnswer(inv -> fakeRedis.get(inv.getArgument(0, String.class)));
        // 写：fakeRedis.put(key, value)
        doAnswer(inv -> {
            fakeRedis.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        tool = new TodoWriteTool(redis);
    }

    private ToolContext.Builder baseCtx(AtomicReference<Object> capturedTodos, List<ToolProgress> progressEvents) {
        return ToolContext.builder()
                .userId("u1")
                .orgTag("acme")
                .role("user")
                .sessionId("sess-1")
                .toolUseId("tc-1")
                .progressSink(progressEvents::add)
                .todoSink(capturedTodos::set);
    }

    private ObjectNode buildTodosInput(String id, String content, String status) {
        ObjectNode in = mapper.createObjectNode();
        ArrayNode arr = in.putArray("todos");
        ObjectNode t = arr.addObject();
        t.put("id", id);
        t.put("content", content);
        t.put("status", status);
        return in;
    }

    @Test
    void emitsOnTodoSinkNotOnProgressSink() throws Exception {
        AtomicReference<Object> captured = new AtomicReference<>();
        List<ToolProgress> progressEvents = new ArrayList<>();

        ObjectNode in = buildTodosInput("t1", "抓 10 条笔记", "in_progress");

        ToolResult res = tool.call(baseCtx(captured, progressEvents).build(), in);

        assertFalse(res.isError());
        assertNotNull(captured.get(), "todoSink 应被触发");
        assertTrue(captured.get() instanceof ArrayNode, "传给 todoSink 的应是 ArrayNode");
        ArrayNode arr = (ArrayNode) captured.get();
        assertEquals(1, arr.size());
        assertEquals("t1", arr.get(0).path("id").asText());
        assertEquals("in_progress", arr.get(0).path("status").asText());
        assertTrue(progressEvents.isEmpty(),
                "不能再通过 tool_progress 推 todo —— 前端不认那个频道");
    }

    @Test
    void rejectsMultipleInProgress() throws Exception {
        AtomicReference<Object> captured = new AtomicReference<>();
        List<ToolProgress> progressEvents = new ArrayList<>();

        ObjectNode in = mapper.createObjectNode();
        ArrayNode arr = in.putArray("todos");
        ObjectNode t1 = arr.addObject();
        t1.put("id", "a"); t1.put("content", "x"); t1.put("status", "in_progress");
        ObjectNode t2 = arr.addObject();
        t2.put("id", "b"); t2.put("content", "y"); t2.put("status", "in_progress");

        ToolResult res = tool.call(baseCtx(captured, progressEvents).build(), in);

        assertTrue(res.isError(), "同时两个 in_progress 应报错");
        assertNull(captured.get(), "报错路径不应触发 todoSink");
    }

    @Test
    void mergeByIdKeepsExisting() throws Exception {
        AtomicReference<Object> captured = new AtomicReference<>();
        List<ToolProgress> progressEvents = new ArrayList<>();

        // 先写入 t1 = pending
        ObjectNode first = buildTodosInput("t1", "原任务", "pending");
        tool.call(baseCtx(captured, progressEvents).build(), first);

        // 合并追加 t2
        ObjectNode second = mapper.createObjectNode();
        second.put("merge", true);
        ArrayNode arr = second.putArray("todos");
        ObjectNode t2 = arr.addObject();
        t2.put("id", "t2"); t2.put("content", "新任务"); t2.put("status", "pending");

        ToolResult res = tool.call(baseCtx(captured, progressEvents).build(), second);

        assertFalse(res.isError());
        ArrayNode merged = (ArrayNode) captured.get();
        assertEquals(2, merged.size(), "merge=true 应保留原 t1");
        List<String> ids = new ArrayList<>();
        for (JsonNode n : merged) ids.add(n.path("id").asText());
        assertTrue(ids.contains("t1"));
        assertTrue(ids.contains("t2"));
    }

    @Test
    void replaceModeDropsOld() throws Exception {
        AtomicReference<Object> captured = new AtomicReference<>();
        List<ToolProgress> progressEvents = new ArrayList<>();

        ObjectNode first = buildTodosInput("t1", "老任务", "completed");
        tool.call(baseCtx(captured, progressEvents).build(), first);

        ObjectNode second = buildTodosInput("t2", "新任务", "pending");
        ToolResult res = tool.call(baseCtx(captured, progressEvents).build(), second);

        assertFalse(res.isError());
        ArrayNode arr = (ArrayNode) captured.get();
        assertEquals(1, arr.size(), "默认 merge=false 应整体替换");
        assertEquals("t2", arr.get(0).path("id").asText());
    }
}
