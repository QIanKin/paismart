package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.agent.ScheduledSkillTask;
import com.yizhaoqi.smartpai.repository.agent.ScheduledSkillTaskRepository;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * schedule_create：让 agent 把一个 skill 脚本排到周期任务里。
 * 例："每周一 09:00 跑 xhs-trend skill 抓小红书赛道" → 一次工具调用就能排上。
 *
 * 约束：
 *  - skill 名必须已注册；
 *  - cron 必须能被 Spring {@link CronExpression} 解析（5 或 6 段）；
 *  - 每个 orgTag 最多 50 个任务（防错排爆表）；
 *  - 自动绑定当前 ToolContext 的 orgTag 和 projectId —— 跨租户隔离交给 service 层处理。
 */
@Component
public class ScheduleCreateTool implements Tool {

    private static final int MAX_JOBS_PER_ORG = 50;

    private final ScheduledSkillTaskRepository repository;
    private final SkillRegistry skillRegistry;
    private final JsonNode schema;

    public ScheduleCreateTool(ScheduledSkillTaskRepository repository, SkillRegistry skillRegistry) {
        this.repository = repository;
        this.skillRegistry = skillRegistry;
        this.schema = ToolInputSchemas.object()
                .stringProp("name", "任务名（同一 org 下唯一，例如 'daily-xhs-track-refresh'）", true)
                .stringProp("skill", "要执行的 skill 名（必须已注册）", true)
                .stringProp("entrypoint", "skill 下 scripts/ 中的入口脚本（可选，多数 skill 只有一个）", false)
                .stringProp("cron", "Spring cron 表达式（5 或 6 段），例 '0 9 * * MON' = 每周一 09:00", true)
                .stringProp("paramsJson", "传给 entrypoint 的参数 JSON 数组（可选）", false)
                .enumProp("outputMode", "产出模式",
                        java.util.List.of("summary", "raw", "none"), false)
                .booleanProp("bindToProject", "是否绑定到当前 project（默认 true）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "schedule_create"; }
    @Override public String description() {
        return "为当前租户排一个周期性 skill 任务（cron 驱动）。适合：每天刷新博主数据、每周刷新赛道热榜、"
                + "非工作时间跑爬虫。任务会自动持久化并在 ScheduledSkillRunner 里轮询。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String name = input.path("name").asText("").trim();
        String skill = input.path("skill").asText("").trim();
        String cron = input.path("cron").asText("").trim();
        if (name.isEmpty() || skill.isEmpty() || cron.isEmpty()) {
            return ToolResult.error("name / skill / cron 均为必填");
        }
        String orgTag = ctx.orgTag() == null ? "" : ctx.orgTag();

        // skill 是否存在
        if (skillRegistry.find(skill, orgTag).isEmpty() && skillRegistry.find(skill, null).isEmpty()) {
            return ToolResult.error("未找到 skill: " + skill + "（请先用 list_skills 查看可用 skill）");
        }

        // cron 合法性
        CronExpression expr;
        try {
            expr = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("cron 不合法: " + e.getMessage());
        }

        // 额度
        long existing = repository.findByOrgTagOrderByIdDesc(orgTag).size();
        if (existing >= MAX_JOBS_PER_ORG) {
            return ToolResult.error("本租户定时任务已达上限 " + MAX_JOBS_PER_ORG + "，请先删除旧任务");
        }

        // 唯一性
        if (repository.findByNameAndOrgTag(name, orgTag).isPresent()) {
            return ToolResult.error("任务名已存在: " + name + "（orgTag=" + orgTag + "）");
        }

        ScheduledSkillTask t = new ScheduledSkillTask();
        t.setName(name);
        t.setSkillName(skill);
        if (input.hasNonNull("entrypoint")) t.setEntrypoint(input.get("entrypoint").asText(null));
        t.setCron(cron);
        if (input.hasNonNull("paramsJson")) {
            JsonNode p = input.get("paramsJson");
            t.setParamsJson(p.isTextual() ? p.asText() : p.toString());
        }
        if (input.hasNonNull("outputMode")) t.setOutputMode(input.get("outputMode").asText("summary"));
        t.setEnabled(true);
        t.setOrgTag(orgTag.isEmpty() ? null : orgTag);
        boolean bindProject = !input.hasNonNull("bindToProject") || input.get("bindToProject").asBoolean(true);
        if (bindProject && ctx.projectId() != null) {
            try { t.setProjectId(Long.parseLong(ctx.projectId())); } catch (NumberFormatException ignored) {}
        }
        LocalDateTime nextRun = LocalDateTime.now().plusSeconds(1);
        LocalDateTime computed = expr.next(nextRun);
        t.setNextRunAt(computed);

        ScheduledSkillTask saved = repository.save(t);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", saved.getId());
        data.put("name", saved.getName());
        data.put("skill", saved.getSkillName());
        data.put("cron", saved.getCron());
        data.put("nextRunAt", saved.getNextRunAt() == null ? null : saved.getNextRunAt().toString());
        data.put("orgTag", saved.getOrgTag());
        data.put("projectId", saved.getProjectId());

        return ToolResult.of(data, "排期已创建 #" + saved.getId()
                + " → 下次触发：" + (computed == null ? "-" : computed));
    }
}
