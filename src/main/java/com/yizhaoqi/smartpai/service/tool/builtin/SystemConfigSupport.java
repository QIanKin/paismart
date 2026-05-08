package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.agent.FeatureFlagService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SystemConfigSupport：把 LLM 工具能写的"系统配置"统一收口。
 *
 * <p>当前支持的 key 命名空间（其它一律拒绝）：
 * <ul>
 *   <li>{@code feature_flag.<flag_key>}（boolean）— 委托给 {@link FeatureFlagService}</li>
 *   <li>{@code llm.chat.url|key|model}（string）— 委托给 {@link ModelProviderConfigService} 的 chat scope</li>
 *   <li>{@code llm.chat.active}（string）— 切换 chat scope 的 active provider</li>
 *   <li>{@code llm.embedding.url|key|model}（string）— 类似 chat</li>
 *   <li>{@code llm.embedding.active}（string）— 切换 embedding scope 的 active provider</li>
 * </ul>
 *
 * <p>读 LLM key 时永远只返回 masked 形式，不会泄露原文。
 */
final class SystemConfigSupport {

    private SystemConfigSupport() {}

    static final String SCOPE_LLM = "llm";
    static final String SCOPE_EMBEDDING = "embedding";

    /** 列出所有可写的配置 key + 当前值（key 是 mask 过的）。 */
    static List<Map<String, Object>> listAll(FeatureFlagService flags,
                                             ModelProviderConfigService modelConfig) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FeatureFlagService.FlagView f : flags.listAll()) {
            rows.add(entry("feature_flag." + f.key(), "boolean", f.enabled(),
                    "FeatureFlag · " + f.label(), f.description()));
        }
        ModelProviderConfigService.ModelProviderSettingsView all = modelConfig.getCurrentSettings();
        addLlmScopeRows(rows, "chat", all.llm());
        addLlmScopeRows(rows, "embedding", all.embedding());
        return rows;
    }

    private static void addLlmScopeRows(List<Map<String, Object>> rows, String scopeAlias,
                                        ModelProviderConfigService.ScopeSettingsView scope) {
        if (scope == null) return;
        rows.add(entry("llm." + scopeAlias + ".active", "string", scope.activeProvider(),
                "LLM · " + scopeAlias + " 的激活 provider", "可选: " + listProviders(scope)));
        ModelProviderConfigService.ProviderConfigView active = scope.providers().stream()
                .filter(p -> p.provider().equals(scope.activeProvider())).findFirst().orElse(null);
        if (active != null) {
            rows.add(entry("llm." + scopeAlias + ".url", "string", active.apiBaseUrl(),
                    "当前 active provider 的 API 地址", null));
            rows.add(entry("llm." + scopeAlias + ".model", "string", active.model(),
                    "当前 active provider 的 model 名", null));
            rows.add(entry("llm." + scopeAlias + ".key", "secret",
                    active.maskedApiKey() == null ? "" : active.maskedApiKey(),
                    "当前 active provider 的 API key（mask）", "写入时传明文，读取永远 mask"));
        }
    }

    private static String listProviders(ModelProviderConfigService.ScopeSettingsView scope) {
        StringBuilder b = new StringBuilder();
        for (ModelProviderConfigService.ProviderConfigView p : scope.providers()) {
            if (b.length() > 0) b.append(", ");
            b.append(p.provider());
        }
        return b.toString();
    }

    static Map<String, Object> get(String key, FeatureFlagService flags,
                                   ModelProviderConfigService modelConfig) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key 必填");
        if (key.startsWith("feature_flag.")) {
            String flagKey = key.substring("feature_flag.".length());
            return entry(key, "boolean", flags.isEnabled(flagKey), "FeatureFlag · " + flagKey, null);
        }
        if (key.startsWith("llm.")) {
            return readLlmKey(key, modelConfig);
        }
        throw new IllegalArgumentException("unknown_key: " + key);
    }

    static Map<String, Object> set(String key, JsonNode rawValue, String updatedBy,
                                   FeatureFlagService flags,
                                   ModelProviderConfigService modelConfig) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key 必填");
        if (rawValue == null || rawValue.isNull()) throw new IllegalArgumentException("value 不能为空");

        if (key.startsWith("feature_flag.")) {
            String flagKey = key.substring("feature_flag.".length());
            boolean v = rawValue.asBoolean();
            flags.setEnabled(flagKey, v);
            return entry(key, "boolean", v, "FeatureFlag · " + flagKey + " 已" + (v ? "开启" : "关闭"), null);
        }
        if (key.startsWith("llm.")) {
            return writeLlmKey(key, rawValue.asText(""), updatedBy, modelConfig);
        }
        throw new IllegalArgumentException("unknown_key: " + key + "（仅支持 feature_flag.* 与 llm.*）");
    }

    private static Map<String, Object> readLlmKey(String key, ModelProviderConfigService modelConfig) {
        String[] seg = key.split("\\.", 3);
        if (seg.length < 3) throw new IllegalArgumentException("LLM key 格式: llm.<chat|embedding>.<active|url|key|model>");
        String scopeAlias = seg[1];
        String field = seg[2];
        String scope = mapScope(scopeAlias);

        ModelProviderConfigService.ModelProviderSettingsView all = modelConfig.getCurrentSettings();
        ModelProviderConfigService.ScopeSettingsView ss = "embedding".equals(scope) ? all.embedding() : all.llm();
        if (ss == null) throw new IllegalArgumentException("scope 不存在: " + scope);

        if ("active".equals(field)) {
            return entry(key, "string", ss.activeProvider(), "LLM · " + scopeAlias + " 的 active provider", null);
        }
        ModelProviderConfigService.ProviderConfigView active = ss.providers().stream()
                .filter(p -> p.provider().equals(ss.activeProvider())).findFirst()
                .orElseThrow(() -> new IllegalStateException("当前 scope 没有 active provider: " + scope));
        switch (field) {
            case "url": return entry(key, "string", active.apiBaseUrl(), null, null);
            case "model": return entry(key, "string", active.model(), null, null);
            case "key": return entry(key, "secret", active.maskedApiKey(), "API key 已 mask，写入要传明文", null);
            default: throw new IllegalArgumentException("LLM key 不支持的 field: " + field);
        }
    }

    private static Map<String, Object> writeLlmKey(String key, String value, String updatedBy,
                                                   ModelProviderConfigService modelConfig) {
        String[] seg = key.split("\\.", 3);
        if (seg.length < 3) throw new IllegalArgumentException("LLM key 格式: llm.<chat|embedding>.<active|url|key|model>");
        String scopeAlias = seg[1];
        String field = seg[2];
        String scope = mapScope(scopeAlias);

        ModelProviderConfigService.ModelProviderSettingsView all = modelConfig.getCurrentSettings();
        ModelProviderConfigService.ScopeSettingsView ss = "embedding".equals(scope) ? all.embedding() : all.llm();
        if (ss == null) throw new IllegalArgumentException("scope 不存在: " + scope);

        String activeProvider = ss.activeProvider();
        String newActive = "active".equals(field) ? value.trim() : activeProvider;

        // 把当前 active provider 的字段按需替换为新值，其他保持原样
        List<ModelProviderConfigService.ProviderUpsertRequest> upserts = new ArrayList<>();
        for (ModelProviderConfigService.ProviderConfigView p : ss.providers()) {
            boolean isTarget = p.provider().equals(activeProvider);
            String newUrl = isTarget && "url".equals(field) ? value : p.apiBaseUrl();
            String newModel = isTarget && "model".equals(field) ? value : p.model();
            String newApiKey = isTarget && "key".equals(field) ? value : null; // null = 保留原密文
            upserts.add(new ModelProviderConfigService.ProviderUpsertRequest(
                    p.provider(), newUrl, newModel, newApiKey, p.dimension(), p.enabled()));
        }
        ModelProviderConfigService.UpdateScopeRequest req = new ModelProviderConfigService.UpdateScopeRequest(
                newActive, upserts);
        modelConfig.updateScope(scope, req, updatedBy);

        // 读回最新值
        return readLlmKey(key, modelConfig);
    }

    private static String mapScope(String alias) {
        if ("chat".equals(alias) || "llm".equals(alias)) return SCOPE_LLM;
        if ("embedding".equals(alias) || "embed".equals(alias)) return SCOPE_EMBEDDING;
        throw new IllegalArgumentException("scope alias 必须是 chat / embedding，得到: " + alias);
    }

    private static Map<String, Object> entry(String key, String type, Object value, String label, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("type", type);
        m.put("value", value);
        if (label != null) m.put("label", label);
        if (hint != null) m.put("hint", hint);
        return m;
    }

    /** 用在所有 system_config_* 工具开头的权限校验。 */
    static ToolResult requireAdmin(ToolContext ctx) {
        String role = ctx.role();
        if (role == null || !role.equalsIgnoreCase("ADMIN")) {
            return ToolResult.error("forbidden",
                    "system_config_* 仅 ADMIN 角色可用，当前会话 role=" + role);
        }
        return null;
    }

    /** 给三个工具复用的 input schema：list 不要参数。 */
    static JsonNode listSchema() {
        return ToolInputSchemas.object()
                .stringProp("prefix", "可选筛选 key 前缀，例如 'feature_flag.' 或 'llm.chat.'", false)
                .additionalProperties(false)
                .build();
    }

    static JsonNode getSchema() {
        return ToolInputSchemas.object()
                .stringProp("key", "配置项 key，例如 feature_flag.data_source.tikhub / llm.chat.model", true)
                .additionalProperties(false)
                .build();
    }

    static JsonNode setSchema() {
        return ToolInputSchemas.object()
                .stringProp("key", "配置项 key，例如 feature_flag.data_source.tikhub / llm.chat.model / llm.chat.key", true)
                .anyProp("value", "新值。boolean 用 true/false，string 直接传文本", true)
                .additionalProperties(false)
                .build();
    }
}
