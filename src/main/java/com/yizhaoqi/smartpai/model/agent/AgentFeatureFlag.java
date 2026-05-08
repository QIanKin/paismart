package com.yizhaoqi.smartpai.model.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 运行时 Feature Flag。用于让管理员在前端实时切换数据源 / 能力组件，
 * 而无须重启容器或修改 .env。
 *
 * <p>命名约定：使用 dot-snake_case，如 {@code data_source.tikhub}、{@code data_source.pgy_cookie}。
 *
 * <p>与 application.yml 配置的关系：
 * <ul>
 *   <li>DB 里有这条记录 → 以 DB 为准（覆盖 yml）；</li>
 *   <li>DB 里没有 → 回退到 yml 默认（即旧行为）。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "agent_feature_flags",
        indexes = {
                @Index(name = "idx_flag_key", columnList = "flag_key", unique = true)
        })
public class AgentFeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flag_key", nullable = false, length = 96)
    private String key;

    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(length = 512)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
