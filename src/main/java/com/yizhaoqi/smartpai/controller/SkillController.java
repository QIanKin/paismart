package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.agent.ScheduledSkillTask;
import com.yizhaoqi.smartpai.model.agent.Skill;
import com.yizhaoqi.smartpai.repository.agent.ScheduledSkillTaskRepository;
import com.yizhaoqi.smartpai.repository.agent.SkillRepository;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.skill.LoadedSkill;
import com.yizhaoqi.smartpai.service.skill.SkillLoader;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 子系统 REST 入口：
 *  - GET  /api/v1/agent/skills                列出可见 skill（只返回 manifest，不返回 body）
 *  - GET  /api/v1/agent/skills/{name}         取详情（含 markdown body）
 *  - POST /api/v1/agent/skills/reload         触发一次磁盘扫描 + 热重载
 *  - PUT  /api/v1/agent/skills/{id}/enabled   启用/禁用某个 skill
 *  - GET  /api/v1/agent/skills/tasks          列出定时任务
 *  - POST /api/v1/agent/skills/tasks          新增/更新定时任务
 *  - DELETE /api/v1/agent/skills/tasks/{id}   删除定时任务
 */
@RestController
@RequestMapping("/api/v1/agent/skills")
public class SkillController {

    private final SkillRegistry registry;
    private final SkillLoader loader;
    private final SkillRepository skillRepository;
    private final ScheduledSkillTaskRepository taskRepository;
    private final AgentUserResolver userResolver;
    private final JwtUtils jwtUtils;
    private final ObjectMapper mapper;

    public SkillController(SkillRegistry registry,
                           SkillLoader loader,
                           SkillRepository skillRepository,
                           ScheduledSkillTaskRepository taskRepository,
                           AgentUserResolver userResolver,
                           JwtUtils jwtUtils,
                           ObjectMapper mapper) {
        this.registry = registry;
        this.loader = loader;
        this.skillRepository = skillRepository;
        this.taskRepository = taskRepository;
        this.userResolver = userResolver;
        this.jwtUtils = jwtUtils;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String auth) {
        User u = resolveUser(auth);
        List<LoadedSkill> skills = registry.listVisible(u.getPrimaryOrg());
        List<Map<String, Object>> data = skills.stream().map(LoadedSkill::toManifest).toList();
        return ok(data);
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> detail(@RequestHeader("Authorization") String auth,
                                    @PathVariable String name) {
        User u = resolveUser(auth);
        return registry.find(name, u.getPrimaryOrg())
                .map(s -> {
                    Map<String, Object> body = new LinkedHashMap<>(s.toManifest());
                    body.put("instructions", s.bodyMd());
                    return ok(body);
                })
                .orElseGet(() -> notFound("skill not found: " + name));
    }

    @PostMapping("/reload")
    public ResponseEntity<?> reload(@RequestHeader("Authorization") String auth) {
        resolveUser(auth);
        SkillLoader.ReloadResult r = loader.reloadNow();
        return ok(r);
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@RequestHeader("Authorization") String auth,
                                        @PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        resolveUser(auth);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled")) || "true".equals(String.valueOf(body.get("enabled")));
        return loader.setEnabled(id, enabled)
                .map(s -> ok(Map.of("id", s.getId(), "name", s.getName(), "enabled", s.getEnabled())))
                .orElseGet(() -> notFound("skill id not found: " + id));
    }

    // ---- scheduled tasks ----

    @GetMapping("/tasks")
    public ResponseEntity<?> listTasks(@RequestHeader("Authorization") String auth) {
        resolveUser(auth);
        return ok(taskRepository.findAll());
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> saveTask(@RequestHeader("Authorization") String auth,
                                      @RequestBody Map<String, Object> body) {
        User u = resolveUser(auth);
        ScheduledSkillTask t;
        if (body.get("id") != null) {
            Long id = asLong(body.get("id"));
            t = taskRepository.findById(id).orElseGet(ScheduledSkillTask::new);
        } else {
            t = new ScheduledSkillTask();
        }
        t.setName(String.valueOf(body.getOrDefault("name", t.getName())));
        t.setSkillName(String.valueOf(body.getOrDefault("skillName", t.getSkillName())));
        t.setEntrypoint(asString(body.get("entrypoint")));
        t.setCron(String.valueOf(body.getOrDefault("cron", t.getCron() == null ? "0 0 * * * *" : t.getCron())));
        t.setParamsJson(asString(body.get("paramsJson")));
        t.setOutputMode(asString(body.getOrDefault("outputMode", "summary")));
        t.setOrgTag(asString(body.getOrDefault("orgTag", u.getPrimaryOrg())));
        t.setProjectId(asLong(body.get("projectId")));
        t.setEnabled(body.get("enabled") == null ? Boolean.TRUE : Boolean.valueOf(String.valueOf(body.get("enabled"))));
        t.setNextRunAt(null);
        return ok(taskRepository.save(t));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<?> deleteTask(@RequestHeader("Authorization") String auth,
                                        @PathVariable Long id) {
        resolveUser(auth);
        taskRepository.deleteById(id);
        return ok(Map.of("deleted", true));
    }

    // ---- helpers ----
    private User resolveUser(String auth) {
        String token = auth == null ? "" : auth.replace("Bearer ", "");
        String userId = jwtUtils.extractUserIdFromToken(token);
        if (userId == null) throw new IllegalArgumentException("无效 token");
        return userResolver.resolve(userId);
    }

    private ResponseEntity<Object> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", data));
    }

    private ResponseEntity<Object> notFound(String msg) {
        return ResponseEntity.status(404).body(Map.of("code", 404, "message", msg));
    }

    private String asString(Object v) { return v == null ? null : String.valueOf(v); }

    private Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
