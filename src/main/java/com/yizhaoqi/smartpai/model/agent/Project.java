package com.yizhaoqi.smartpai.model.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 项目——用户组织会话的顶层容器。
 * 一个项目聚合：一组工具白名单、一组 skill 白名单、一段专属 system prompt、多个会话（ChatSession）。
 * 租户隔离：ownerUserId + orgTag，查询时同时按两个过滤，避免跨租户串数据。
 */
@Data
@Entity
@Table(name = "agent_projects", indexes = {
        @Index(name = "idx_ap_owner", columnList = "owner_user_id"),
        @Index(name = "idx_ap_org", columnList = "org_tag"),
        @Index(name = "idx_ap_status", columnList = "status")
})
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 项目级 system prompt；空时 AgentRuntime 走默认全局 prompt + 行为契约 */
    @Lob
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    /** 工具白名单 JSON 数组，如 ["knowledge_search","web_fetch"]；空/null 表示使用全局 */
    @Lob
    @Column(name = "enabled_tools", columnDefinition = "TEXT")
    private String enabledToolsJson;

    /** skill 白名单 JSON 数组，如 ["xhs-scraper","bilibili-analyzer"]；空表示全部启用 */
    @Lob
    @Column(name = "enabled_skills", columnDefinition = "TEXT")
    private String enabledSkillsJson;

    /** 项目创建时用到的模板 code（若有），方便后续追溯 */
    @Column(name = "template_code", length = 64)
    private String templateCode;

    /** 项目当前状态：active / archived */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.active;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "org_tag", length = 64)
    private String orgTag;

    /**
     * 项目级自定义字段值，JSON object，如
     * {"client":"蕉下","campaignGoal":"618种草","industry":"美妆"}。<br>
     * 可用字段由 creator_custom_fields (entity_type=project) 定义。
     */
    @Lob
    @Column(name = "custom_fields", columnDefinition = "TEXT")
    private String customFieldsJson;

    @JsonIgnore
    @CreationTimestamp
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Status { active, archived }
}
