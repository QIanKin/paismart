package com.yizhaoqi.smartpai.service.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 合并两份自定义字段 JSON（都是 object {key: value}）的工具类。
 * 规则：
 *  - incoming 优先覆盖 existing；
 *  - incoming 里 value=null 表示"删除此字段"；
 *  - null/空字符串的 existing 当成 {} 处理。
 */
public final class CustomFieldsMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CustomFieldsMerger() {}

    public static String merge(String existingJson, String incomingJson) {
        ObjectNode existing = parseObj(existingJson);
        ObjectNode incoming = parseObj(incomingJson);
        if (incoming == null) {
            return existing == null ? null : existing.toString();
        }
        if (existing == null) {
            // 拷贝 incoming 但剥掉 null 字段
            ObjectNode cleaned = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = incoming.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue() != null && !e.getValue().isNull()) cleaned.set(e.getKey(), e.getValue());
            }
            return cleaned.isEmpty() ? null : cleaned.toString();
        }
        Iterator<Map.Entry<String, JsonNode>> it = incoming.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                existing.remove(k);
            } else {
                existing.set(k, v);
            }
        }
        return existing.isEmpty() ? null : existing.toString();
    }

    private static ObjectNode parseObj(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isObject() ? (ObjectNode) n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
