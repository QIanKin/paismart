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
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 会话——用户在某个项目下的一条连续对话。
 * 一条会话承载连续的短期 + 长期记忆：
 *  - 短期：Redis L1 缓存近 N 条（由 MessageStore 处理）
 *  - 长期：MySQL Message 全量 + ES agent_memory summary
 *
 * projectId 可为 null，代表"默认项目"的零散会话（兼容用户不创建项目直接聊天）。
 */
@Data
@Entity
@Table(name = "agent_sessions", indexes = {
        @Index(name = "idx_as_user", columnList = "user_id"),
        @Index(name = "idx_as_project", columnList = "project_id"),
        @Index(name = "idx_as_updated", columnList = "last_active_at"),
        @Index(name = "idx_as_creator", columnList = "creator_id"),
        @Index(name = "idx_as_session_type", columnList = "session_type")
})
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "org_tag", length = 64)
    private String orgTag;

    @Column(name = "project_id")
    private Long projectId;

    /**
     * 会话类型：控制 AgentRuntime 如何注入 prompt / 默认工具白名单 / 上下文。
     * 详见 {@link SessionType}。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 24)
    private SessionType sessionType = SessionType.GENERAL;

    /**
     * 当 sessionType 为 BLOGGER_BRIEF / CONTENT_REVIEW / DATA_TRACK 时，
     * 绑定的 Creator.id。Agent 会自动把这个博主的人设标签 / 最近数据注入到 prompt。
     */
    @Column(name = "creator_id")
    private Long creatorId;

    @Column(length = 128)
    private String title;

    /** 会话当前累计的消息数（含 user/assistant/tool） */
    @Column(name = "message_count", nullable = false)
    private Integer messageCount = 0;

    /** 下一个 message 的自增 seq（相对会话内部单调） */
    @Column(name = "next_seq", nullable = false)
    private Integer nextSeq = 1;

    /** 压缩游标——小于此 seq 的消息已被 MemoryCompactor 总结进 memory_items；拉历史时跳过 */
    @Column(name = "compacted_before_seq", nullable = false)
    private Integer compactedBeforeSeq = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.active;

    /** 可选的 system prompt 片段，会覆盖 Project 级 */
    @Column(name = "system_prompt_override", columnDefinition = "TEXT")
    private String systemPromptOverride;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @JsonIgnore
    @CreationTimestamp
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Status { active, archived }

    /**
     * 会话业务类型。<br>
     * - {@link #GENERAL} 通用：项目内普通对话，prompt 由 project 默认 prompt 提供；<br>
     * - {@link #ALLOCATION} 博主分配：AI 协助从 roster / 博主库里挑博主，默认启用 creator_search / project_roster_* 工具；<br>
     * - {@link #BLOGGER_BRIEF} 博主方案：为 creator 做投放方案 / 内容策划，自动注入该博主人设标签/最近笔记；<br>
     * - {@link #CONTENT_REVIEW} 内容审稿：对该博主的交付稿做审阅；<br>
     * - {@link #DATA_TRACK} 数据追踪：看该博主的数据。
     */
    public enum SessionType {
        GENERAL,
        ALLOCATION,
        BLOGGER_BRIEF,
        CONTENT_REVIEW,
        DATA_TRACK
    }
}
