package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.agent.AgentMessage;
import com.yizhaoqi.smartpai.model.agent.ChatSession;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.agent.ChatSessionService;
import com.yizhaoqi.smartpai.service.agent.memory.MessageStore;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话级 REST 入口。
 *  - list/create/rename/archive/list messages
 *  - 拉消息用于前端刷新或"从其他入口进入"还原
 */
@RestController
@RequestMapping("/api/v1/agent/sessions")
public class ChatSessionController {

    private final ChatSessionService sessionService;
    private final MessageStore messageStore;
    private final AgentUserResolver userResolver;
    private final JwtUtils jwtUtils;
    private final ObjectMapper mapper;

    public ChatSessionController(ChatSessionService sessionService,
                                 MessageStore messageStore,
                                 AgentUserResolver userResolver,
                                 JwtUtils jwtUtils,
                                 ObjectMapper mapper) {
        this.sessionService = sessionService;
        this.messageStore = messageStore;
        this.userResolver = userResolver;
        this.jwtUtils = jwtUtils;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String auth,
                                  @RequestParam(required = false) Long projectId,
                                  @RequestParam(required = false) String sessionType,
                                  @RequestParam(required = false) Long creatorId,
                                  @RequestParam(required = false, defaultValue = "50") int limit) {
        User user = resolveUser(auth);
        List<ChatSession> sessions;
        if (projectId != null && creatorId != null) {
            sessions = sessionService.listForProjectAndCreator(projectId, creatorId).stream()
                    .filter(s -> s.getUserId().equals(user.getId())).toList();
        } else if (projectId != null && sessionType != null) {
            ChatSession.SessionType type = parseType(sessionType);
            sessions = sessionService.listForProjectAndType(projectId, type).stream()
                    .filter(s -> s.getUserId().equals(user.getId())).toList();
        } else if (projectId != null) {
            sessions = sessionService.listForProject(projectId).stream()
                    .filter(s -> s.getUserId().equals(user.getId())).toList();
        } else {
            sessions = sessionService.listForUser(user.getId(), limit);
        }
        return ok(sessions);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader("Authorization") String auth,
                                    @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        Long projectId = asLong(body.get("projectId"));
        String title = asString(body.get("title"));
        ChatSession.SessionType type = body.containsKey("sessionType")
                ? parseType(asString(body.get("sessionType")))
                : ChatSession.SessionType.GENERAL;
        Long creatorId = asLong(body.get("creatorId"));
        return ok(sessionService.createSession(user.getId(), user.getPrimaryOrg(),
                projectId, title, type, creatorId));
    }

    private ChatSession.SessionType parseType(String raw) {
        if (raw == null || raw.isBlank()) return ChatSession.SessionType.GENERAL;
        try {
            return ChatSession.SessionType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return ChatSession.SessionType.GENERAL;
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@RequestHeader("Authorization") String auth,
                                 @PathVariable Long id) {
        User user = resolveUser(auth);
        return ok(sessionService.getOwned(id, user.getId()));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<?> listMessages(@RequestHeader("Authorization") String auth,
                                          @PathVariable Long id) {
        User user = resolveUser(auth);
        sessionService.getOwned(id, user.getId()); // 权限校验
        List<AgentMessage> all = messageStore.readAll(id);
        return ok(all);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> rename(@RequestHeader("Authorization") String auth,
                                    @PathVariable Long id,
                                    @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        String title = asString(body.get("title"));
        return ok(sessionService.rename(id, user.getId(), title));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> archive(@RequestHeader("Authorization") String auth,
                                     @PathVariable Long id) {
        User user = resolveUser(auth);
        sessionService.archive(id, user.getId());
        return ok(Map.of("archived", true));
    }

    private User resolveUser(String auth) {
        String token = auth == null ? "" : auth.replace("Bearer ", "");
        String userId = jwtUtils.extractUserIdFromToken(token);
        if (userId == null) throw new IllegalArgumentException("无效 token");
        return userResolver.resolve(userId);
    }

    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", data));
    }

    private String asString(Object v) { return v == null ? null : String.valueOf(v); }

    private Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
