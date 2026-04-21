package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.BrowserSkillRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * qiangua_brand_discover：在千瓜上按品牌名发现相关达人。
 *
 * <p>底层走 skill {@code qiangua-brand-discover}（浏览器 CDP 连业务员本机）。
 */
@Component
public class QianguaBrandDiscoverTool implements Tool {

    private final BrowserSkillRunner runner;
    private final JsonNode schema;

    public QianguaBrandDiscoverTool(BrowserSkillRunner runner) {
        this.runner = runner;
        this.schema = ToolInputSchemas.object()
                .stringProp("brandName", "品牌名（中英皆可，如 'Dior'）", true)
                .integerProp("maxKols", "期望抓的达人数（默认 30）", false)
                .booleanProp("includePageText",
                        "是否在输出里附带整页文本供 LLM 兜底分析（默认 true）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "qiangua_brand_discover"; }

    @Override public String description() {
        return "在千瓜（qian-gua.com）按品牌名查询相关小红书达人。需要业务员本机 Chrome 已登录千瓜。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文缺少 orgTag");
        String brandName = input.path("brandName").asText("");
        if (brandName.isBlank()) return ToolResult.error("brandName 必填");

        int maxKols = Math.min(100, Math.max(1, input.path("maxKols").asInt(30)));
        boolean includePageText = !input.hasNonNull("includePageText")
                || input.get("includePageText").asBoolean(true);

        BrowserSkillRunner.RunRequest req = new BrowserSkillRunner.RunRequest();
        req.orgTag = orgTag;
        req.sessionId = ctx.sessionId();
        req.skillName = "qiangua-brand-discover";
        req.scriptRelative = "scripts/search_qiangua.mjs";
        req.extraArgs.add("--brand-name");
        req.extraArgs.add(brandName);
        req.extraArgs.add("--max-kols");
        req.extraArgs.add(String.valueOf(maxKols));
        req.extraArgs.add("--include-page-text");
        req.extraArgs.add(String.valueOf(includePageText));
        req.timeoutSeconds = 120;
        req.cancelled = ctx.cancelled();

        BrowserSkillRunner.RunResult res = runner.run(req);
        if (!res.ok()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errorType", res.errorType());
            return ToolResult.error("qiangua-brand-discover 执行失败: " + res.errorMessage(), detail);
        }
        JsonNode payload = res.payload();
        int kolCount = payload.path("kols").size();
        return ToolResult.of(payload,
                String.format("千瓜 · %s 抓到 %d 个相关达人", brandName, kolCount));
    }
}
