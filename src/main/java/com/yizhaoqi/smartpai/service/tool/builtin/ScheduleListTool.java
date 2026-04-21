package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.agent.ScheduledSkillTask;
import com.yizhaoqi.smartpai.repository.agent.ScheduledSkillTaskRepository;
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
 * schedule_list：列出本租户（及当前 project）的所有定时 skill 任务。
 */
@Component
public class ScheduleListTool implements Tool {

    private final ScheduledSkillTaskRepository repository;
    private final JsonNode schema;

    public ScheduleListTool(ScheduledSkillTaskRepository repository) {
        this.repository = repository;
        this.schema = ToolInputSchemas.object()
                .stringProp("scope", "'org'（本租户全部）或 'project'（当前项目）；默认 org", false)
                .booleanProp("onlyEnabled", "仅返回 enabled=true，默认 false", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "schedule_list"; }
    @Override public String description() {
        return "列出本租户的定时 skill 任务（cron + 状态 + 上次/下次运行时间）。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String scope = input.has("scope") ? input.get("scope").asText("org") : "org";
        boolean onlyEnabled = input.has("onlyEnabled") && input.get("onlyEnabled").asBoolean(false);
        String orgTag = ctx.orgTag();

        List<ScheduledSkillTask> tasks;
        if ("project".equalsIgnoreCase(scope) && ctx.projectId() != null) {
            Long pid;
            try { pid = Long.parseLong(ctx.projectId()); }
            catch (NumberFormatException e) { return ToolResult.error("当前 projectId 不是数字"); }
            tasks = repository.findByProjectIdOrderByIdDesc(pid);
        } else {
            tasks = repository.findByOrgTagOrderByIdDesc(orgTag == null ? null : orgTag);
        }

        List<Map<String, Object>> rows = new ArrayList<>(tasks.size());
        for (ScheduledSkillTask t : tasks) {
            if (onlyEnabled && !Boolean.TRUE.equals(t.getEnabled())) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", t.getId());
            r.put("name", t.getName());
            r.put("skill", t.getSkillName());
            r.put("cron", t.getCron());
            r.put("enabled", t.getEnabled());
            r.put("lastStatus", t.getLastStatus() == null ? null : t.getLastStatus().name());
            r.put("lastRunAt", t.getLastRunAt() == null ? null : t.getLastRunAt().toString());
            r.put("nextRunAt", t.getNextRunAt() == null ? null : t.getNextRunAt().toString());
            r.put("projectId", t.getProjectId());
            rows.add(r);
        }

        Map<String, Object> data = Map.of(
                "scope", scope,
                "orgTag", orgTag,
                "projectId", ctx.projectId(),
                "total", rows.size(),
                "tasks", rows
        );
        return ToolResult.of(data, "schedule_list scope=" + scope + " → " + rows.size() + " 条");
    }
}
