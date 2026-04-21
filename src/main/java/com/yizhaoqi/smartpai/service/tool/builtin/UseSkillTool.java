package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.skill.LoadedSkill;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 取某 skill 的详细 markdown 指引 + 脚本清单。
 *
 * 设计点：本 tool 的返回会被塞进 tool message 走进下一轮 LLM，LLM 在下一步把指引作为临时 system-like 指令使用。
 * 和"把所有 skill 正文塞进 system prompt"相比：
 *  - 节省 token（按需加载）
 *  - 更贴近 claude-code 的 progressive disclosure 模型
 */
@Component
public class UseSkillTool implements Tool {

    private final SkillRegistry registry;
    private final JsonNode schema;

    public UseSkillTool(SkillRegistry registry) {
        this.registry = registry;
        this.schema = ToolInputSchemas.object()
                .stringProp("name", "skill 名（见 list_skills 返回）", true)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "use_skill"; }
    @Override public String description() {
        return "加载某个 skill 的详细指引（SKILL.md 正文）。在需要专门领域知识或外部 CLI 操作时先调用本工具。";
    }
    @Override public JsonNode inputSchema() { return schema; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String name = input.path("name").asText(null);
        if (name == null || name.isBlank()) return ToolResult.error("name 不能为空");

        LoadedSkill s = registry.find(name, ctx.orgTag()).orElse(null);
        if (s == null) return ToolResult.error("skill 不存在或不可见: " + name);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", s.name());
        data.put("description", s.description());
        data.put("version", s.version());
        data.put("requiredBins", s.requiredBins());
        data.put("scripts", s.scriptsOrEmpty());
        data.put("rootPath", s.rootPath());
        data.put("instructions", s.bodyMd());

        return ToolResult.of(data,
                "加载 skill 「" + s.name() + "」指引 (" + (s.bodyMd() == null ? 0 : s.bodyMd().length()) + " chars)",
                Map.of("skill", s.name()));
    }
}
