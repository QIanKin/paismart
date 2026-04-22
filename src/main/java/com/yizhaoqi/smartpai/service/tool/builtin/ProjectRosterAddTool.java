package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.agent.ProjectCreator;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.agent.ProjectCreatorService;
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
 * project_roster_add：把一批博主加入到当前项目的名册，并指定阶段。<br>
 * 场景：ALLOCATION 会话 AI 决定入围博主后，由用户「确认入围」，AI 调用这个工具持久化。
 */
@Component
public class ProjectRosterAddTool implements Tool {

    private final ProjectCreatorService rosterService;
    private final AgentUserResolver userResolver;
    private final JsonNode schema;

    public ProjectRosterAddTool(ProjectCreatorService rosterService, AgentUserResolver userResolver) {
        this.rosterService = rosterService;
        this.userResolver = userResolver;
        ObjectNode intItem = ToolInputSchemas.mapper().createObjectNode();
        intItem.put("type", "integer");
        this.schema = ToolInputSchemas.object()
                .integerProp("projectId", "项目 id；缺省时用当前会话绑定的项目", false)
                .arrayProp("creatorIds", "要加入名册的 Creator（人）id 列表。与 accountIds 至少提供一个。", intItem, false)
                .arrayProp("accountIds", "要加入名册的 CreatorAccount（账号）id 列表。"
                        + "如果账号没绑定 Creator，系统会即时造一个 Creator 并绑定。与 creatorIds 至少提供一个。",
                        intItem, false)
                .stringProp("stage", "阶段：CANDIDATE / SHORTLISTED / LOCKED / SIGNED / PUBLISHED / SETTLED / DROPPED，默认 SHORTLISTED", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "project_roster_add"; }
    @Override public String description() {
        return "把一批博主按指定阶段加入项目名册（已存在则合并/更新阶段）。默认 SHORTLISTED。"
                + "推荐用 accountIds（来自 creator_search 返回的 CreatorAccount.id），"
                + "系统会自动处理 account ↔ creator 绑定；如果你已经有 Creator（人）id 也可以用 creatorIds。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        Long projectId = RosterToolHelpers.resolveProjectId(ctx, input);
        if (projectId == null) return ToolResult.error("无法确定 projectId");

        List<Long> creatorIds = pickIds(input.path("creatorIds"));
        List<Long> accountIds = pickIds(input.path("accountIds"));
        if (creatorIds.isEmpty() && accountIds.isEmpty()) {
            return ToolResult.error("creatorIds 或 accountIds 至少提供一个非空列表");
        }
        ProjectCreator.Stage stage = RosterToolHelpers.parseStage(input.get("stage"));
        if (stage == null) stage = ProjectCreator.Stage.SHORTLISTED;

        User user;
        try { user = userResolver.resolve(ctx.userId()); }
        catch (Exception e) { return ToolResult.error("无法解析当前用户: " + e.getMessage()); }

        List<ProjectCreator> saved = new ArrayList<>();
        try {
            if (!accountIds.isEmpty()) {
                saved.addAll(rosterService.addAccountsBatch(projectId, user.getId(), accountIds, stage, ctx.userId()));
            }
            if (!creatorIds.isEmpty()) {
                saved.addAll(rosterService.addBatch(projectId, user.getId(), creatorIds, stage, ctx.userId()));
            }
        } catch (Exception e) {
            return ToolResult.error("加入失败: " + e.getMessage());
        }
        List<Map<String, Object>> rows = new ArrayList<>(saved.size());
        for (ProjectCreator pc : saved) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rosterId", pc.getId());
            m.put("creatorId", pc.getCreatorId());
            m.put("stage", pc.getStage() == null ? null : pc.getStage().name());
            m.put("priority", pc.getPriority());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("accepted", rows.size());
        out.put("requested", creatorIds.size() + accountIds.size());
        out.put("items", rows);
        return ToolResult.of(out, String.format("向 project #%d 加入 %d 个博主（stage=%s）",
                projectId, rows.size(), stage.name()));
    }

    private static List<Long> pickIds(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return new ArrayList<>();
        List<Long> ids = new ArrayList<>(arr.size());
        for (JsonNode node : arr) {
            long v = node.asLong(-1);
            if (v > 0) ids.add(v);
        }
        return ids;
    }
}
