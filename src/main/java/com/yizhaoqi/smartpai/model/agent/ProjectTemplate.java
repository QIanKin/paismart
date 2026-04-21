package com.yizhaoqi.smartpai.model.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 项目模板：把常见的 SOP 固化下来，一键创建项目时带入。
 * 内容用 JSON 串存储，保持表结构稳定：
 *  - systemPrompt
 *  - enabledToolsJson：["knowledge_search","web_fetch",...]
 *  - enabledSkillsJson: ["xhs-scraper",...]
 *  - defaultTodosJson：推荐的初始 TODO 列表（frontend 可预填）
 *  - defaultSopJson  ：SOP 步骤描述（每步 name/description/suggested_tools）
 *
 * 租户可见性：ownerOrgTag=null → 全局模板；非 null → 只对所属租户可见。
 */
@Data
@Entity
@Table(name = "agent_project_templates", uniqueConstraints =
        @UniqueConstraint(name = "uq_tpl_code", columnNames = "code"))
public class ProjectTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模板业务编码（稳定，前端可用；建议 kebab-case） */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    @Lob
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Lob
    @Column(name = "enabled_tools", columnDefinition = "TEXT")
    private String enabledToolsJson;

    @Lob
    @Column(name = "enabled_skills", columnDefinition = "TEXT")
    private String enabledSkillsJson;

    @Lob
    @Column(name = "default_todos", columnDefinition = "TEXT")
    private String defaultTodosJson;

    @Lob
    @Column(name = "default_sop", columnDefinition = "TEXT")
    private String defaultSopJson;

    /** null 表示全局模板；非 null 表示仅所属租户可见 */
    @Column(name = "owner_org_tag", length = 64)
    private String ownerOrgTag;

    /** 封面图/图标 */
    @Column(length = 256)
    private String icon;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @JsonIgnore
    @CreationTimestamp
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
