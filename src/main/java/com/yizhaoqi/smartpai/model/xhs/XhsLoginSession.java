package com.yizhaoqi.smartpai.model.xhs;

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

import java.time.LocalDateTime;

/**
 * 小红书扫码登录会话。
 *
 * 生命周期（status）：
 *   PENDING   -> QR_READY -> SCANNED -> CONFIRMED -> SUCCESS
 *                                                 \-> FAILED
 *                                                 \-> EXPIRED
 *                                                 \-> CANCELLED
 *
 * 每条记录对应一次"业务员点 扫码登录 → 拿到 cookie 入库"的完整过程，
 * 无论成败都留痕，方便审计 & 排障。
 *
 * session_id 是对外暴露的主键（UUID），用于 WS 订阅 & REST 查询；
 * 数据库自增 id 只做内部用。
 *
 * 多租户：owner_org_tag 必填，隔离到组织。
 */
@Data
@Entity
@Table(name = "xhs_login_sessions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_xhs_login_session_id", columnNames = "session_id")
        },
        indexes = {
                @Index(name = "idx_xhs_login_org_status", columnList = "owner_org_tag,status"),
                @Index(name = "idx_xhs_login_expires_at", columnList = "expires_at")
        })
public class XhsLoginSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外 UUID，客户端订阅 WS / 查 REST 都用它。 */
    @Column(name = "session_id", length = 64, nullable = false, updatable = false)
    private String sessionId;

    @Column(name = "owner_org_tag", length = 64, nullable = false)
    private String ownerOrgTag;

    @Column(name = "created_by_user_id", length = 64, nullable = false)
    private String createdByUserId;

    /** 本次请求要采的平台（逗号分隔）: xhs_pc,xhs_creator,xhs_pgy,xhs_qianfan */
    @Column(name = "platforms", length = 255, nullable = false)
    private String platforms;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.PENDING;

    /**
     * base64 data URL。成功或失败后清空（不留底）。
     * 用 TEXT（65K）足够 —— 二维码 PNG base64 一般 2~4KB。
     */
    @Lob
    @Column(name = "qr_data_url", columnDefinition = "TEXT")
    private String qrDataUrl;

    @Column(name = "captured_platforms", length = 255)
    private String capturedPlatforms;

    @Column(name = "missing_platforms", length = 255)
    private String missingPlatforms;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 超过此时间仍未完成 → 视为 EXPIRED。 */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum Status {
        PENDING,
        QR_READY,
        SCANNED,
        CONFIRMED,
        SUCCESS,
        FAILED,
        EXPIRED,
        CANCELLED
    }

    /** 是否已到终态（不再接受状态变更）。 */
    public boolean isTerminal() {
        return status == Status.SUCCESS
                || status == Status.FAILED
                || status == Status.EXPIRED
                || status == Status.CANCELLED;
    }
}
