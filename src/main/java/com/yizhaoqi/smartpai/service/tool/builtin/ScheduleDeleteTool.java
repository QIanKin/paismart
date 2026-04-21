package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.agent.ScheduledSkillTask;
import com.yizhaoqi.smartpai.repository.agent.ScheduledSkillTaskRepository;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * schedule_delete：禁用或彻底删除一个定时任务。默认 softDelete=true（只 enabled=false）。
 * 为了防止 agent 误删生产排期，真正的 hard delete 要显式传 hardDelete=true 且仅当它属于当前 org/project 时允许。
 */
@Component
public class ScheduleDeleteTool implements Tool {

    private final ScheduledSkillTaskRepository repository;
    private final JsonNode schema;

    public ScheduleDeleteTool(ScheduledSkillTaskRepository repository) {
        this.repository = repository;
        this.schema = ToolInputSchemas.object()
                .integerProp("id", "task id（来自 schedule_list）", true)
                .booleanProp("hardDelete", "是否真删除。默认 false = 仅标记 enabled=false", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "schedule_delete"; }
    @Override public String description() {
        return "禁用（默认）或硬删除一个定时 skill 任务。建议先用 schedule_list 查 id。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }
    @Override public boolean isDestructive(JsonNode input) {
        return input != null && input.has("hardDelete") && input.get("hardDelete").asBoolean(false);
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        Long id = input.has("id") ? input.get("id").asLong(-1L) : -1L;
        if (id <= 0) return ToolResult.error("id 必填");
        boolean hard = input.has("hardDelete") && input.get("hardDelete").asBoolean(false);

        Optional<ScheduledSkillTask> opt = repository.findById(id);
        if (opt.isEmpty()) return ToolResult.error("任务不存在: " + id);
        ScheduledSkillTask t = opt.get();

        // 跨租户隔离：任务 orgTag 必须与当前 ctx.orgTag 一致（null==null 也 OK）
        String ownOrg = t.getOrgTag();
        String curOrg = ctx.orgTag();
        boolean sameOrg = (ownOrg == null && curOrg == null) || (ownOrg != null && ownOrg.equals(curOrg));
        if (!sameOrg) return ToolResult.error("任务 #" + id + " 属于其他租户，无法操作");

        if (hard) {
            repository.deleteById(id);
            return ToolResult.of(Map.of("id", id, "hardDeleted", true),
                    "已硬删除定时任务 #" + id);
        } else {
            t.setEnabled(false);
            repository.save(t);
            return ToolResult.of(Map.of("id", id, "enabled", false),
                    "已禁用定时任务 #" + id + "（保留记录）");
        }
    }
}
