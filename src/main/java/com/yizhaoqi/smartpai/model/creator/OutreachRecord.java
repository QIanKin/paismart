package com.yizhaoqi.smartpai.model.creator;

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
 * 外联触达记录（评论 / 点赞 / 关注 / 私信）。
 * <p>对齐 openclaw 备份里 {@code xiaohongshu-outreach-bridge/init_db.py} 的 outreach 表，
 * 同时做成多平台通用：platform + platformUserId 作为外部主键。
 *
 * <p>典型流程：
 * <ol>
 *   <li>skill {@code xhs-outreach-comment} 跑完一个博主 → 写入一行 PENDING/SUCCESS/FAILED。</li>
 *   <li>后续人工/skill 回访拉取回复 → 更新 {@link #gotReply} / {@link #replyText} / {@link #replyAt}。</li>
 *   <li>前端"外联看板"按 session/project 聚合展示。</li>
 * </ol>
 *
 * <p>和 {@link CreatorAccount} 的关系：可为空（出厂就抓到的即兴外联），有 creatorAccountId 时表示已入库博主。
 */
@Data
@Entity
@Table(name = "outreach_records",
        indexes = {
                @Index(name = "idx_outreach_org", columnList = "owner_org_tag"),
                @Index(name = "idx_outreach_account", columnList = "creator_account_id"),
                @Index(name = "idx_outreach_platform_user", columnList = "platform,platform_user_id"),
                @Index(name = "idx_outreach_session", columnList = "session_id"),
                @Index(name = "idx_outreach_project", columnList = "project_id"),
                @Index(name = "idx_outreach_status", columnList = "status"),
                @Index(name = "idx_outreach_executed_at", columnList = "executed_at")
        })
public class OutreachRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_org_tag", length = 64, nullable = false)
    private String ownerOrgTag;

    /** 已入库博主（可空） */
    @Column(name = "creator_account_id")
    private Long creatorAccountId;

    /** 平台：xhs / douyin / ... */
    @Column(length = 32, nullable = false)
    private String platform;

    /** 平台侧博主 id（从主页 URL 提取） */
    @Column(name = "platform_user_id", length = 96)
    private String platformUserId;

    @Column(length = 128)
    private String nickname;

    @Column(name = "profile_url", length = 512)
    private String profileUrl;

    /** 被评论/互动的具体笔记/视频/帖子 */
    @Column(name = "post_id", length = 96)
    private String postId;

    @Column(name = "post_url", length = 512)
    private String postUrl;

    /** 动作类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Action action = Action.COMMENT;

    /** 执行时使用的消息/评论文本 */
    @Lob
    @Column(name = "message_text", columnDefinition = "TEXT")
    private String messageText;

    /** 状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.PENDING;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 筛选判定：Y/N/?（对齐 Jenny 的 screenProfile verdict） */
    @Column(length = 4)
    private String verdict;

    @Lob
    @Column(name = "verdict_reason_json", columnDefinition = "TEXT")
    private String verdictReasonJson;

    @Column(name = "commercial_score")
    private Integer commercialScore;

    @Column(name = "personal_score")
    private Integer personalScore;

    @Column(length = 255)
    private String email;

    @Column(length = 64)
    private String wechat;

    @Column(length = 32)
    private String phone;

    /** 回复追踪 */
    @Column(name = "got_reply", nullable = false)
    private Boolean gotReply = false;

    @Lob
    @Column(name = "reply_text", columnDefinition = "TEXT")
    private String replyText;

    @Column(name = "reply_at")
    private LocalDateTime replyAt;

    /** 审计：哪个业务员/skill 跑的 */
    @Column(name = "executed_by", length = 96)
    private String executedBy;

    @Column(name = "executor_skill", length = 96)
    private String executorSkill;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 和 agent 会话 / 项目的关联（可空） */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "project_id", length = 64)
    private String projectId;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum Action {
        COMMENT,
        LIKE,
        FOLLOW,
        DM,
        VIEW
    }

    public enum Status {
        PENDING,
        SCREENED_OUT,
        SUCCESS,
        FAILED,
        RATE_LIMITED,
        ALREADY_DONE
    }
}
