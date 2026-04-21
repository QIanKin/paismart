package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.skill.LoadedSkill;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出当前租户可见的 skills（不含 body，避免灌爆 context）。
 * Agent 一般在任务开始时先调 list_skills → 决定是否 use_skill 读正文 → 再 bash 执行脚本。
 */
@Component
public class ListSkillsTool implements Tool {

    private final SkillRegistry registry;
    private final JsonNode schema;

    public ListSkillsTool(SkillRegistry registry) {
        this.registry = registry;
        this.schema = ToolInputSchemas.object()
                .stringProp("filter", "可选的名称关键字，忽略大小写包含匹配", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "list_skills"; }
    @Override public String description() {
        return "列出当前企业/用户可用的 skill。每个 skill 含 name/description/scripts/requiredBins。"
                + "发现相关 skill 后，用 use_skill 读取详细指引，再用 bash 执行脚本。";
    }
    @Override public JsonNode inputSchema() { return schema; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String filter = input.has("filter") ? input.get("filter").asText("").trim().toLowerCase() : "";
        List<LoadedSkill> all = registry.listVisible(ctx.orgTag());
        List<Map<String, Object>> out = new ArrayList<>();
        for (LoadedSkill s : all) {
            if (!filter.isEmpty()) {
                String text = (s.name() + " " + (s.description() == null ? "" : s.description())).toLowerCase();
                if (!text.contains(filter)) continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", s.name());
            row.put("description", s.description());
            row.put("scripts", s.scriptsOrEmpty());
            row.put("requiredBins", s.requiredBins());
            out.add(row);
        }
        String summary = "共 " + out.size() + " 个 skill" + (filter.isEmpty() ? "" : " (filter=" + filter + ")");
        return ToolResult.of(Map.of("skills", out), summary);
    }
}
