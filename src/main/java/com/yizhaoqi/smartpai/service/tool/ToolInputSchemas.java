package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;

/**
 * JSON Schema 构建器。让工具以流式 API 声明 inputSchema，产物直接符合 OpenAI function calling。
 *
 * 示例：
 * <pre>
 * inputSchema = ToolInputSchemas.object()
 *     .stringProp("query", "用户问题", true)
 *     .integerProp("top_k", "返回结果数，默认5", false)
 *     .enumProp("scope", "搜索范围", List.of("personal","tenant","public"), false)
 *     .build();
 * </pre>
 */
public final class ToolInputSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolInputSchemas() {}

    public static Builder object() {
        return new Builder();
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static final class Builder {
        private final ObjectNode root = MAPPER.createObjectNode();
        private final ObjectNode properties = MAPPER.createObjectNode();
        private final ArrayNode required = MAPPER.createArrayNode();

        private Builder() {
            root.put("type", "object");
            root.set("properties", properties);
        }

        public Builder prop(String name, ObjectNode schema, boolean requiredFlag) {
            properties.set(name, schema);
            if (requiredFlag) {
                required.add(name);
            }
            return this;
        }

        public Builder stringProp(String name, String desc, boolean requiredFlag) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "string");
            if (desc != null) node.put("description", desc);
            return prop(name, node, requiredFlag);
        }

        public Builder integerProp(String name, String desc, boolean requiredFlag) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "integer");
            if (desc != null) node.put("description", desc);
            return prop(name, node, requiredFlag);
        }

        public Builder numberProp(String name, String desc, boolean requiredFlag) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "number");
            if (desc != null) node.put("description", desc);
            return prop(name, node, requiredFlag);
        }

        public Builder booleanProp(String name, String desc, boolean requiredFlag) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "boolean");
            if (desc != null) node.put("description", desc);
            return prop(name, node, requiredFlag);
        }

        public Builder arrayProp(String name, String desc, ObjectNode itemSchema, boolean requiredFlag) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "array");
            if (desc != null) node.put("description", desc);
            node.set("items", itemSchema);
            return prop(name, node, requiredFlag);
        }

        public Builder enumProp(String name, String desc, List<String> values, boolean requiredFlag) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "string");
            if (desc != null) node.put("description", desc);
            ArrayNode arr = node.putArray("enum");
            values.forEach(arr::add);
            return prop(name, node, requiredFlag);
        }

        public Builder enumProp(String name, String desc, String[] values, boolean requiredFlag) {
            return enumProp(name, desc, Arrays.asList(values), requiredFlag);
        }

        public Builder objectProp(String name, String desc, ObjectNode schema, boolean requiredFlag) {
            ObjectNode node = schema.deepCopy();
            if (desc != null) node.put("description", desc);
            return prop(name, node, requiredFlag);
        }

        public Builder additionalProperties(boolean allow) {
            root.put("additionalProperties", allow);
            return this;
        }

        public JsonNode build() {
            if (required.size() > 0) {
                root.set("required", required);
            }
            return root;
        }
    }

    public static ObjectNode stringType() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "string");
        return node;
    }
}
