package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.agent.ProjectCreator;
import com.yizhaoqi.smartpai.service.tool.ToolContext;

/**
 * 项目名册相关工具共享的小工具方法。集中放在这里避免各 tool 重复。
 */
final class RosterToolHelpers {

    private RosterToolHelpers() {}

    static Long resolveProjectId(ToolContext ctx, JsonNode input) {
        if (input != null && input.hasNonNull("projectId")) {
            long v = input.get("projectId").asLong(-1);
            if (v > 0) return v;
        }
        String ctxPid = ctx.projectId();
        if (ctxPid != null && !ctxPid.isBlank()) {
            try { return Long.parseLong(ctxPid); } catch (Exception ignored) {}
        }
        return null;
    }

    static ProjectCreator.Stage parseStage(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            return ProjectCreator.Stage.valueOf(node.asText().trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}
