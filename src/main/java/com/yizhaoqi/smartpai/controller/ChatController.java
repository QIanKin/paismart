package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.handler.ChatWebSocketHandler;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.Map;

@Component
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController extends TextWebSocketHandler {

    private final ChatHandler chatHandler;
    private final JwtUtils jwtUtils;

    public ChatController(ChatHandler chatHandler, JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userMessage = message.getPayload();
        String userId = session.getId(); // Use session ID as userId for simplicity
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("WEBSOCKET_CHAT");
        try {
            LogUtils.logChat(userId, session.getId(), "USER_MESSAGE", userMessage.length());
            LogUtils.logBusiness("WEBSOCKET_CHAT", userId, "处理WebSocket聊天消息: messageLength=%d", userMessage.length());
            
        chatHandler.processMessage(userId, userMessage, session);
            
            LogUtils.logUserOperation(userId, "WEBSOCKET_CHAT", "message_processing", "SUCCESS");
            monitor.end("WebSocket消息处理成功");
        } catch (Exception e) {
            LogUtils.logBusinessError("WEBSOCKET_CHAT", userId, "WebSocket消息处理失败", e);
            monitor.end("WebSocket消息处理失败: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * 获取WebSocket停止指令Token
     */
    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken(@RequestHeader("Authorization") String token) {
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(errorBody(401, "Invalid token"));
            }
            String jwtToken = token.replace("Bearer ", "");
            if (!jwtUtils.validateToken(jwtToken)) {
                return ResponseEntity.status(401).body(errorBody(401, "Invalid token"));
            }

            String cmdToken = ChatWebSocketHandler.getInternalCmdToken();
            if (cmdToken == null || cmdToken.trim().isEmpty()) {
                return ResponseEntity.status(500).body(errorBody(500, "Token生成失败"));
            }

            return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取WebSocket停止指令Token成功",
                "data", Map.of("cmdToken", cmdToken)
            ));

        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TOKEN", "system", "获取WebSocket Token失败", e);
            return ResponseEntity.status(500).body(errorBody(500, "服务器内部错误：" + e.getMessage()));
        }
    }

    /**
     * 构造允许 {@code data=null} 的响应体。
     * <p>{@link Map#of} 不允许 value 为 null，直接用会在 401/500 分支里再 NPE 一次，
     * 客户端收到的就是栈追踪而不是约定的 JSON。
     */
    private static Map<String, Object> errorBody(int code, String message) {
        Map<String, Object> body = new HashMap<>(3);
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        return body;
    }
}
