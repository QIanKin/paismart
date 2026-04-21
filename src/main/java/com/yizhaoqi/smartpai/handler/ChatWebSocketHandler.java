package com.yizhaoqi.smartpai.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.agent.AgentMessageDispatcher;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String HEARTBEAT_PING = "__chat_ping__";
    private static final String HEARTBEAT_PONG = "__chat_pong__";

    // ConcurrentWebSocketSessionDecorator 的两个阈值：
    //  - SEND_TIME_LIMIT_MS：单条消息发送超时（超过则关闭），流式场景给宽一点
    //  - SEND_BUFFER_SIZE_LIMIT：排队中的 buffer 上限（字节），超过就关闭，防止 LLM 回答太快把堆打爆
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 1024 * 1024; // 1 MiB

    private final ChatHandler chatHandler;
    private final AgentMessageDispatcher agentDispatcher;
    // 存放"加锁后的"会话，所有写操作统一走它，避免 chat-monitor 线程和 http-nio 线程同时写 session
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    // userId → 原生 session，用于需要读 uri / 关闭连接等场景
    private final ConcurrentHashMap<String, WebSocketSession> rawSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtUtils jwtUtils;
    
    // 内部指令令牌 - 可以从配置文件读取
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    public ChatWebSocketHandler(ChatHandler chatHandler,
                                AgentMessageDispatcher agentDispatcher,
                                JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.agentDispatcher = agentDispatcher;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String jwtToken;
        try {
            jwtToken = extractToken(session);
            if (!jwtUtils.validateToken(jwtToken)) {
                logger.debug("拒绝无效WebSocket连接，会话ID: {}", session.getId());
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
        } catch (Exception exception) {
            logger.warn("拒绝非法WebSocket连接，会话ID: {}, 原因: {}", session.getId(), exception.getMessage());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception closeException) {
                logger.error("关闭无效WebSocket连接失败: {}", closeException.getMessage(), closeException);
            }
            return;
        }

        String userId = extractUserId(jwtToken);
        // 用 ConcurrentWebSocketSessionDecorator 把写操作串行化，
        // 这样 chat-monitor 线程（流式推字）和 http-nio 线程（心跳 pong / 错误帧）
        // 并发 sendMessage 不会再触发 Tomcat 的 TEXT_PARTIAL_WRITING 状态机异常。
        WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        sessions.put(userId, wrapped);
        rawSessions.put(userId, session);
        logger.info("WebSocket连接已建立，用户ID: {}，会话ID: {}，URI路径: {}",
                userId, session.getId(), session.getUri().getPath());

        // 发送会话ID到前端（走 wrapped）
        try {
            Map<String, String> connectionMessage = Map.of(
                "type", "connection",
                "sessionId", session.getId(),
                "message", "WebSocket连接已建立"
            );
            String jsonMessage = objectMapper.writeValueAsString(connectionMessage);
            wrapped.sendMessage(new TextMessage(jsonMessage));
            logger.info("已发送会话ID到前端: sessionId={}", session.getId());
        } catch (Exception e) {
            logger.error("发送会话ID失败: {}", e.getMessage(), e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(extractToken(session));
        // 永远用 wrapped session 做写操作，防止并发写同一条 WebSocket
        WebSocketSession out = sessions.getOrDefault(userId, session);
        try {
            String payload = message.getPayload();

            // 心跳消息只用于保活连接，不进入聊天处理链路。
            if (HEARTBEAT_PING.equals(payload)) {
                out.sendMessage(new TextMessage(HEARTBEAT_PONG));
                return;
            }

            logger.info("接收到消息，用户ID: {}，会话ID: {}，消息长度: {}", 
                       userId, session.getId(), payload.length());
            
            // 检查是否是JSON格式的系统指令
            if (payload.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMessage = objectMapper.readValue(payload, Map.class);
                    String messageType = (String) jsonMessage.get("type");
                    String internalToken = (String) jsonMessage.get("_internal_cmd_token");
                    
                    // 只有包含正确内部令牌的停止指令才处理
                    if ("stop".equals(messageType) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
                        logger.info("收到有效的停止按钮指令，用户ID: {}，会话ID: {}", userId, session.getId());
                        agentDispatcher.stop(userId, out);
                        chatHandler.stopResponse(userId, out); // 兼容：同时通知旧 RAG 流停
                        return;
                    }
                    
                    // JSON 消息里 type=chat 的 payload.content 作为用户消息内容；
                    // 支持可选的 sessionId / projectId（Phase 2 多会话多项目支持）
                    if ("chat".equals(messageType) && jsonMessage.get("content") instanceof String content) {
                        String sId = jsonMessage.get("sessionId") == null ? null : String.valueOf(jsonMessage.get("sessionId"));
                        String pId = jsonMessage.get("projectId") == null ? null : String.valueOf(jsonMessage.get("projectId"));
                        agentDispatcher.dispatch(userId, content, sId, pId, out);
                        return;
                    }
                    
                    logger.debug("收到未识别 JSON 消息，降级为纯文本处理");
                } catch (Exception jsonParseError) {
                    logger.debug("JSON解析失败，当作普通消息处理: {}", jsonParseError.getMessage());
                }
            }
            
            // 默认把整条 payload 当 user content 投给 Agent
            agentDispatcher.dispatch(userId, payload, out);
            
        } catch (Exception e) {
            logger.error("处理消息出错，用户ID: {}，会话ID: {}，错误: {}", 
                        userId, session.getId(), e.getMessage(), e);
            sendErrorMessage(out, "消息处理失败：" + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = "unknown";
        try {
            userId = extractUserId(extractToken(session));
            sessions.remove(userId);
            rawSessions.remove(userId);
        } catch (Exception e) {
            logger.debug("关闭连接时无法解析用户信息，会话ID: {}", session.getId());
        }

        if (CloseStatus.POLICY_VIOLATION.equals(status)) {
            logger.debug("WebSocket连接因策略校验失败被关闭，用户ID: {}，会话ID: {}，状态: {}",
                    userId, session.getId(), status);
        } else {
            logger.info("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}",
                    userId, session.getId(), status);
        }

        // 清理会话的引用映射
        chatHandler.clearSessionReferenceMapping(session.getId());
    }

    private String extractUserId(String jwtToken) {
        String userId = jwtUtils.extractUserIdFromToken(jwtToken);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("无法从JWT令牌中提取用户ID");
        }

        logger.debug("从JWT令牌中提取的用户ID: {}", userId);
        return userId;
    }

    private String extractToken(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getPath() == null) {
            throw new IllegalArgumentException("WebSocket URI is missing");
        }
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        return segments[segments.length - 1];
    }

    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        try {
            Map<String, String> error = Map.of("error", errorMessage);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            logger.info("已发送错误消息到会话: {}, 错误: {}", session.getId(), errorMessage);
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取内部指令令牌 - 供前端调用
     */
    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }
} 
