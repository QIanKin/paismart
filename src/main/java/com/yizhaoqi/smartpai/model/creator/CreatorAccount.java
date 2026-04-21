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
 * 博主在"某个平台上"的账号。用户指定的 "flat" 口径：一个平台账号 = 一条记录。
 * 可以独立存在（未绑定 Creator），也可以绑到某个 {@link Creator}。
 *
 * 去重：同一 platform + platformUserId 唯一。
 * 若平台返回的 platformUserId 不稳定，退而求其次用 (platform, handle, ownerOrgTag) 唯一。
 */
@Data
@Entity
@Table(name = "creator_accounts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_account_platform_user",
                        columnNames = {"platform", "platform_user_id"})
        },
        indexes = {
                @Index(name = "idx_account_org", columnList = "owner_org_tag"),
                @Index(name = "idx_account_creator", columnList = "creator_id"),
                @Index(name = "idx_account_platform_handle", columnList = "platform,handle"),
                @Index(name = "idx_account_followers", columnList = "followers"),
                @Index(name = "idx_account_category", columnList = "category_main")
        })
public class CreatorAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "owner_org_tag", length = 64, nullable = false)
    private String ownerOrgTag;

    /** 平台：xhs / douyin / bilibili / weibo / wechat_mp / kuaishou / youtube ... */
    @Column(length = 32, nullable = false)
    private String platform;

    @Column(name = "platform_user_id", length = 96, nullable = false)
    private String platformUserId;

    /** 用户名/账号名（@handle） */
    @Column(length = 128)
    private String handle;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String bio;

    private Long followers;
    private Long following;
    private Long likes;
    private Long posts;

    /** 近 30 天 /近 N 篇 平均互动 —— 跟 snapshot 不同，这里保留最近值便于筛选 */
    @Column(name = "avg_likes")
    private Long avgLikes;

    @Column(name = "avg_comments")
    private Long avgComments;

    @Column(name = "hit_ratio")
    private Double hitRatio;

    @Column(name = "engagement_rate")
    private Double engagementRate;

    private Boolean verified;

    @Column(name = "verify_type", length = 32)
    private String verifyType;

    @Column(length = 64)
    private String region;

    @Column(name = "homepage_url", length = 512)
    private String homepageUrl;

    /** 平台内分类，主/次 */
    @Column(name = "category_main", length = 64)
    private String categoryMain;

    @Column(name = "category_sub", length = 64)
    private String categorySub;

    /** 平台打的标签 JSON array */
    @Lob
    @Column(name = "platform_tags_json", columnDefinition = "TEXT")
    private String platformTagsJson;

    /** 自定义字段 */
    @Lob
    @Column(name = "custom_fields_json", columnDefinition = "MEDIUMTEXT")
    private String customFieldsJson;

    /** 最近一次快照时间（followers/posts 等数据同步时） */
    @Column(name = "latest_snapshot_at")
    private LocalDateTime latestSnapshotAt;

    @JsonIgnore
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
