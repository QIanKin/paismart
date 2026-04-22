package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolProgress;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4a 关键回归：AskUserQuestionTool 必须把问题打到专用 ask_user 通道，
 * 而不是 emitProgress（tool_progress）——前端 switch 只认前者。
 */
class AskUserQuestionToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AskUserQuestionTool tool;

    @BeforeEach
    void setUp() {
        tool = new AskUserQuestionTool();
    }

    @Test
    void emitsOnAskUserSinkNotOnProgressSink() {
        AtomicReference<String> askedQuestion = new AtomicReference<>();
        AtomicReference<List<String>> askedOptions = new AtomicReference<>();
        List<ToolProgress> progressEvents = new ArrayList<>();

        ToolContext ctx = ToolContext.builder()
                .userId("u1")
                .orgTag("acme")
                .role("user")
                .toolUseId("tool-use-1")
                .progressSink(progressEvents::add)
                .askUserSink((q, opts) -> {
                    askedQuestion.set(q);
                    askedOptions.set(opts);
                })
                .build();

        ObjectNode in = mapper.createObjectNode();
        in.put("question", "要搜哪个平台？");
        ArrayNode opts = in.putArray("options");
        opts.add("小红书");
        opts.add("抖音");

        ToolResult res = tool.call(ctx, in);

        assertFalse(res.isError(), "call 不应报错");
        assertEquals("要搜哪个平台？", askedQuestion.get(), "askUserSink 应收到原问题");
        assertEquals(List.of("小红书", "抖音"), askedOptions.get(), "askUserSink 应收到 options");
        assertTrue(progressEvents.isEmpty(),
                "不能再走 tool_progress 通道 —— 否则前端拿不到 ask_user 事件");
    }

    @Test
    void rejectsEmptyQuestion() {
        ToolContext ctx = ToolContext.builder()
                .userId("u1").orgTag("acme").role("user")
                .toolUseId("t1")
                .askUserSink((q, opts) -> fail("不应在 question 为空时还打 askUserSink"))
                .build();

        ObjectNode in = mapper.createObjectNode();
        in.put("question", "  ");
        ToolResult res = tool.call(ctx, in);

        assertTrue(res.isError());
    }

    @Test
    void emptyOptionsStillEmits() {
        AtomicReference<List<String>> capturedOpts = new AtomicReference<>();
        ToolContext ctx = ToolContext.builder()
                .userId("u1").orgTag("acme").role("user")
                .toolUseId("t1")
                .askUserSink((q, opts) -> capturedOpts.set(opts))
                .build();

        ObjectNode in = mapper.createObjectNode();
        in.put("question", "给个自由回答就行");

        ToolResult res = tool.call(ctx, in);
        assertFalse(res.isError());
        assertNotNull(capturedOpts.get());
        assertTrue(capturedOpts.get().isEmpty(), "没提供 options 时应为空列表");
    }

    @Test
    void silentWhenSinkNotWired() {
        // 默认 builder 的 askUserSink 是 no-op，不应抛
        ToolContext ctx = ToolContext.builder()
                .userId("u1").orgTag("acme").role("user")
                .toolUseId("t1")
                .build();

        ObjectNode in = mapper.createObjectNode();
        in.put("question", "test");

        ToolResult res = tool.call(ctx, in);
        assertFalse(res.isError());
    }
}
