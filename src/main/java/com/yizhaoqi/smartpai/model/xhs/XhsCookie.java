package com.yizhaoqi.smartpai.model.xhs;

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
 * 小红书 / 蒲公英 / 千帆等平台的 cookie 池。
 *
 * 每一行 = 一个账号的登录凭证。Agent 在执行 xhs-* skill 时由
 * {@link com.yizhaoqi.smartpai.service.xhs.XhsCookieService#pickAvailable} 做加权轮转挑选。
 *
 * 字段设计：
 *  - cookieEncrypted：AES/GCM 密文 + base64，解密见 {@link com.yizhaoqi.smartpai.service.xhs.CookieCipher}
 *  - platform：xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan  （与 Spider_XHS 四套 API 对应）
 *  - status：ACTIVE / EXPIRED / BANNED / DISABLED
 *  - priority：大的优先；失败多 → 降 priority，成功 → +1
 *  - failCount / successCount / lastUsedAt / lastCheckedAt：健康指标
 *
 * 多租户：ownerOrgTag 必填，同一 org 下多账号轮转。
 */
@Data
@Entity
@Table(name = "xhs_cookies", indexes = {
        @Index(name = "idx_cookie_org_platform_status", columnList = "owner_org_tag,platform,status"),
        @Index(name = "idx_cookie_status", columnList = "status")
})
public class XhsCookie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_org_tag", length = 64, nullable = false)
    private String ownerOrgTag;

    @Column(nullable = false, length = 32)
    private String platform;

    @Column(name = "account_label", length = 128)
    private String accountLabel;

    /** AES/GCM 密文 base64 */
    @JsonIgnore
    @Lob
    @Column(name = "cookie_encrypted", columnDefinition = "TEXT", nullable = false)
    private String cookieEncrypted;

    /** 脱敏预览：前 16 + "..." + 末 8，仅用于前端列表 */
    @Column(name = "cookie_preview", length = 64)
    private String cookiePreview;

    /**
     * 明文 cookie 里出现的 key 名字清单（逗号分隔，不含 value）。
     * 例：{@code "a1,web_session,webId,xsecappid,gid"}。
     * 只存 key，不含敏感 value，可以安全在前端展示，
     * 让用户一眼看出这条 cookie 缺不缺 a1/web_session/webId。
     */
    @Column(name = "cookie_keys", length = 512)
    private String cookieKeys;

    @Column(length = 128)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private Integer priority = 10;

    @Column(name = "success_count", nullable = false)
    private Integer successCount = 0;

    @Column(name = "fail_count", nullable = false)
    private Integer failCount = 0;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "created_by", length = 96)
    private String createdBy;

    /**
     * cookie 来源：MANUAL（人工录入）/ QR_LOGIN（扫码登录采集）/ SEED（seeder 预置）。
     * 用于审计与问题定位。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source = Source.MANUAL;

    /** 如果 source == QR_LOGIN，这里指回 {@code xhs_login_sessions.session_id}。 */
    @Column(name = "login_session_id", length = 64)
    private String loginSessionId;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum Status {
        ACTIVE,
        EXPIRED,
        BANNED,
        DISABLED
    }

    /** cookie 来源渠道。 */
    public enum Source {
        MANUAL,
        QR_LOGIN,
        SEED
    }
}
