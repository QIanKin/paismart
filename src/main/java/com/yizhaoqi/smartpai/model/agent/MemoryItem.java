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
 * 长期记忆条目。
 * 两类来源：
 *  1. MemoryCompactor 对一段连续被"压缩"的消息生成的摘要（source=compaction）
 *  2. 用户/系统显式标注的事实条目（source=user_note, fact, preference 等）
 *
 * 向量化：真正的向量存放在 ES 的 agent_memory 索引（字段 embedding），本表只存副本元数据 + fullText，
 * 方便按 sessionId/projectId 回溯。MemoryRecallService 查询时走 ES ANN + 本表回查。
 */
@Data
@Entity
@Table(name = "agent_memory_items", indexes = {
        @Index(name = "idx_amem_session", columnList = "session_id"),
        @Index(name = "idx_amem_project", columnList = "project_id"),
        @Index(name = "idx_amem_user", columnList = "user_id"),
        @Index(name = "idx_amem_source", columnList = "source")
})
public class MemoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "org_tag", length = 64)
    private String orgTag;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "project_id")
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Source source;

    /** 简短标题（8~32 字），用于 UI 展示和 LLM context 里的 memory 摘要行 */
    @Column(length = 128)
    private String title;

    /** 完整摘要内容；LLM recall 时按需注入 */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String fullText;

    /** 对应 ES agent_memory 索引的文档 id；为 null 表示尚未向量化（异步任务补） */
    @Column(name = "es_doc_id", length = 64)
    private String esDocId;

    /** 覆盖的消息区间 [fromSeq, toSeq]，source=compaction 时有值 */
    @Column(name = "from_seq")
    private Integer fromSeq;

    @Column(name = "to_seq")
    private Integer toSeq;

    /** 估算 token，便于 context 预算 */
    @Column(name = "token_estimate")
    private Integer tokenEstimate;

    /** 过期时间，null 表示不过期 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @JsonIgnore
    @CreationTimestamp
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Source { compaction, fact, user_note, preference, entity }
}
