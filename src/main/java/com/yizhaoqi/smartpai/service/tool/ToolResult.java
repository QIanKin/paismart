package com.yizhaoqi.smartpai.service.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具的执行结果。
 * - {@code data} 是要回灌给 LLM 的载荷（会被序列化为 JSON 放进 tool 消息 content）；
 * - {@code summary} 是人类可读的一句话摘要，Runtime 会发到 WS tool_result.summary 字段展示；
 * - {@code meta} 留作扩展（例如记录扫描了多少条文档、花了多少 ms、外链 URL）。
 *
 * 设计原则参考 claude-code agent-tools：LLM 看到的是 data；UI 展示的是 summary + meta。
 * 两者分开是为了避免在把结构化结果"拍扁"给 UI 时污染 LLM 上下文。
 */
public record ToolResult(Object data, String summary, Map<String, Object> meta, boolean isError) {

    public static ToolResult of(Object data, String summary) {
        return new ToolResult(data, summary, Map.of(), false);
    }

    public static ToolResult of(Object data, String summary, Map<String, Object> meta) {
        return new ToolResult(data, summary, meta == null ? Map.of() : meta, false);
    }

    public static ToolResult text(String text) {
        return new ToolResult(text, text, Map.of(), false);
    }

    public static ToolResult error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", true);
        payload.put("message", message);
        return new ToolResult(payload, message, Map.of(), true);
    }

    public static ToolResult error(String message, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", true);
        payload.put("message", message);
        if (extra != null) {
            payload.putAll(extra);
        }
        return new ToolResult(payload, message, Map.of(), true);
    }
}
