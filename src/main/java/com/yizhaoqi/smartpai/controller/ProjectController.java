package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.agent.Project;
import com.yizhaoqi.smartpai.model.agent.ProjectTemplate;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.agent.ProjectService;
import com.yizhaoqi.smartpai.service.agent.ProjectTemplateService;
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

import java.util.List;
import java.util.Map;

/**
 * 项目与模板 REST 入口。
 * 所有写操作都严格按 userId（= 数据库 user.id）隔离；org 级可见 project 仅靠 service 层二次校验。
 */
@RestController
@RequestMapping("/api/v1/agent/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectTemplateService templateService;
    private final AgentUserResolver userResolver;
    private final JwtUtils jwtUtils;
    private final ObjectMapper mapper;

    public ProjectController(ProjectService projectService,
                             ProjectTemplateService templateService,
                             AgentUserResolver userResolver,
                             JwtUtils jwtUtils,
                             ObjectMapper mapper) {
        this.projectService = projectService;
        this.templateService = templateService;
        this.userResolver = userResolver;
        this.jwtUtils = jwtUtils;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String auth) {
        User user = resolveUser(auth);
        List<Project> own = projectService.listForOwner(user.getId());
        return ok(own);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@RequestHeader("Authorization") String auth,
                                 @PathVariable Long id) {
        User user = resolveUser(auth);
        return ok(projectService.getOwned(id, user.getId()));
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> create(@RequestHeader("Authorization") String auth,
                                    @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        String name = asString(body.get("name"));
        String desc = asString(body.get("description"));
        String systemPrompt = asString(body.get("systemPrompt"));
        List<String> enabledTools = asStringList(body.get("enabledTools"));
        List<String> enabledSkills = asStringList(body.get("enabledSkills"));
        Map<String, Object> customFields = body.get("customFields") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        return ok(projectService.create(user.getId(), user.getPrimaryOrg(), name, desc, systemPrompt,
                enabledTools, enabledSkills, customFields));
    }

    @PostMapping("/from-template")
    public ResponseEntity<?> createFromTemplate(@RequestHeader("Authorization") String auth,
                                                @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        String code = asString(body.get("templateCode"));
        String name = asString(body.get("name"));
        if (code == null) return bad("templateCode 不能为空");
        return ok(projectService.createFromTemplate(user.getId(), user.getPrimaryOrg(), code, name));
    }

    @PutMapping("/{id}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> update(@RequestHeader("Authorization") String auth,
                                    @PathVariable Long id,
                                    @RequestBody Map<String, Object> body) {
        User user = resolveUser(auth);
        String name = asString(body.get("name"));
        String desc = asString(body.get("description"));
        String systemPrompt = asString(body.get("systemPrompt"));
        List<String> enabledTools = body.containsKey("enabledTools") ? asStringList(body.get("enabledTools")) : null;
        List<String> enabledSkills = body.containsKey("enabledSkills") ? asStringList(body.get("enabledSkills")) : null;
        Map<String, Object> customFields = body.containsKey("customFields")
                ? (body.get("customFields") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of())
                : null;
        return ok(projectService.update(id, user.getId(), name, desc, systemPrompt,
                enabledTools, enabledSkills, customFields));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> archive(@RequestHeader("Authorization") String auth,
                                     @PathVariable Long id) {
        User user = resolveUser(auth);
        projectService.archive(id, user.getId());
        return ok(Map.of("archived", true));
    }

    @GetMapping("/templates")
    public ResponseEntity<?> listTemplates(@RequestHeader("Authorization") String auth) {
        User user = resolveUser(auth);
        List<ProjectTemplate> tpls = templateService.listVisible(user.getPrimaryOrg());
        return ok(tpls);
    }

    // ---- helpers ----
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

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object v) {
        if (v == null) return null;
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        try {
            return mapper.readValue(String.valueOf(v), List.class);
        } catch (Exception e) {
            return null;
        }
    }
}
