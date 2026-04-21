package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.User;
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
 * project_roster_list：列出当前会话所属项目的博主名册。<br>
 * 如果 sessionType 是 ALLOCATION，Agent 在挑博主前默认先调一次这个工具看看已入围谁。
 */
@Component
public class ProjectRosterListTool implements Tool {

    private final ProjectCreatorService rosterService;
    private final AgentUserResolver userResolver;
    private final JsonNode schema;

    public ProjectRosterListTool(ProjectCreatorService rosterService, AgentUserResolver userResolver) {
        this.rosterService = rosterService;
        this.userResolver = userResolver;
        this.schema = ToolInputSchemas.object()
                .integerProp("projectId", "项目 id；缺省时用当前会话绑定的项目", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "project_roster_list"; }
    @Override public String description() {
        return "列出项目的博主名册（含阶段、优先级、报价）。用于『博主分配』类会话在下决策前盘点现有入围情况。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        Long projectId = RosterToolHelpers.resolveProjectId(ctx, input);
        if (projectId == null) return ToolResult.error("无法确定 projectId：会话未绑定项目，且参数未传");
        User user;
        try {
            user = userResolver.resolve(ctx.userId());
        } catch (Exception e) {
            return ToolResult.error("无法解析当前用户: " + e.getMessage());
        }
        List<ProjectCreatorService.RosterEntryView> roster;
        try {
            roster = rosterService.listRoster(projectId, user.getId());
        } catch (Exception e) {
            return ToolResult.error("拉取 roster 失败: " + e.getMessage());
        }
        List<Map<String, Object>> items = new ArrayList<>(roster.size());
        for (ProjectCreatorService.RosterEntryView v : roster) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rosterId", v.entry().getId());
            m.put("projectId", v.entry().getProjectId());
            m.put("creatorId", v.entry().getCreatorId());
            m.put("stage", v.entry().getStage() == null ? null : v.entry().getStage().name());
            m.put("priority", v.entry().getPriority());
            m.put("quotedPrice", v.entry().getQuotedPrice());
            m.put("currency", v.entry().getCurrency());
            m.put("projectNotes", v.entry().getProjectNotes());
            if (v.creator() != null) {
                m.put("creatorDisplayName", v.creator().getDisplayName());
                m.put("personaTags", v.creator().getPersonaTagsJson());
                m.put("trackTags", v.creator().getTrackTagsJson());
                m.put("priceNote", v.creator().getPriceNote());
                m.put("cooperationStatus", v.creator().getCooperationStatus());
            }
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("total", items.size());
        out.put("items", items);
        return ToolResult.of(out, String.format("project #%d 当前 roster %d 个博主", projectId, items.size()));
    }
}
