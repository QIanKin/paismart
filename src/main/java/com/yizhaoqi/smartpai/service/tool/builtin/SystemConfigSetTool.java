package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.agent.FeatureFlagService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * system_config_set：写一项系统配置。LLM 在会话里自配 API key / 模型 / 数据源开关靠它。
 *
 * <p>支持的 key 命名空间：
 * <ul>
 *   <li>{@code feature_flag.<flag>} — boolean，运行时数据源开关</li>
 *   <li>{@code llm.<chat|embedding>.<active|url|key|model>} — string，LLM provider 配置</li>
 * </ul>
 *
 * <p>**ADMIN-only**。所有写操作都会落审计日志（key + 操作人 userId，<b>不记录 value 原文</b>）。
 */
@Component
public class SystemConfigSetTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigSetTool.class);

    private final FeatureFlagService featureFlags;
    private final ModelProviderConfigService modelConfig;
    private final JsonNode schema;

    public SystemConfigSetTool(FeatureFlagService featureFlags, ModelProviderConfigService modelConfig) {
        this.featureFlags = featureFlags;
        this.modelConfig = modelConfig;
        this.schema = SystemConfigSupport.setSchema();
    }

    @Override public String name() { return "system_config_set"; }

    @Override public String description() {
        return "写一项系统配置（运行时立即生效）。常见用例："
                + "切 LLM 模型 → key=llm.chat.model value='claude-sonnet-4'；"
                + "更新 LLM 接口地址 → key=llm.chat.url；"
                + "更换 LLM API key → key=llm.chat.key value='sk-...'；"
                + "切换激活 provider → key=llm.chat.active value='openai'；"
                + "数据源开关 → key=feature_flag.data_source.tikhub value=true。"
                + "**仅 ADMIN 可用**。所有写操作都会被审计。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        ToolResult forbidden = SystemConfigSupport.requireAdmin(ctx);
        if (forbidden != null) return forbidden;

        String key = input.path("key").asText("");
        JsonNode value = input.path("value");

        log.info("[system_config_set] user={} key={} (value redacted)", ctx.userId(), key);
        try {
            Map<String, Object> updated = SystemConfigSupport.set(
                    key, value, "agent:" + ctx.userId(), featureFlags, modelConfig);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("updated", updated);
            out.put("operator", ctx.userId());

            String summary;
            if ("secret".equals(updated.get("type"))) {
                summary = key + " 已更新（secret 已加密落库，masked=" + updated.get("value") + "）";
            } else {
                summary = key + " 已更新为 " + updated.get("value");
            }
            return ToolResult.of(out, summary);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("bad_input", e.getMessage());
        } catch (Exception e) {
            log.warn("[system_config_set] failed key={} err={}", key, e.toString());
            return ToolResult.error("config_error", "写入失败: " + e.getMessage());
        }
    }
}
