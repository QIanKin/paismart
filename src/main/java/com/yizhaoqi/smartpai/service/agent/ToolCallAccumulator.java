package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 流式 tool_calls 的增量累加器。OpenAI 流式协议下，tool_calls 分多个 chunk 发来：
 * 首次带 id/name/arguments 片段，后续只带 arguments 片段，需按 index 聚合。
 * 本类线程不安全，只能在单线程消费。
 */
public final class ToolCallAccumulator {

    private final TreeMap<Integer, Slot> slots = new TreeMap<>();

    public void accept(int index, String id, String name, String argumentsDelta) {
        Slot s = slots.computeIfAbsent(index, i -> new Slot());
        if (id != null) s.id = id;
        if (name != null) s.name = name;
        if (argumentsDelta != null) s.arguments.append(argumentsDelta);
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public List<PendingToolCall> drain(ObjectMapper mapper) {
        List<PendingToolCall> out = new ArrayList<>(slots.size());
        for (Slot s : slots.values()) {
            JsonNode args = parseJsonLenient(mapper, s.arguments.toString());
            out.add(new PendingToolCall(s.id, s.name, args, s.arguments.toString()));
        }
        slots.clear();
        return out;
    }

    private static JsonNode parseJsonLenient(ObjectMapper mapper, String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(raw);
        } catch (Exception e) {
            ObjectNode node = mapper.createObjectNode();
            node.put("__parse_error", e.getMessage() == null ? "parse_error" : e.getMessage());
            node.put("__raw", raw);
            return node;
        }
    }

    public record PendingToolCall(String id, String name, JsonNode arguments, String rawArguments) {}

    private static final class Slot {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
