package com.yizhaoqi.smartpai.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.xhs.XhsLoginSession;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.xhs.XhsLoginSessionService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /ws/xhs-login/{token}?session={sessionId}} 的 WS 处理器。
 *
 * <p>流程：
 * <ol>
 *   <li>握手时从 URL 里拿 JWT，做 {@link JwtUtils#validateToken}</li>
 *   <li>读 query 参数 session=，验 session 归属（org + 用户匹配）</li>
 *   <li>{@link XhsLoginSessionService#subscribe} 订阅事件，事件来一个推一个 JSON 帧</li>
 *   <li>连接关闭 → 反订阅；若用户 WS 断了但进程还活着，node 脚本仍会写 DB</li>
 * </ol>
 *
 * <p>客户端可发送的指令（可选，都是 JSON 一行）：
 * <pre>
 *   {"type":"cancel"}   // 取消当前登录
 *   {"type":"ping"}     // 心跳，服务端回 {"type":"pong"}
 * </pre>
 */
@Component
public class XhsLoginWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(XhsLoginWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 256 * 1024;
    private static final String SESSION_ATTR = "xhsLoginSessionId";
    private static final String USER_ATTR = "xhsLoginUserId";
    private static final String LISTENER_ATTR = "xhsLoginListener";

    private final JwtUtils jwtUtils;
    private final AgentUserResolver userResolver;
    private final XhsLoginSessionService loginService;
    private final ObjectMapper mapper = new ObjectMapper();

    public XhsLoginWebSocketHandler(JwtUtils jwtUtils,
                                    AgentUserResolver userResolver,
                                    XhsLoginSessionService loginService) {
        this.jwtUtils = jwtUtils;
        this.userResolver = userResolver;
        this.loginService = loginService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String token;
        String sessionId;
        try {
            token = extractToken(session);
            sessionId = extractSessionId(session);
            if (!jwtUtils.validateToken(token)) {
                safeClose(session, CloseStatus.POLICY_VIOLATION.withReason("invalid token"));
                return;
            }
        } catch (Exception e) {
            log.warn("xhs-login WS 握手失败: {}", e.getMessage());
            safeClose(session, CloseStatus.POLICY_VIOLATION.withReason("handshake failed"));
            return;
        }

        String userId = jwtUtils.extractUserIdFromToken(token);
        if (userId == null || userId.isBlank()) {
            safeClose(session, CloseStatus.POLICY_VIOLATION.withReason("no user"));
            return;
        }
        User user;
        try {
            user = userResolver.resolve(userId);
        } catch (Exception e) {
            safeClose(session, CloseStatus.POLICY_VIOLATION.withReason("user not found"));
            return;
        }

        Optional<XhsLoginSession> found = loginService.find(sessionId);
        if (found.isEmpty() || !user.getPrimaryOrg().equals(found.get().getOwnerOrgTag())) {
            safeClose(session, CloseStatus.POLICY_VIOLATION.withReason("session not found or forbidden"));
            return;
        }

        WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        session.getAttributes().put(SESSION_ATTR, sessionId);
        session.getAttributes().put(USER_ATTR, String.valueOf(user.getId()));

        XhsLoginSessionService.LoginEventListener listener = (type, payload) -> {
            if (!wrapped.isOpen()) return;
            try {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("type", type);
                frame.put("payload", payload);
                wrapped.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
            } catch (Exception ex) {
                log.debug("xhs-login WS 发送失败 type={} err={}", type, ex.getMessage());
            }
        };
        session.getAttributes().put(LISTENER_ATTR, listener);
        loginService.subscribe(sessionId, listener);
        log.info("xhs-login WS 建立 user={} session={}", user.getId(), sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = (String) session.getAttributes().get(SESSION_ATTR);
        if (sessionId == null) return;
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cmd = mapper.readValue(payload, Map.class);
            Object rawType = cmd == null ? null : cmd.get("type");
            String type = rawType == null ? "" : String.valueOf(rawType);
            switch (type) {
                case "cancel" -> loginService.cancel(sessionId, "cancelled via WS");
                case "ping" -> session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
                default -> log.debug("忽略 xhs-login WS 指令 type={}", type);
            }
        } catch (Exception e) {
            log.debug("xhs-login WS 消息解析失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = (String) session.getAttributes().get(SESSION_ATTR);
        XhsLoginSessionService.LoginEventListener l =
                (XhsLoginSessionService.LoginEventListener) session.getAttributes().get(LISTENER_ATTR);
        if (sessionId != null && l != null) {
            loginService.unsubscribe(sessionId, l);
        }
        log.info("xhs-login WS 关闭 session={} status={}", sessionId, status);
    }

    // ---------- helpers ----------

    private String extractToken(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) throw new IllegalArgumentException("URI 为空");
        String path = uri.getPath();
        String[] seg = path.split("/");
        if (seg.length == 0) throw new IllegalArgumentException("path 无效");
        return seg[seg.length - 1];
    }

    private String extractSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            throw new IllegalArgumentException("缺 session 查询参数");
        }
        for (String kv : uri.getQuery().split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq);
            if ("session".equals(k)) {
                return kv.substring(eq + 1);
            }
        }
        throw new IllegalArgumentException("缺 session 查询参数");
    }

    private void safeClose(WebSocketSession s, CloseStatus cs) {
        try { s.close(cs); } catch (Exception ignored) {}
    }
}
