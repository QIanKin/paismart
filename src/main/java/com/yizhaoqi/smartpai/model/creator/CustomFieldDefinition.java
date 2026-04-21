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
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 自定义字段定义：前端 UI "给博主加字段" / "给视频加字段" 的元信息来源。
 * 同一 ownerOrgTag + entityType + key 唯一；系统内置字段 isBuiltIn=true 不可删除。
 */
@Data
@Entity
@Table(name = "creator_custom_fields",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_custom_field_key",
                        columnNames = {"owner_org_tag", "entity_type", "field_key"})
        },
        indexes = {
                @Index(name = "idx_cfd_entity", columnList = "owner_org_tag,entity_type")
        })
public class CustomFieldDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_org_tag", length = 64, nullable = false)
    private String ownerOrgTag;

    /** 目标实体：creator / account / post */
    @Column(name = "entity_type", length = 16, nullable = false)
    private String entityType;

    @Column(name = "field_key", length = 64, nullable = false)
    private String fieldKey;

    @Column(length = 128, nullable = false)
    private String label;

    /** string / number / boolean / date / enum / tags / url */
    @Column(name = "data_type", length = 16, nullable = false)
    private String dataType;

    /** enum 时存 JSON array；tags 时存 JSON array 建议项 */
    @Lob
    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = false;

    @Column(name = "is_builtin", nullable = false)
    private Boolean isBuiltIn = false;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Column(length = 255)
    private String description;

    @JsonIgnore
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
