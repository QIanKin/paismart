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
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 已安装的 Skill 元数据。一个 Skill 对应磁盘上一个目录 + SKILL.md：
 *  skill-root/
 *   ├─ SKILL.md    ← 必需
 *   ├─ scripts/    ← 可选；被 BashTool 执行时注入 PATH
 *   └─ references/ ← 可选；LLM 可以按需读取的外部资料
 *
 * source:
 *   - BUILTIN：PaiSmart 内置 skill（跟随 jar 发布）
 *   - LOCAL：研发/运维手动放到服务器 skills 根目录的
 *   - INSTALLED：通过 API 上传/注册的第三方 skill
 *
 * 可见性：
 *  - ownerOrgTag=null → 全局 skill；
 *  - ownerOrgTag=X    → 仅租户 X 可见。
 */
@Data
@Entity
@Table(name = "agent_skills",
        uniqueConstraints = @UniqueConstraint(name = "uq_skill_name_org", columnNames = {"name", "owner_org_tag"}),
        indexes = {
                @Index(name = "idx_skill_enabled", columnList = "enabled"),
                @Index(name = "idx_skill_source", columnList = "source")
        })
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 96)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(length = 32)
    private String version;

    @Column(length = 128)
    private String homepage;

    /** YAML front-matter 里 metadata.openclaw 的原文（如 emoji、requires.bins、install 配置） */
    @Lob
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    /** SKILL.md 去掉 front-matter 后的正文 markdown；SkillContextProvider 直接注入 */
    @Lob
    @Column(name = "body_md", columnDefinition = "MEDIUMTEXT")
    private String bodyMd;

    /** 用于热重载判定：内容哈希变化才 merge 回 DB 并重新对外提示 */
    @Column(name = "body_hash", length = 64)
    private String bodyHash;

    /** 绝对路径：skill 目录（包含 SKILL.md 的父目录） */
    @Column(name = "root_path", length = 512)
    private String rootPath;

    /** scripts/ 目录里以 newline 分隔的相对脚本文件名（供 BashTool 注入 PATH 前预览） */
    @Lob
    @Column(name = "scripts_inventory", columnDefinition = "TEXT")
    private String scriptsInventory;

    /** JSON：{"bins":["ffmpeg","yt-dlp"]}，供 BashTool 启动前做 which 检查 */
    @Lob
    @Column(name = "required_bins", columnDefinition = "TEXT")
    private String requiredBinsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source = Source.LOCAL;

    @Column(name = "owner_org_tag", length = 64)
    private String ownerOrgTag;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 上次磁盘 mtime，用于 SkillLoader 快速 diff */
    @Column(name = "last_loaded_at")
    private LocalDateTime lastLoadedAt;

    @JsonIgnore
    @CreationTimestamp
    private LocalDateTime createdAt;

    @JsonIgnore
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Source { BUILTIN, LOCAL, INSTALLED }
}
