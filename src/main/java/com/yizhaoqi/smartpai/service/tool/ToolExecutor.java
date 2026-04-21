package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行器：把"权限 -> 输入解析 -> 实际 call -> 异常兜底 -> 计时/日志"统一收敛。
 * Agent 主循环只和本类打交道，不直接碰 Tool.call()。
 */
@Component
public class ToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 执行单个工具。任何异常都会被捕获并封装成 ToolResult.error，绝不向上抛，
     * 因为 LLM 需要从"工具失败"中自愈。
     */
    public ToolExecution execute(Tool tool, JsonNode rawInput, ToolContext ctx) {
        long start = System.currentTimeMillis();
        JsonNode input = rawInput == null ? NullNode.getInstance() : rawInput;

        try {
            PermissionResult perm = tool.checkPermission(ctx, input);
            if (perm instanceof PermissionResult.Deny deny) {
                logger.warn("Tool [{}] 权限拒绝: user={}, reason={}",
                        tool.name(), ctx.userId(), deny.reason());
                ToolResult res = ToolResult.error("permission_denied: " + deny.reason());
                return new ToolExecution(tool, input, res, System.currentTimeMillis() - start);
            }
            if (perm instanceof PermissionResult.Allow allow && allow.rewrittenInput() != null) {
                input = allow.rewrittenInput();
            }

            if (ctx.isCancelled()) {
                return new ToolExecution(tool, input,
                        ToolResult.error("cancelled_by_user"),
                        System.currentTimeMillis() - start);
            }

            ToolResult res = tool.call(ctx, input);
            if (res == null) {
                res = ToolResult.text("(tool returned null)");
            }
            long cost = System.currentTimeMillis() - start;
            logger.info("Tool [{}] 完成 cost={}ms isError={} summary={}",
                    tool.name(), cost, res.isError(), res.summary());
            return new ToolExecution(tool, input, res, cost);
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - start;
            logger.error("Tool [{}] 执行失败 cost={}ms user={}", tool.name(), cost, ctx.userId(), ex);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("exception", ex.getClass().getSimpleName());
            ToolResult res = ToolResult.error(safeMessage(ex), extra);
            return new ToolExecution(tool, input, res, cost);
        }
    }

    /**
     * 把 ToolResult.data 序列化为字符串，便于以 OpenAI tool message 形式回灌给 LLM。
     */
    public String resultToLlmPayload(ToolResult result) {
        if (result == null) {
            return "";
        }
        try {
            Object data = result.data();
            if (data == null) {
                return "";
            }
            if (data instanceof String s) {
                return s;
            }
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            logger.warn("序列化 ToolResult.data 失败", e);
            return String.valueOf(result.data());
        }
    }

    private String safeMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        return ex.getClass().getSimpleName();
    }

    public ObjectNode describeForWs(ToolExecution exec) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tool", exec.tool().name());
        node.put("toolUseId", exec.contextSnapshotToolUseId());
        node.set("input", exec.input());
        node.put("isError", exec.result().isError());
        node.put("summary", exec.result().summary());
        node.put("durationMs", exec.durationMs());
        return node;
    }

    public record ToolExecution(Tool tool, JsonNode input, ToolResult result, long durationMs) {
        public String contextSnapshotToolUseId() {
            // 由调用者在构造 ToolContext 时注入 toolUseId；这里只是保留字段给 describeForWs 用。
            return null;
        }
    }
}
