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
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 广告项目 ↔ 博主 的 roster（名册）关联。
 * <p>
 * 一个项目下的多个博主在生命周期中会经历：候选 → 入围 → 锁定 → 已签约 → 已发布 → 已结算。
 * 前端据此做看板/筛选；Agent 据此为「博主分配会话」与「博主方案会话」提供上下文。
 * <p>
 * 唯一约束：同一项目下同一 Creator 只能在 roster 上出现一次。
 */
@Data
@Entity
@Table(name = "project_creators",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pc_project_creator",
                        columnNames = {"project_id", "creator_id"})
        },
        indexes = {
                @Index(name = "idx_pc_project", columnList = "project_id"),
                @Index(name = "idx_pc_creator", columnList = "creator_id"),
                @Index(name = "idx_pc_stage", columnList = "stage"),
                @Index(name = "idx_pc_org", columnList = "owner_org_tag")
        })
public class ProjectCreator {

    public enum Stage {
        /** 候选：处于筛选/洽谈早期 */
        CANDIDATE,
        /** 入围：PM 已初步挑选 */
        SHORTLISTED,
        /** 锁定：双方确认要合作但未签约 */
        LOCKED,
        /** 已签约：合同/排期确定 */
        SIGNED,
        /** 已发布：内容上线 */
        PUBLISHED,
        /** 已结算 */
        SETTLED,
        /** 已放弃/淘汰（为未来扩展留；UI 允许软删除） */
        DROPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    /** 关联时冗余保存，省一次 join；租户越权防御线 */
    @Column(name = "owner_org_tag", length = 64, nullable = false)
    private String ownerOrgTag;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Stage stage = Stage.CANDIDATE;

    /** 1-100，数字越大越优先；默认 50 */
    @Column(nullable = false)
    private Integer priority = 50;

    /** 对本项目对该博主的报价（可覆盖 Creator.priceNote） */
    @Column(name = "quoted_price", precision = 12, scale = 2)
    private BigDecimal quotedPrice;

    /** 币种，默认 CNY */
    @Column(length = 8)
    private String currency;

    /** 公司侧该博主的对接人 user.id */
    @Column(name = "assigned_to_user_id")
    private Long assignedToUserId;

    /** 项目维度的备注（比如：「此博主本项目走日常种草单」） */
    @Lob
    @Column(name = "project_notes", columnDefinition = "TEXT")
    private String projectNotes;

    /** 加入 roster 的 user.id */
    @Column(name = "added_by", length = 64)
    private String addedBy;

    /**
     * 名册条目级自定义字段值（JSON），用户可在「自定义字段」里用 entity_type=project_creator 定义。<br>
     * 典型字段：deliverables / trafficTarget / riskNote / contractNo。
     */
    @Lob
    @Column(name = "custom_fields", columnDefinition = "TEXT")
    private String customFieldsJson;

    @JsonIgnore
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
