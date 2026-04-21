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
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 定时 Skill 任务：企业定期刷新内部数据资产（赛道热榜、博主快照、爆款结构等）。
 *
 * 语义：按 {@link #cron} 触发，执行某个 skill 下 scripts 中的 {@code entrypoint}，
 * 可选传 paramsJson 作为 argv。执行结果会被：
 *  1. 存为 MemoryItem（source=fact）供 LLM recall；
 *  2. 写到 agent_skill_task_runs（保留最近 N 条）；
 *  3. 通知订阅的 WS 频道（如后台面板）。
 */
@Data
@Entity
@Table(name = "agent_skill_tasks", indexes = {
        @Index(name = "idx_task_skill", columnList = "skill_name"),
        @Index(name = "idx_task_enabled", columnList = "enabled")
})
public class ScheduledSkillTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "skill_name", nullable = false, length = 96)
    private String skillName;

    /** skill scripts 目录下的入口文件名，如 "refresh_xhs_tracks.sh" */
    @Column(length = 256)
    private String entrypoint;

    /** 5~6 位 cron（Spring cron 语法） */
    @Column(nullable = false, length = 64)
    private String cron;

    /** 传给 entrypoint 的参数 JSON（array 形式） */
    @Lob
    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    /** 任务产出的摘要生成策略：raw（整段输出）/ summary（LLM 压缩）/ none */
    @Column(name = "output_mode", length = 16)
    private String outputMode = "summary";

    /** 作用域：租户级（org_tag）或项目级（project_id）；都空表示全局 */
    @Column(name = "org_tag", length = 64)
    private String orgTag;

    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", length = 16)
    private LastStatus lastStatus;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Lob
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @JsonIgnore
    @CreationTimestamp
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum LastStatus { running, success, failed, skipped }
}
