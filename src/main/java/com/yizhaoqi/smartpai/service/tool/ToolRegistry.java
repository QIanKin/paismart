package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具注册中心。启动时通过 Spring 扫描所有 {@link Tool} bean，按 name 索引。
 * 除了存取，还负责把 Tool 列表编译成 OpenAI function calling 所需的 tools manifest。
 */
@Component
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Tool> toolByName;

    public ToolRegistry(List<Tool> tools) {
        Map<String, Tool> acc = new LinkedHashMap<>();
        for (Tool t : tools) {
            if (!t.isEnabled()) {
                logger.info("Tool [{}] 已禁用，跳过注册", t.name());
                continue;
            }
            if (acc.containsKey(t.name())) {
                throw new IllegalStateException("Tool name 冲突: " + t.name());
            }
            acc.put(t.name(), t);
        }
        this.toolByName = Collections.unmodifiableMap(acc);
        logger.info("ToolRegistry 启动完成，已注册 {} 个工具：{}",
                toolByName.size(), toolByName.keySet());
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(toolByName.get(name));
    }

    public Collection<Tool> all() {
        return toolByName.values();
    }

    /**
     * 按名字列表筛一个子集；null 或空 -> 返回全部。忽略找不到的工具名。
     */
    public List<Tool> subset(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.copyOf(toolByName.values());
        }
        return names.stream()
                .map(toolByName::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 把工具列表编译为 OpenAI function-calling 的 tools 数组：
     * [{ "type": "function", "function": { "name": ..., "description": ..., "parameters": { ...schema... } } }, ...]
     */
    public ArrayNode toOpenAiManifest(Collection<Tool> tools) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Tool t : tools) {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("type", "function");
            ObjectNode fn = MAPPER.createObjectNode();
            fn.put("name", t.name());
            fn.put("description", t.description());
            JsonNode schema = t.inputSchema();
            fn.set("parameters", schema == null ? MAPPER.createObjectNode().put("type", "object") : schema);
            entry.set("function", fn);
            arr.add(entry);
        }
        return arr;
    }

    public ArrayNode toOpenAiManifestAll() {
        return toOpenAiManifest(all());
    }
}
