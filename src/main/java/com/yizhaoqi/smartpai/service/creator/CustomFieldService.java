package com.yizhaoqi.smartpai.service.creator;

import com.yizhaoqi.smartpai.model.creator.CustomFieldDefinition;
import com.yizhaoqi.smartpai.repository.creator.CustomFieldDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CustomFieldService {

    /**
     * 支持的实体类型。
     * - creator / account / post：博主库内部（人 / 平台账号 / 单篇笔记）；
     * - project：项目级别（如客户名、campaign 目标、行业、预算等）；
     * - project_creator：博主在特定项目下的名册条目（交付物、流量目标、合同号等）。
     */
    private static final Set<String> ENTITY_TYPES =
            Set.of("creator", "account", "post", "project", "project_creator");
    private static final Set<String> DATA_TYPES =
            Set.of("string", "number", "boolean", "date", "enum", "tags", "url", "text", "money");

    private final CustomFieldDefinitionRepository repo;

    public CustomFieldService(CustomFieldDefinitionRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<CustomFieldDefinition> list(String ownerOrgTag, String entityType) {
        validateEntityType(entityType);
        return repo.findByOwnerOrgTagAndEntityTypeOrderByDisplayOrderAsc(ownerOrgTag, entityType);
    }

    @Transactional
    public CustomFieldDefinition upsert(CustomFieldDefinition def) {
        if (def.getOwnerOrgTag() == null || def.getOwnerOrgTag().isBlank())
            throw new IllegalArgumentException("ownerOrgTag 必填");
        validateEntityType(def.getEntityType());
        if (def.getFieldKey() == null || def.getFieldKey().isBlank())
            throw new IllegalArgumentException("fieldKey 必填");
        if (def.getLabel() == null || def.getLabel().isBlank())
            throw new IllegalArgumentException("label 必填");
        if (!DATA_TYPES.contains(def.getDataType()))
            throw new IllegalArgumentException("dataType 不合法: " + def.getDataType());

        Optional<CustomFieldDefinition> existing = repo
                .findByOwnerOrgTagAndEntityTypeAndFieldKey(
                        def.getOwnerOrgTag(), def.getEntityType(), def.getFieldKey());
        CustomFieldDefinition target = existing.orElseGet(CustomFieldDefinition::new);
        if (target.getId() == null) {
            target.setOwnerOrgTag(def.getOwnerOrgTag());
            target.setEntityType(def.getEntityType());
            target.setFieldKey(def.getFieldKey());
            target.setIsBuiltIn(Boolean.TRUE.equals(def.getIsBuiltIn()));
        } else if (Boolean.TRUE.equals(target.getIsBuiltIn())) {
            // 内置字段不允许改 dataType/key，仅能改 label/options/displayOrder/description
            if (def.getLabel() != null) target.setLabel(def.getLabel());
            if (def.getOptionsJson() != null) target.setOptionsJson(def.getOptionsJson());
            if (def.getDisplayOrder() != null) target.setDisplayOrder(def.getDisplayOrder());
            if (def.getDescription() != null) target.setDescription(def.getDescription());
            return repo.save(target);
        }
        target.setLabel(def.getLabel());
        target.setDataType(def.getDataType());
        target.setOptionsJson(def.getOptionsJson());
        target.setIsRequired(Boolean.TRUE.equals(def.getIsRequired()));
        target.setDisplayOrder(def.getDisplayOrder() == null ? 0 : def.getDisplayOrder());
        target.setDescription(def.getDescription());
        return repo.save(target);
    }

    @Transactional
    public boolean delete(Long id, String ownerOrgTag) {
        return repo.findById(id)
                .filter(d -> d.getOwnerOrgTag().equals(ownerOrgTag))
                .filter(d -> !Boolean.TRUE.equals(d.getIsBuiltIn()))
                .map(d -> { repo.delete(d); return true; }).orElse(false);
    }

    private static void validateEntityType(String t) {
        if (!ENTITY_TYPES.contains(t)) {
            throw new IllegalArgumentException("entityType 必须是 " + ENTITY_TYPES);
        }
    }
}
