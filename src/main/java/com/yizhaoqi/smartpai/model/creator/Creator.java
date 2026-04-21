package com.yizhaoqi.smartpai.model.creator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 博主（人）— 由公司内部运营口径定义，通常对应一位真实创作者或一个合作入口。
 * 一个 Creator 下面挂多个 CreatorAccount（各平台账号），对应"flat schema"里 Creator 的那一层。
 *
 * 允许账号先入库、后关联 Creator，因此 CreatorAccount.creatorId 可空；
 * 公司内部同事（如 BD）可以随手把同一个人的多个账号合并到同一 Creator。
 */
@Data
@Entity
@Table(name = "creators", indexes = {
        @Index(name = "idx_creator_org", columnList = "owner_org_tag"),
        @Index(name = "idx_creator_status", columnList = "cooperation_status"),
        @Index(name = "idx_creator_name", columnList = "display_name")
})
public class Creator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_org_tag", length = 64, nullable = false)
    private String ownerOrgTag;

    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;

    @Column(name = "real_name", length = 128)
    private String realName;

    @Column(length = 16)
    private String gender;

    @Column(name = "birth_year")
    private Integer birthYear;

    @Column(length = 64)
    private String city;

    @Column(length = 64)
    private String country;

    /** 人设标签 JSON array，例 ["母婴","家居"] */
    @Lob
    @Column(name = "persona_tags_json", columnDefinition = "TEXT")
    private String personaTagsJson;

    /** 所处赛道 JSON array，例 ["母婴","亲子"] */
    @Lob
    @Column(name = "track_tags_json", columnDefinition = "TEXT")
    private String trackTagsJson;

    /** 合作状态：potential / negotiating / active / paused / blacklisted */
    @Column(name = "cooperation_status", length = 32)
    private String cooperationStatus = "potential";

    /** 公司内部跟进人 user id（非必填，可后补） */
    @Column(name = "internal_owner_id")
    private Long internalOwnerId;

    @Lob
    @Column(name = "internal_notes", columnDefinition = "TEXT")
    private String internalNotes;

    /** 合作报价/商务备注，隐私字段 */
    @Column(name = "price_note", length = 255)
    private String priceNote;

    /** 自定义字段（前端可按 CustomFieldDefinition 加字段） */
    @Lob
    @Column(name = "custom_fields_json", columnDefinition = "MEDIUMTEXT")
    private String customFieldsJson;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @JsonIgnore
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
