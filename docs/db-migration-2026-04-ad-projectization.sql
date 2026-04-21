-- ============================================================
-- PaiSmart Schema Migration: 广告项目化 + 博主名册 + 自定义字段扩展
-- 日期：2026-04-18
-- 背景：
--   当前项目用 Hibernate ddl-auto=update 自动建/加列，新部署（干净 DB）
--   完全不用跑这个脚本；它是给"已经在跑老数据"的实例做兜底用的。
--
-- 变更列表（全部都是加表 / 加列，向后兼容）：
--   A. agent_sessions 加 session_type + creator_id 两列
--   B. agent_projects 加 custom_fields 列
--   C. 新表 project_creators
--   D. project_creators 加 custom_fields 列（在 C 之外额外确保）
--   E. creator_custom_fields.entity_type 允许新值（不动 schema，只是值扩展）
--
-- 跑这个脚本之前建议：
--   1) 先 dump 一份数据：mysqldump PaiSmart > backup.sql
--   2) 用 MYSQL CLI 或 IDE 分段执行，每段注意观察报错
-- ============================================================

-- ========== A. agent_sessions ==========
-- ChatSession 现在区分会话业务类型（GENERAL / ALLOCATION / BLOGGER_BRIEF / CONTENT_REVIEW / DATA_TRACK）
-- 并且可以绑定到具体博主（creator_id）。老数据自动落到 GENERAL。
ALTER TABLE agent_sessions
    ADD COLUMN session_type VARCHAR(24) NOT NULL DEFAULT 'GENERAL' AFTER project_id,
    ADD COLUMN creator_id BIGINT NULL AFTER session_type;

CREATE INDEX idx_as_creator ON agent_sessions (creator_id);
CREATE INDEX idx_as_session_type ON agent_sessions (session_type);

-- ========== B. agent_projects ==========
-- 项目级自定义字段 JSON（客户名 / campaign 目标 / 行业 / 预算…）
ALTER TABLE agent_projects
    ADD COLUMN custom_fields TEXT NULL;

-- ========== C. project_creators（广告项目 ↔ 博主 名册） ==========
CREATE TABLE IF NOT EXISTS project_creators (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    owner_org_tag VARCHAR(64) NOT NULL,
    stage VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',
    priority INT NOT NULL DEFAULT 50,
    quoted_price DECIMAL(12, 2) NULL,
    currency VARCHAR(8) NULL,
    assigned_to_user_id BIGINT NULL,
    project_notes TEXT NULL,
    added_by VARCHAR(64) NULL,
    custom_fields TEXT NULL,
    created_at DATETIME NULL,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pc_project_creator (project_id, creator_id),
    KEY idx_pc_project (project_id),
    KEY idx_pc_creator (creator_id),
    KEY idx_pc_stage (stage),
    KEY idx_pc_org (owner_org_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ========== D. 兼容旧 project_creators（如果这个表是 update 阶段建的，
--     可能少了 custom_fields 列；用下面这条命令确保它存在） ==========
-- 老实例里如果 Hibernate 已建表，custom_fields 列可能不存在：
--   SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
--     WHERE TABLE_NAME='project_creators' AND COLUMN_NAME='custom_fields';
-- 如果上面查不到，再跑下面这句：
-- ALTER TABLE project_creators ADD COLUMN custom_fields TEXT NULL;

-- ========== E. creator_custom_fields 允许的 entity_type 值扩展 ==========
-- 原值：'creator' / 'account' / 'post'
-- 新值：追加 'project' / 'project_creator'
-- 因为列是 VARCHAR(16)，不需要 DDL 修改，只是业务层放开了校验。
--   如果你这边有严格的 CHECK 约束或 ENUM 列类型（我们没有），再加个 ALTER。

-- ========== 结束 ==========
-- 验收：
--   1) DESCRIBE agent_sessions; -- 有 session_type / creator_id 两列
--   2) DESCRIBE agent_projects; -- 有 custom_fields
--   3) DESCRIBE project_creators; -- 存在，有 custom_fields 列
--   4) SHOW INDEX FROM agent_sessions; -- 有 idx_as_creator / idx_as_session_type
