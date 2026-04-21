package com.yizhaoqi.smartpai.service.agent.context.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.agent.Project;
import com.yizhaoqi.smartpai.repository.agent.ProjectRepository;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.service.agent.context.ContextContribution;
import com.yizhaoqi.smartpai.service.agent.context.ContextProvider;
import com.yizhaoqi.smartpai.service.agent.context.ContextRequest;
import com.yizhaoqi.smartpai.service.skill.LoadedSkill;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 向 system 侧注入"本项目启用的 skill 清单（name + description）"——帮助 LLM 知道自己能调哪些 skill。
 * 不注入 SKILL.md 正文：正文很长，按需由 LLM 调 use_skill 拉进上下文。
 *
 * 策略：
 *  - Project 无 enabledSkillsJson 或为空 → 取租户可见的前 20 个 skill；
 *  - 项目配置了白名单 → 仅列白名单交集的 skill；
 *  - 若 skill 数 > 20，压缩版只保留 name。
 */
@Component
public class ActiveSkillContextProvider implements ContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(ActiveSkillContextProvider.class);
    private static final int HARD_CAP = 20;

    private final SkillRegistry registry;
    private final ProjectRepository projectRepository;
    private final UsageQuotaService usage;
    private final ObjectMapper mapper;

    public ActiveSkillContextProvider(SkillRegistry registry,
                                      ProjectRepository projectRepository,
                                      UsageQuotaService usage,
                                      ObjectMapper mapper) {
        this.registry = registry;
        this.projectRepository = projectRepository;
        this.usage = usage;
        this.mapper = mapper;
    }

    @Override public String name() { return "active_skills"; }
    @Override public int order() { return 30; }

    @Override
    public List<ContextContribution> contribute(ContextRequest req) {
        List<LoadedSkill> visible = registry.listVisible(req.orgTag());
        if (visible.isEmpty()) return List.of();

        Set<String> whitelist = readWhitelist(req.projectId());
        List<LoadedSkill> selected = new ArrayList<>();
        if (whitelist == null || whitelist.isEmpty()) {
            for (LoadedSkill s : visible) {
                if (selected.size() >= HARD_CAP) break;
                selected.add(s);
            }
        } else {
            for (LoadedSkill s : visible) {
                if (whitelist.contains(s.name())) selected.add(s);
            }
        }
        if (selected.isEmpty()) return List.of();

        StringBuilder full = new StringBuilder("# 可用 Skills\n");
        StringBuilder lite = new StringBuilder("# 可用 Skills（仅名称）\n");
        for (LoadedSkill s : selected) {
            full.append("- **").append(s.name()).append("**");
            if (s.description() != null && !s.description().isBlank()) {
                full.append(": ").append(s.description().replaceAll("\\s+", " "));
            }
            full.append('\n');
            lite.append("- ").append(s.name()).append('\n');
        }
        full.append("\n使用约定：先调 `list_skills` / `use_skill(name=...)` 读详情，再通过 `bash` 执行 scripts/ 下的脚本。\n");

        int fullTokens = usage.estimateTextTokens(full.toString()) + 16;
        int liteTokens = usage.estimateTextTokens(lite.toString()) + 16;

        Map<String, Object> fullMsg = new LinkedHashMap<>();
        fullMsg.put("role", "system");
        fullMsg.put("content", full.toString());

        Map<String, Object> liteMsg = new LinkedHashMap<>();
        liteMsg.put("role", "system");
        liteMsg.put("content", lite.toString());

        return List.of(ContextContribution.compressible(
                "skills", 80, fullTokens, List.of(fullMsg), List.of(liteMsg), liteTokens,
                "skills(" + selected.size() + ")"));
    }

    private Set<String> readWhitelist(Long projectId) {
        if (projectId == null) return null;
        Project p = projectRepository.findById(projectId).orElse(null);
        if (p == null || p.getEnabledSkillsJson() == null || p.getEnabledSkillsJson().isBlank()) return null;
        try {
            List<String> arr = mapper.readValue(p.getEnabledSkillsJson(), new TypeReference<List<String>>() {});
            return new HashSet<>(arr);
        } catch (Exception e) {
            logger.debug("解析 project {} enabledSkills 失败: {}", projectId, e.getMessage());
            return null;
        }
    }
}
