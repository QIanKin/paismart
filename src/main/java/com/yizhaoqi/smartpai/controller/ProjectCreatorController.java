package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.agent.ProjectCreator;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.agent.ProjectCreatorService;
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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 项目博主名册 REST 入口，挂在项目子路径下：{@code /api/v1/agent/projects/{projectId}/creators}。
 */
@RestController
@RequestMapping("/api/v1/agent/projects/{projectId}/creators")
public class ProjectCreatorController {

    private final ProjectCreatorService service;
    private final AgentUserResolver userResolver;
    private final JwtUtils jwtUtils;

    public ProjectCreatorController(ProjectCreatorService service,
                                    AgentUserResolver userResolver,
                                    JwtUtils jwtUtils) {
        this.service = service;
        this.userResolver = userResolver;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String auth,
                                  @PathVariable Long projectId) {
        User user = resolveUser(auth);
        List<ProjectCreatorService.RosterEntryView> roster = service.listRoster(projectId, user.getId());
        return ok(roster);
    }

    @PostMapping
    public ResponseEntity<?> addOne(@RequestHeader("Authorization") String auth,
                                    @PathVariable Long projectId,
                                    @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        Long creatorId = asLong(body.get("creatorId"));
        if (creatorId == null) return bad("creatorId 不能为空");
        ProjectCreator entry = service.addToRoster(
                projectId, user.getId(), creatorId,
                parseStage(body.get("stage")),
                asInt(body.get("priority")),
                asDecimal(body.get("quotedPrice")),
                asString(body.get("currency")),
                asLong(body.get("assignedToUserId")),
                asString(body.get("projectNotes")),
                String.valueOf(user.getId())
        );
        return ok(entry);
    }

    /**
     * 批量加：一次把搜索框里勾选的 N 个博主塞进 roster。
     * 支持 {@code creatorIds}（人 id） 或 {@code accountIds}（账号 id）。
     *
     * <p>路径提供两种写法，前端/Agent 按自己习惯挑：
     * <ul>
     *   <li><code>POST /api/v1/agent/projects/{pid}/creators/batch</code>（推荐，保险）</li>
     *   <li><code>POST /api/v1/agent/projects/{pid}/creators:batch</code>（Google API 风格，历史兼容）</li>
     * </ul>
     * 之前只挂 <code>:batch</code>，在 Spring 6 的 PathPatternParser 下偶发会 404（颜色段匹配歧义）。
     * 同时挂两条路径，彻底避坑。
     */
    @PostMapping(value = {"/batch", ":batch"})
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> addBatch(@RequestHeader("Authorization") String auth,
                                      @PathVariable Long projectId,
                                      @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        Object rawCreator = body.get("creatorIds");
        Object rawAccount = body.get("accountIds");
        ProjectCreator.Stage stage = parseStage(body.get("stage"));
        String addedBy = String.valueOf(user.getId());

        List<ProjectCreator> saved = new ArrayList<>();
        if (rawAccount instanceof List<?> accList && !accList.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (Object o : accList) {
                Long v = asLong(o);
                if (v != null) ids.add(v);
            }
            saved.addAll(service.addAccountsBatch(projectId, user.getId(), ids, stage, addedBy));
        }
        if (rawCreator instanceof List<?> creList && !creList.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (Object o : creList) {
                Long v = asLong(o);
                if (v != null) ids.add(v);
            }
            saved.addAll(service.addBatch(projectId, user.getId(), ids, stage, addedBy));
        }
        if (saved.isEmpty() && rawCreator == null && rawAccount == null) {
            return bad("creatorIds 或 accountIds 必须提供其一");
        }
        return ok(saved);
    }

    @PutMapping("/{rosterId}")
    public ResponseEntity<?> update(@RequestHeader("Authorization") String auth,
                                    @PathVariable Long projectId,
                                    @PathVariable Long rosterId,
                                    @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        String customJson = null;
        if (body.containsKey("customFields")) {
            Object cf = body.get("customFields");
            if (cf == null || (cf instanceof Map<?, ?> m && m.isEmpty())) {
                customJson = ""; // 空串 = 清空
            } else {
                try { customJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cf); }
                catch (Exception e) { return bad("customFields JSON 序列化失败: " + e.getMessage()); }
            }
        }
        ProjectCreator entry = service.updateEntry(
                rosterId, user.getId(),
                parseStage(body.get("stage")),
                asInt(body.get("priority")),
                asDecimal(body.get("quotedPrice")),
                asString(body.get("currency")),
                asLong(body.get("assignedToUserId")),
                asString(body.get("projectNotes")),
                customJson
        );
        return ok(entry);
    }

    @PutMapping("/{rosterId}/stage")
    public ResponseEntity<?> moveStage(@RequestHeader("Authorization") String auth,
                                       @PathVariable Long projectId,
                                       @PathVariable Long rosterId,
                                       @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        ProjectCreator.Stage stage = parseStage(body.get("stage"));
        if (stage == null) return bad("stage 不能为空");
        return ok(service.updateStage(rosterId, user.getId(), stage));
    }

    @DeleteMapping("/{rosterId}")
    public ResponseEntity<?> remove(@RequestHeader("Authorization") String auth,
                                    @PathVariable Long projectId,
                                    @PathVariable Long rosterId) {
        User user = resolveUser(auth);
        service.remove(rosterId, user.getId());
        return ok(Map.of("removed", true));
    }

    private ProjectCreator.Stage parseStage(Object v) {
        if (v == null) return null;
        try {
            return ProjectCreator.Stage.valueOf(String.valueOf(v).toUpperCase());
        } catch (Exception e) {
            return null;
        }
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

    private ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", msg));
    }

    private String asString(Object v) { return v == null ? null : String.valueOf(v); }

    private Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private BigDecimal asDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
