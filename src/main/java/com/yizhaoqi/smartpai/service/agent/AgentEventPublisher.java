package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolProgress;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 端 Agent 事件的统一发射器。所有向前端推送的 Agent 事件（tool_call / chunk /
 * plan / todo / tool_result / completion / error / ask_user / rate_limit ...）都从这里出。
 *
 * 设计动机：
 * - 把"向哪个 session 写 / 信封结构 / 序列化 / 容错"收敛到一处；
 * - 前端协议一旦升级，只改这里，不必在 Agent Runtime、各 tool、handler 里到处改 JSON；
 * - 对 WebSocketSession 写操作必须是同步锁保护——Spring WS session 不是线程安全的，
 *   AgentRuntime 的回调可能被 reactor 线程和 tool 线程同时命中。
 */
@Component
public class AgentEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(AgentEventPublisher.class);

    private final ObjectMapper objectMapper;

    public AgentEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 生命周期类事件 ====================

    public void publishStart(WebSocketSession session, String messageId) {
        send(session, AgentEventType.START, messageId, Map.of());
    }

    public void publishCompletion(WebSocketSession session, String messageId,
                                  String finishReason, String finalContent) {
        send(session, AgentEventType.COMPLETION, messageId,
                Map.of("finishReason", finishReason == null ? "stop" : finishReason,
                        "content", finalContent == null ? "" : finalContent));
    }

    public void publishStopped(WebSocketSession session, String messageId) {
        send(session, AgentEventType.STOPPED, messageId, Map.of());
    }

    public void publishError(WebSocketSession session, String messageId, String error) {
        send(session, AgentEventType.ERROR, messageId, Map.of("message", error == null ? "" : error));
    }

    public void publishRateLimit(WebSocketSession session, String messageId,
                                 String message, long retryAfterSeconds) {
        send(session, AgentEventType.RATE_LIMIT, messageId,
                Map.of("message", message == null ? "" : message, "retryAfterSeconds", retryAfterSeconds));
    }

    // ==================== LLM 输出类事件 ====================

    public void publishChunk(WebSocketSession session, String messageId, String delta) {
        if (delta == null || delta.isEmpty()) return;
        send(session, AgentEventType.CHUNK, messageId, Map.of("delta", delta));
    }

    public void publishPlan(WebSocketSession session, String messageId, String text) {
        send(session, AgentEventType.PLAN, messageId, Map.of("text", text == null ? "" : text));
    }

    public void publishTodo(WebSocketSession session, String messageId, Object todos) {
        send(session, AgentEventType.TODO, messageId, Map.of("todos", todos == null ? List.of() : todos));
    }

    public void publishStepStart(WebSocketSession session, String messageId, int stepNo) {
        send(session, AgentEventType.STEP_START, messageId, Map.of("step", stepNo));
    }

    public void publishStepEnd(WebSocketSession session, String messageId, int stepNo) {
        send(session, AgentEventType.STEP_END, messageId, Map.of("step", stepNo));
    }

    // ==================== 工具类事件 ====================

    public void publishToolCall(WebSocketSession session, String messageId, String toolUseId,
                                Tool tool, JsonNode input) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("toolUseId", toolUseId);
        data.put("tool", tool.name());
        data.put("userFacingName", tool.userFacingName(input));
        data.put("readOnly", tool.isReadOnly(input));
        data.set("input", input);
        String summary = tool.summarizeInvocation(input);
        if (summary != null) data.put("summary", summary);
        send(session, AgentEventType.TOOL_CALL, messageId, data);
    }

    public void publishToolProgress(WebSocketSession session, String messageId, ToolProgress progress) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("toolUseId", progress.toolUseId());
        data.put("progressType", progress.type());
        data.put("message", progress.message() == null ? "" : progress.message());
        if (progress.data() != null) data.set("payload", objectMapper.valueToTree(progress.data()));
        send(session, AgentEventType.TOOL_PROGRESS, messageId, data);
    }

    public void publishToolResult(WebSocketSession session, String messageId, String toolUseId,
                                  String toolName, ToolResult result, long durationMs) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("toolUseId", toolUseId);
        data.put("tool", toolName);
        data.put("isError", result.isError());
        data.put("summary", result.summary() == null ? "" : result.summary());
        data.put("durationMs", durationMs);
        // UI 预览：如果 data 是字符串或小对象就直接回显；大对象只回前若干字符摘要。
        try {
            String preview = previewOfData(result.data());
            data.put("preview", preview);
        } catch (Exception ignored) {
            data.put("preview", "");
        }
        if (result.meta() != null && !result.meta().isEmpty()) {
            data.set("meta", objectMapper.valueToTree(result.meta()));
        }
        send(session, AgentEventType.TOOL_RESULT, messageId, data);
    }

    public void publishAskUser(WebSocketSession session, String messageId,
                               String question, List<String> options) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("question", question == null ? "" : question);
        if (options != null) {
            data.set("options", objectMapper.valueToTree(options));
        }
        send(session, AgentEventType.ASK_USER, messageId, data);
    }

    // ==================== 底层 ====================

    private String previewOfData(Object data) throws Exception {
        if (data == null) return "";
        String json;
        if (data instanceof String s) {
            json = s;
        } else {
            json = objectMapper.writeValueAsString(data);
        }
        if (json.length() <= 1024) return json;
        return json.substring(0, 1024) + "…";
    }

    private void send(WebSocketSession session, String type, String messageId, Object data) {
        if (session == null || !session.isOpen()) return;
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", type);
        envelope.put("ts", System.currentTimeMillis());
        if (messageId != null) envelope.put("messageId", messageId);
        envelope.put("sessionId", session.getId());
        if (data instanceof JsonNode node) {
            envelope.set("data", node);
        } else {
            envelope.set("data", objectMapper.valueToTree(data));
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            logger.error("序列化 agent 事件失败 type={}, err={}", type, ex.getMessage(), ex);
            return;
        }
        synchronized (session) {
            if (!session.isOpen()) return;
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (Exception ex) {
                logger.warn("推送 agent 事件失败 type={} sessionId={} err={}",
                        type, session.getId(), ex.getMessage());
            }
        }
    }
}
