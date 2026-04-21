package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.agent.Project;
import com.yizhaoqi.smartpai.model.agent.ProjectTemplate;
import com.yizhaoqi.smartpai.repository.agent.ProjectRepository;
import com.yizhaoqi.smartpai.repository.agent.ProjectTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ProjectService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final ProjectTemplateRepository templateRepository;
    private final ObjectMapper mapper;

    public ProjectService(ProjectRepository projectRepository,
                          ProjectTemplateRepository templateRepository,
                          ObjectMapper mapper) {
        this.projectRepository = projectRepository;
        this.templateRepository = templateRepository;
        this.mapper = mapper;
    }

    public List<Project> listForOwner(Long userId) {
        return projectRepository.findByOwnerUserIdAndStatusOrderByUpdatedAtDesc(userId, Project.Status.active);
    }

    public List<Project> listForOrg(String orgTag) {
        return projectRepository.findByOrgTagAndStatusOrderByUpdatedAtDesc(orgTag, Project.Status.active);
    }

    public Project getOwned(Long id, Long userId) {
        return projectRepository.findByIdAndOwnerUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在或无权限"));
    }

    @Transactional
    public Project create(Long userId, String orgTag, String name, String description,
                          String systemPrompt, List<String> enabledTools, List<String> enabledSkills) {
        return create(userId, orgTag, name, description, systemPrompt, enabledTools, enabledSkills, null);
    }

    @Transactional
    public Project create(Long userId, String orgTag, String name, String description,
                          String systemPrompt, List<String> enabledTools, List<String> enabledSkills,
                          java.util.Map<String, Object> customFields) {
        Project p = new Project();
        p.setOwnerUserId(userId);
        p.setOrgTag(orgTag);
        p.setName(name == null || name.isBlank() ? "新项目" : name);
        p.setDescription(description);
        p.setSystemPrompt(systemPrompt);
        p.setEnabledToolsJson(writeJson(enabledTools));
        p.setEnabledSkillsJson(writeJson(enabledSkills));
        p.setCustomFieldsJson(customFields == null || customFields.isEmpty() ? null : writeJson(customFields));
        p.setStatus(Project.Status.active);
        return projectRepository.save(p);
    }

    @Transactional
    public Project createFromTemplate(Long userId, String orgTag, String templateCode,
                                      String nameOverride) {
        ProjectTemplate tpl = templateRepository.findByCode(templateCode)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + templateCode));
        if (!Boolean.TRUE.equals(tpl.getEnabled())) {
            throw new IllegalArgumentException("模板已禁用: " + templateCode);
        }
        if (tpl.getOwnerOrgTag() != null && !tpl.getOwnerOrgTag().equals(orgTag)) {
            throw new IllegalArgumentException("无权使用该模板");
        }
        Project p = new Project();
        p.setOwnerUserId(userId);
        p.setOrgTag(orgTag);
        p.setName(nameOverride == null || nameOverride.isBlank() ? tpl.getName() : nameOverride);
        p.setDescription(tpl.getDescription());
        p.setSystemPrompt(tpl.getSystemPrompt());
        p.setEnabledToolsJson(tpl.getEnabledToolsJson());
        p.setEnabledSkillsJson(tpl.getEnabledSkillsJson());
        p.setTemplateCode(templateCode);
        p.setStatus(Project.Status.active);
        return projectRepository.save(p);
    }

    @Transactional
    public Project update(Long id, Long userId, String name, String description, String systemPrompt,
                          List<String> enabledTools, List<String> enabledSkills) {
        return update(id, userId, name, description, systemPrompt, enabledTools, enabledSkills, null);
    }

    /** 扩展版：customFields 显式传 null 表示"不变"，传 empty map 表示"清空"。 */
    @Transactional
    public Project update(Long id, Long userId, String name, String description, String systemPrompt,
                          List<String> enabledTools, List<String> enabledSkills,
                          java.util.Map<String, Object> customFields) {
        Project p = getOwned(id, userId);
        if (name != null) p.setName(name);
        if (description != null) p.setDescription(description);
        if (systemPrompt != null) p.setSystemPrompt(systemPrompt);
        if (enabledTools != null) p.setEnabledToolsJson(writeJson(enabledTools));
        if (enabledSkills != null) p.setEnabledSkillsJson(writeJson(enabledSkills));
        if (customFields != null) {
            p.setCustomFieldsJson(customFields.isEmpty() ? null : writeJson(customFields));
        }
        return projectRepository.save(p);
    }

    @Transactional
    public void archive(Long id, Long userId) {
        Project p = getOwned(id, userId);
        p.setStatus(Project.Status.archived);
        projectRepository.save(p);
    }

    public List<String> parseEnabledTools(Project p) {
        return parseList(p == null ? null : p.getEnabledToolsJson());
    }

    public List<String> parseEnabledSkills(Project p) {
        return parseList(p == null ? null : p.getEnabledSkillsJson());
    }

    private String writeJson(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            logger.warn("JSON 序列化失败", e);
            return null;
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            logger.warn("enabledToolsJson 反序列化失败 err={}", e.getMessage());
            return List.of();
        }
    }
}
