package com.yizhaoqi.smartpai.service.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.agent.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 注册表：内存中维护 name → LoadedSkill 索引（双缓冲，读侧无锁）。
 *
 * 写入入口：
 *  - 启动时 SkillLoader 扫盘 + DB 合并 → {@link #replaceAll(List)}
 *  - 热重载 / 安装 / 卸载 → {@link #upsert(LoadedSkill)} / {@link #remove(String, String)}
 *
 * 读侧：
 *  - {@link #find(String, String)} / {@link #listVisible(String)}：按 org 可见性过滤
 */
@Component
public class SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    /** key = name|ownerOrgTag（null 用 "_global_" 表示） */
    private final ConcurrentHashMap<String, LoadedSkill> byKey = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;

    public SkillRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void replaceAll(List<LoadedSkill> skills) {
        byKey.clear();
        for (LoadedSkill s : skills) {
            byKey.put(key(s.name(), s.ownerOrgTag()), s);
        }
        logger.info("SkillRegistry 全量替换完成，共 {} 个 skill", byKey.size());
    }

    public void upsert(LoadedSkill s) {
        byKey.put(key(s.name(), s.ownerOrgTag()), s);
        logger.info("SkillRegistry upsert skill={} org={} source={} enabled={}",
                s.name(), s.ownerOrgTag(), s.source(), s.enabled());
    }

    public void remove(String name, String ownerOrgTag) {
        byKey.remove(key(name, ownerOrgTag));
    }

    /**
     * 查找 skill：优先找租户专属版本，退回全局版本。
     */
    public Optional<LoadedSkill> find(String name, String orgTag) {
        if (orgTag != null) {
            LoadedSkill tenantScoped = byKey.get(key(name, orgTag));
            if (tenantScoped != null && tenantScoped.enabled()) return Optional.of(tenantScoped);
        }
        LoadedSkill global = byKey.get(key(name, null));
        if (global != null && global.enabled()) return Optional.of(global);
        return Optional.empty();
    }

    public List<LoadedSkill> listVisible(String orgTag) {
        List<LoadedSkill> out = new ArrayList<>();
        for (LoadedSkill s : byKey.values()) {
            if (s.isVisibleFor(orgTag)) out.add(s);
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    public List<LoadedSkill> all() {
        return new ArrayList<>(byKey.values());
    }

    private String key(String name, String orgTag) {
        return (name == null ? "_" : name) + "|" + (orgTag == null ? "_global_" : orgTag);
    }

    // ----- 工具方法：entity ↔ LoadedSkill -----

    public LoadedSkill fromEntity(Skill e) {
        List<String> scripts = splitLines(e.getScriptsInventory());
        List<String> bins = parseBins(e.getRequiredBinsJson());
        return new LoadedSkill(
                e.getId(),
                e.getName(),
                e.getDescription(),
                e.getVersion(),
                e.getHomepage(),
                e.getBodyMd(),
                e.getRootPath(),
                scripts,
                bins,
                e.getOwnerOrgTag(),
                e.getSource(),
                Boolean.TRUE.equals(e.getEnabled()),
                e.getBodyHash()
        );
    }

    private List<String> splitLines(String s) {
        if (s == null || s.isBlank()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String line : s.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseBins(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object bins = m.get("bins");
            if (bins instanceof List<?> l) {
                List<String> out = new ArrayList<>(l.size());
                for (Object o : l) if (o != null) out.add(String.valueOf(o));
                return out;
            }
        } catch (Exception e) {
            logger.debug("parseBins 失败 json={} err={}", json, e.getMessage());
        }
        return Collections.emptyList();
    }
}
