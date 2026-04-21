package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleepToolTest {

    @Test
    void sleepsApproximatelyTheRequestedDuration() throws Exception {
        SleepTool tool = new SleepTool();
        ObjectNode in = new ObjectMapper().createObjectNode();
        in.put("seconds", 1);
        long t0 = System.currentTimeMillis();
        ToolResult r = tool.call(ToolContext.builder().build(), in);
        long dt = System.currentTimeMillis() - t0;
        assertFalse(r.isError());
        assertTrue(dt >= 900, "应接近 1 秒，实际 " + dt + "ms");
        assertTrue(dt < 2500, "不应远超预期，实际 " + dt + "ms");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertFalse((Boolean) data.get("cancelled"));
    }

    @Test
    void cancellationInterruptsEarly() throws Exception {
        SleepTool tool = new SleepTool();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ToolContext ctx = ToolContext.builder().cancelled(cancelled).build();
        ObjectNode in = new ObjectMapper().createObjectNode();
        in.put("seconds", 10);

        Thread thr = new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            cancelled.set(true);
        });
        thr.start();

        long t0 = System.currentTimeMillis();
        ToolResult r = tool.call(ctx, in);
        long dt = System.currentTimeMillis() - t0;
        thr.join(2000);

        assertFalse(r.isError());
        assertTrue(dt < 3000, "应被取消，实际 " + dt + "ms");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertTrue((Boolean) data.get("cancelled"));
    }

    @Test
    void clampsSecondsToSafeRange() throws Exception {
        SleepTool tool = new SleepTool();
        ObjectNode in = new ObjectMapper().createObjectNode();
        in.put("seconds", 99999);
        // 用取消来快速退出，不要真睡 60s
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ToolContext ctx = ToolContext.builder().cancelled(cancelled).build();
        Thread thr = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            cancelled.set(true);
        });
        thr.start();
        long t0 = System.currentTimeMillis();
        ToolResult r = tool.call(ctx, in);
        long dt = System.currentTimeMillis() - t0;
        thr.join(2000);
        assertFalse(r.isError());
        // 如果没 clamp，可能跑 60s；有 clamp 且被取消，应 < 2s
        assertTrue(dt < 3000, "应被 clamp + 取消，实际 " + dt + "ms");
    }
}
