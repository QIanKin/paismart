package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.agent.FeatureFlagService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * system_config_list：列出当前可写的所有系统配置 key + 当前值（key 已 mask）。
 *
 * <p>**ADMIN-only**。普通用户调用会直接返回 forbidden。
 */
@Component
public class SystemConfigListTool implements Tool {

    private final FeatureFlagService featureFlags;
    private final ModelProviderConfigService modelConfig;
    private final JsonNode schema;

    public SystemConfigListTool(FeatureFlagService featureFlags, ModelProviderConfigService modelConfig) {
        this.featureFlags = featureFlags;
        this.modelConfig = modelConfig;
        this.schema = SystemConfigSupport.listSchema();
    }

    @Override public String name() { return "system_config_list"; }

    @Override public String description() {
        return "列出当前所有可写的系统配置 key 及其值（API key 已 mask）。"
                + "覆盖：feature_flag.* (运行时数据源开关) + llm.chat.* / llm.embedding.* (大模型 provider 配置)。"
                + "**仅 ADMIN 可用**，普通会话调用会被拒绝。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        ToolResult forbidden = SystemConfigSupport.requireAdmin(ctx);
        if (forbidden != null) return forbidden;

        String prefix = input.path("prefix").asText("");
        List<Map<String, Object>> all = SystemConfigSupport.listAll(featureFlags, modelConfig);
        if (!prefix.isBlank()) {
            all = all.stream().filter(m -> {
                Object k = m.get("key");
                return k != null && k.toString().startsWith(prefix);
            }).toList();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("items", all);
        return ToolResult.of(out, "可写系统配置 " + all.size() + " 条");
    }
}
