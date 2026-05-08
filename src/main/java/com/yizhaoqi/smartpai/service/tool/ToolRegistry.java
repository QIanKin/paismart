package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.agent.FeatureFlagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    /** 用 ObjectProvider 解决 ToolRegistry → FeatureFlagService → properties 的循环依赖风险，并允许 flags 缺失时退化为"不过滤"。 */
    private final ObjectProvider<FeatureFlagService> featureFlagsProvider;

    /** 给测试便利构造和"flag 缺失"退化路径使用的 no-op ObjectProvider。 */
    private static final ObjectProvider<FeatureFlagService> EMPTY_PROVIDER = new ObjectProvider<>() {
        @Override public FeatureFlagService getObject(Object... args) { throw new UnsupportedOperationException(); }
        @Override public FeatureFlagService getObject() { throw new UnsupportedOperationException(); }
        @Override public FeatureFlagService getIfAvailable() { return null; }
        @Override public FeatureFlagService getIfUnique() { return null; }
    };

    /**
     * 测试便利构造：不传 ObjectProvider，等价于"FeatureFlag 不可用 → 不过滤"。
     */
    public ToolRegistry(List<Tool> tools) {
        this(tools, EMPTY_PROVIDER);
    }

    @Autowired
    public ToolRegistry(List<Tool> tools, ObjectProvider<FeatureFlagService> featureFlagsProvider) {
        this.featureFlagsProvider = featureFlagsProvider == null ? EMPTY_PROVIDER : featureFlagsProvider;
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
     * 按名字列表筛一个子集；null -> 返回全部（仍受 feature flag 过滤），空集合 -> 返回空集合。
     * 空集合通常表示多层白名单交集为空，不能回退成全量工具。
     * 忽略找不到的工具名。
     *
     * <p>每次调用都会重新询问 {@link FeatureFlagService} 是否允许该工具暴露给 LLM —— 这是
     * 数据源运行时开关的关键钩子（关闭蒲公英后，本次 turn 立即不会把 xhs_pgy_* 报给 LLM）。
     */
    public List<Tool> subset(Collection<String> names) {
        if (names == null) {
            return applyFlagFilter(toolByName.values());
        }
        if (names.isEmpty()) return List.of();
        List<Tool> raw = names.stream()
                .map(toolByName::get)
                .filter(Objects::nonNull)
                .toList();
        return applyFlagFilter(raw);
    }

    private List<Tool> applyFlagFilter(Collection<Tool> tools) {
        FeatureFlagService flags = featureFlagsProvider.getIfAvailable();
        if (flags == null) return List.copyOf(tools);
        List<Tool> out = new ArrayList<>(tools.size());
        for (Tool t : tools) {
            if (flags.isToolEnabledByFlags(t.name())) out.add(t);
        }
        return out;
    }

    /**
     * 让 controller 用：判断某个工具当前是否被 feature flag 放行。
     */
    public boolean isToolAllowed(String toolName) {
        FeatureFlagService flags = featureFlagsProvider.getIfAvailable();
        if (flags == null) return true;
        return flags.isToolEnabledByFlags(toolName);
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
