package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.config.SkillProperties;
import com.yizhaoqi.smartpai.repository.agent.SkillRepository;
import com.yizhaoqi.smartpai.service.skill.SkillLoader;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让 Agent 能把可复用经验沉淀成 SKILL.md，并立即 reload 到 skill registry。
 * 这是 PaiSmart 自迭代能力的第一步：先允许新增/更新 skill 指引，再由 use_skill + bash/业务工具执行。
 */
@Component
public class SkillUpsertTool implements Tool {

    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final String NAME_PATTERN = "^[a-z][a-z0-9_-]{1,63}$";

    private final SkillProperties properties;
    private final SkillLoader loader;
    private final SkillRepository skillRepository;
    private final JsonNode schema;

    public SkillUpsertTool(SkillProperties properties, SkillLoader loader, SkillRepository skillRepository) {
        this.properties = properties;
        this.loader = loader;
        this.skillRepository = skillRepository;
        this.schema = ToolInputSchemas.object()
                .stringProp("name", "skill 名。使用小写字母/数字/下划线/短横线，例：xhs-note-audit", true)
                .stringProp("description", "一句话说明这个 skill 何时应该被使用", true)
                .stringProp("body", "SKILL.md 正文，不要包含 YAML front-matter；写清触发条件、步骤、注意事项", true)
                .stringProp("version", "版本号，默认 0.1.0", false)
                .booleanProp("overwrite", "同名 skill 已存在时是否覆盖，默认 true", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "skill_upsert"; }

    @Override public String description() {
        return "创建或更新一个 Agent skill，并立即重新加载。适合把新流程、新工具用法、排障经验沉淀为可复用能力。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        if (!properties.isEnabled()) {
            return PermissionResult.deny("skills.enabled=false，skill 子系统未启用");
        }
        String name = input.path("name").asText("");
        if (!name.matches(NAME_PATTERN)) {
            return PermissionResult.deny("skill name 只能使用小写字母、数字、下划线、短横线，且必须以小写字母开头");
        }
        String body = input.path("body").asText("");
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return PermissionResult.deny("skill body 超过 256KB，请拆分或精简");
        }
        if (properties.getRoots() == null || properties.getRoots().isEmpty()) {
            return PermissionResult.deny("skills.roots 未配置，无法写入 skill");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String name = input.path("name").asText().trim();
        String description = input.path("description").asText("").trim();
        String body = input.path("body").asText("").trim();
        String version = input.path("version").asText("0.1.0").trim();
        boolean overwrite = !input.has("overwrite") || input.get("overwrite").asBoolean(true);

        Path root = Paths.get(properties.getRoots().get(0)).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path skillDir = root.resolve(name).normalize();
        if (!skillDir.startsWith(root)) {
            return ToolResult.error("path_denied", "skill 目录越界: " + skillDir);
        }
        Path skillFile = skillDir.resolve("SKILL.md");
        if (Files.exists(skillFile) && !overwrite) {
            return ToolResult.error("already_exists", "skill 已存在，如需覆盖请传 overwrite=true: " + name);
        }

        Files.createDirectories(skillDir);
        String content = buildSkillMd(name, description, version, body);
        Files.writeString(skillFile, content, StandardCharsets.UTF_8);
        SkillLoader.ReloadResult reload = loader.reloadNow();
        boolean reenabled = skillRepository.findByNameAndOwnerOrgTagIsNull(name)
                .filter(skill -> !Boolean.TRUE.equals(skill.getEnabled()))
                .map(skill -> loader.setEnabled(skill.getId(), true).isPresent())
                .orElse(false);
        if (reenabled) {
            reload = loader.reloadNow();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("description", description);
        data.put("path", skillFile.toString());
        data.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
        data.put("reload", Map.of(
                "scanned", reload.scanned(),
                "added", reload.added(),
                "updated", reload.updated(),
                "activeCount", reload.activeCount(),
                "errors", reload.errors(),
                "reenabled", reenabled
        ));
        return ToolResult.of(data, "已写入并加载 skill: " + name, Map.of("skill", name));
    }

    static String buildSkillMd(String name, String description, String version, String body) {
        return "---\n"
                + "name: " + yamlSingleQuoted(name) + "\n"
                + "description: " + yamlSingleQuoted(description == null ? "" : description) + "\n"
                + "version: " + yamlSingleQuoted(version == null || version.isBlank() ? "0.1.0" : version) + "\n"
                + "---\n\n"
                + "# " + name + "\n\n"
                + (body == null ? "" : body.strip()) + "\n";
    }

    private static String yamlSingleQuoted(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }
}
