package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * sleep：让 agent 有限时等待（例如外部抓取限流、等定时任务下次触发）。
 * 对标 Open-ClaudeCode SleepTool：不占 shell 进程，只睡线程；支持取消。
 *
 * 上限 60 秒 —— 避免 LLM 把对话卡死。更长的等待改用 ScheduleCreateTool 排期。
 */
@Component
public class SleepTool implements Tool {

    private static final int MAX_SECONDS = 60;
    private static final int DEFAULT_SECONDS = 5;

    private final JsonNode schema = ToolInputSchemas.object()
            .integerProp("seconds", "要睡的秒数；范围 1~" + MAX_SECONDS + "，默认 " + DEFAULT_SECONDS, false)
            .stringProp("reason", "可选：为什么要等（日志/UI 展示）", false)
            .additionalProperties(false)
            .build();

    @Override public String name() { return "sleep"; }
    @Override public String description() {
        return "让当前 agent 轮次暂停若干秒（≤ " + MAX_SECONDS + "s）。用于外部限流、等定时任务；"
                + "不持有 shell 进程。如果需要几分钟以上的等待，改用 schedule_create 排期。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        int seconds = input.has("seconds") ? input.get("seconds").asInt(DEFAULT_SECONDS) : DEFAULT_SECONDS;
        if (seconds < 1) seconds = 1;
        if (seconds > MAX_SECONDS) seconds = MAX_SECONDS;
        String reason = input.has("reason") && !input.get("reason").isNull() ? input.get("reason").asText("") : "";

        long until = System.currentTimeMillis() + seconds * 1000L;
        int ticked = 0;
        while (System.currentTimeMillis() < until) {
            if (ctx.isCancelled()) {
                return ToolResult.of(
                        Map.of("sleptSeconds", ticked, "cancelled", true, "reason", reason),
                        "sleep 被中断（sl " + ticked + "/" + seconds + "s）");
            }
            long left = until - System.currentTimeMillis();
            long step = Math.min(500L, Math.max(50L, left));
            Thread.sleep(step);
            ticked = seconds - (int) Math.ceil(left / 1000.0);
        }
        return ToolResult.of(
                Map.of("sleptSeconds", seconds, "cancelled", false, "reason", reason),
                "sleep " + seconds + "s" + (reason.isEmpty() ? "" : "：" + reason));
    }
}
