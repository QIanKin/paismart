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
 * 博主的一条平台内容（视频/笔记/图文/直播回放）。
 * 去重：同 platform + platformPostId 唯一。
 */
@Data
@Entity
@Table(name = "creator_posts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_post_platform_id",
                        columnNames = {"platform", "platform_post_id"})
        },
        indexes = {
                @Index(name = "idx_post_account", columnList = "account_id"),
                @Index(name = "idx_post_org", columnList = "owner_org_tag"),
                @Index(name = "idx_post_published", columnList = "published_at"),
                @Index(name = "idx_post_platform_type", columnList = "platform,post_type")
        })
public class CreatorPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "owner_org_tag", length = 64, nullable = false)
    private String ownerOrgTag;

    @Column(length = 32, nullable = false)
    private String platform;

    @Column(name = "platform_post_id", length = 96, nullable = false)
    private String platformPostId;

    /** video / note / image / live_replay / article */
    @Column(name = "post_type", length = 16)
    private String postType;

    @Column(length = 512)
    private String title;

    @Lob
    @Column(name = "content_text", columnDefinition = "MEDIUMTEXT")
    private String contentText;

    @Column(name = "cover_url", length = 1024)
    private String coverUrl;

    @Column(name = "video_url", length = 1024)
    private String videoUrl;

    @Column(name = "link", length = 1024)
    private String link;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "duration_sec")
    private Integer durationSec;

    private Long likes;
    private Long comments;
    private Long shares;
    private Long collects;
    private Long views;

    /** "是否爆款"标记；公司内部口径 */
    @Column(name = "is_hit")
    private Boolean isHit;

    /** 平台 hashtags JSON array */
    @Lob
    @Column(name = "hashtags_json", columnDefinition = "TEXT")
    private String hashtagsJson;

    /** 爆款结构标签（公司内部打的） */
    @Lob
    @Column(name = "hit_structure_tags_json", columnDefinition = "TEXT")
    private String hitStructureTagsJson;

    /** 截图或视频缩略图在 MinIO 的 key，供前端渲染 */
    @Column(name = "screenshot_key", length = 512)
    private String screenshotKey;

    @Lob
    @Column(name = "raw_json", columnDefinition = "MEDIUMTEXT")
    private String rawJson;

    @Column(name = "metrics_snapshot_at")
    private LocalDateTime metricsSnapshotAt;

    @JsonIgnore
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
