package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 主动向用户发起结构化反问。常用于：
 *  - 关键信息不足（例如博主要搜哪个平台、要导出哪些字段）；
 *  - 任务存在多种执行路径，需要用户做选择。
 *
 * 机制：工具只把问题发到前端（触发 ask_user WS 事件），Agent 当前轮继续 plan/回答，
 * 用户点答案时由前端作为下一轮 user message 发回——和普通输入一样进 Agent。
 *
 * 这个工具本身立即返回一个"已提问"的占位，让 LLM 知道"别再等了，下一步等用户"。
 */
@Component
public class AskUserQuestionTool implements Tool {

    private final JsonNode schema;

    public AskUserQuestionTool() {
        this.schema = ToolInputSchemas.object()
                .stringProp("question", "要问用户的问题，中文，不超过 200 字", true)
                .arrayProp("options", "候选选项（可选）。若提供，前端会渲染成按钮。",
                        com.yizhaoqi.smartpai.service.tool.ToolInputSchemas.stringType(), false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "ask_user_question"; }

    @Override public String description() {
        return "当关键信息不足或任务有多条可行路径时，用本工具向用户发起反问；"
                + "可以提供 options 让用户一键作答。不要在信息已经明确时滥用。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override public String userFacingName(JsonNode input) { return "向用户提问"; }

    @Override public String summarizeInvocation(JsonNode input) {
        return input == null ? "ask_user_question" : "ask_user: " + input.path("question").asText("");
    }

    @Override public ToolResult call(ToolContext ctx, JsonNode input) {
        String question = input.path("question").asText("").trim();
        if (question.isEmpty()) return ToolResult.error("question 不能为空");

        List<String> options = new ArrayList<>();
        JsonNode optsNode = input.path("options");
        if (optsNode.isArray()) {
            for (Iterator<JsonNode> it = optsNode.elements(); it.hasNext(); ) {
                String s = it.next().asText(null);
                if (s != null && !s.isBlank()) options.add(s);
            }
        }

        // 借用 progress 通道把问题推给 UI；AgentEventPublisher 会把它转成 ask_user WS 事件。
        // （ToolContext.emitProgress 默认走 tool_progress 事件；Runtime 发 tool_result 时前端已有完整 payload）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("options", options);
        ctx.emitProgress("data", "ask_user", payload);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("asked", true);
        data.put("question", question);
        data.put("options", options);
        data.put("note", "已向用户发起提问，请等待用户的下一条消息作为答复；不要再继续调用其它工具。");
        return ToolResult.of(data, "已提问：" + question);
    }
}
