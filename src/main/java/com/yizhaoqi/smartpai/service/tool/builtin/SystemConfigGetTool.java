package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.agent.FeatureFlagService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * system_config_get：读单个系统配置项的当前值（API key 永远 mask 返回，不会泄漏原文）。
 *
 * <p>**ADMIN-only**。
 */
@Component
public class SystemConfigGetTool implements Tool {

    private final FeatureFlagService featureFlags;
    private final ModelProviderConfigService modelConfig;
    private final JsonNode schema;

    public SystemConfigGetTool(FeatureFlagService featureFlags, ModelProviderConfigService modelConfig) {
        this.featureFlags = featureFlags;
        this.modelConfig = modelConfig;
        this.schema = SystemConfigSupport.getSchema();
    }

    @Override public String name() { return "system_config_get"; }

    @Override public String description() {
        return "读取一项系统配置当前值。支持的 key 命名空间：feature_flag.<flag> 与 llm.<chat|embedding>.<active|url|key|model>。"
                + "API key 永远以 mask 形式返回。**仅 ADMIN 可用**。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        ToolResult forbidden = SystemConfigSupport.requireAdmin(ctx);
        if (forbidden != null) return forbidden;

        String key = input.path("key").asText("");
        try {
            Map<String, Object> data = SystemConfigSupport.get(key, featureFlags, modelConfig);
            return ToolResult.of(data, key + " = " + safeStr(data.get("value")));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("bad_input", e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("config_error", "读取失败: " + e.getMessage());
        }
    }

    private static String safeStr(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
