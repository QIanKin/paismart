package com.yizhaoqi.smartpai.service.skill;

import com.yizhaoqi.smartpai.model.agent.Skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时视图：把 MySQL Skill entity + 磁盘 body 合成一个不可变 record，供 LLM context 注入和 tool 调用。
 * body 永远从内存（上次加载时的 bodyMd）读，避免热请求打盘。
 */
public record LoadedSkill(
        Long id,
        String name,
        String description,
        String version,
        String homepage,
        String bodyMd,
        String rootPath,
        List<String> scripts,
        List<String> requiredBins,
        String ownerOrgTag,
        Skill.Source source,
        boolean enabled,
        String bodyHash
) {
    public Map<String, Object> toManifest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("version", version == null ? "-" : version);
        m.put("scripts", scripts);
        m.put("requiredBins", requiredBins);
        m.put("ownerOrgTag", ownerOrgTag);
        m.put("source", source == null ? null : source.name());
        m.put("enabled", enabled);
        return m;
    }

    public boolean isVisibleFor(String orgTag) {
        if (!enabled) return false;
        if (ownerOrgTag == null) return true;
        return ownerOrgTag.equals(orgTag);
    }

    public List<String> scriptsOrEmpty() {
        return scripts == null ? Collections.emptyList() : scripts;
    }
}
