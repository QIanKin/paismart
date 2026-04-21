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

import java.time.LocalDateTime;

/**
 * 账号指标的时间序列快照。前端画"粉丝增长曲线"/"点赞均值趋势"用。
 * 每次定时任务或手动刷新时插入一条；不更新，保留历史。
 */
@Data
@Entity
@Table(name = "creator_snapshots", indexes = {
        @Index(name = "idx_snap_account_time", columnList = "account_id,snapshot_at DESC")
})
public class CreatorSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "snapshot_at", nullable = false)
    private LocalDateTime snapshotAt;

    private Long followers;
    private Long following;
    private Long likes;
    private Long posts;

    @Column(name = "avg_likes")
    private Long avgLikes;

    @Column(name = "avg_comments")
    private Long avgComments;

    @Column(name = "hit_ratio")
    private Double hitRatio;

    @Column(name = "engagement_rate")
    private Double engagementRate;

    @Lob
    @Column(name = "raw_json", columnDefinition = "MEDIUMTEXT")
    private String rawJson;

    @JsonIgnore
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
