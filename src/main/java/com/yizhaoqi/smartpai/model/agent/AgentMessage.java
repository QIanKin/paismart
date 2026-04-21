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

import java.time.LocalDateTime;

/**
 * 一条消息。对标 OpenAI 消息协议，但做了裁剪：
 *  - role: user / assistant / tool / system
 *  - assistant 消息可能带 tool_calls（JSON 数组）和/或文本内容
 *  - tool 消息必须带 tool_call_id，content 是工具结果的 JSON 字符串
 *
 * 一个 turn 的 messageGroupId 一致：user 的问题 + 其引发的一串 assistant/tool 消息共享同一组，
 * 方便按 turn 级别拉记录、压缩、重放。
 */
@Data
@Entity
@Table(name = "agent_messages", indexes = {
        @Index(name = "idx_am_session", columnList = "session_id,seq"),
        @Index(name = "idx_am_group", columnList = "message_group_id"),
        @Index(name = "idx_am_role", columnList = "role")
})
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 会话内部单调递增序号，读历史用 */
    @Column(nullable = false)
    private Integer seq;

    /** 一轮对话（user 起头，到 agent 终结）共享一个 group id（UUID） */
    @Column(name = "message_group_id", length = 64, nullable = false)
    private String messageGroupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    /** 纯文本内容。tool 消息这里是工具结果的 JSON 字符串 */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    /** assistant 带 tool_calls 时的原始 OpenAI 结构（JSON 数组）；其他角色为 null */
    @Lob
    @Column(name = "tool_calls", columnDefinition = "MEDIUMTEXT")
    private String toolCallsJson;

    /** role=tool 时对应的 tool_call_id，用于与上一条 assistant 的 tool_calls[i].id 对齐 */
    @Column(name = "tool_call_id", length = 64)
    private String toolCallId;

    /** role=tool 时被调用的工具名（冗余，便于分析）； */
    @Column(name = "tool_name", length = 64)
    private String toolName;

    /** 工具执行耗时（ms），仅 role=tool */
    @Column(name = "tool_duration_ms")
    private Long toolDurationMs;

    /** 是否被压缩器归档（归档后不再直接拉出作为 prompt context） */
    @Column(name = "compacted", nullable = false)
    private Boolean compacted = false;

    /** 估算 token（写入时粗估，方便 context 预算计算） */
    @Column(name = "token_estimate")
    private Integer tokenEstimate;

    @JsonIgnore
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum Role { user, assistant, tool, system }
}
