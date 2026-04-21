-- =====================================================================
-- 2026-04 · 登录式 Cookie 采集（A 子项目）相关数据库迁移
--
-- 幂等脚本：多次执行不会报错。
--   · 新表 xhs_login_sessions：登录会话生命周期（QR 采集事件、成败审计）
--   · 既有表 xhs_cookies：加 source / login_session_id 两列，标识 cookie 来源
--
-- 执行方式：
--   docker compose -f docker-compose.prod.yml exec mysql \
--     mysql --default-character-set=utf8mb4 -uroot -p<PWD> smartpai \
--     < docs/db-migration-2026-04-xhs-login.sql
--
-- 也可靠 JPA 的 ddl-auto=update 自动建 xhs_login_sessions，
-- 但加列步骤 JPA 不保证顺序，建议走本脚本显式执行一次。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------- 1. 新表 xhs_login_sessions ----------

CREATE TABLE IF NOT EXISTS xhs_login_sessions (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id         VARCHAR(64)  NOT NULL,
    owner_org_tag      VARCHAR(64)  NOT NULL,
    created_by_user_id VARCHAR(64)  NOT NULL,
    platforms          VARCHAR(255) NOT NULL,           -- 请求的平台列表（逗号分隔）
    status             VARCHAR(16)  NOT NULL,           -- PENDING/QR_READY/SCANNED/CONFIRMED/SUCCESS/FAILED/EXPIRED/CANCELLED
    qr_data_url        TEXT         NULL,               -- 成功后清空
    captured_platforms VARCHAR(255) NULL,
    missing_platforms  VARCHAR(255) NULL,
    error_message      VARCHAR(512) NULL,
    started_at         DATETIME     NOT NULL,
    finished_at        DATETIME     NULL,
    expires_at         DATETIME     NOT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_xhs_login_session_id (session_id),
    KEY idx_xhs_login_org_status (owner_org_tag, status),
    KEY idx_xhs_login_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XHS 扫码登录会话';


-- ---------- 2. 扩展 xhs_cookies 表 ----------

-- 加 source 列（MANUAL / QR_LOGIN / SEED）
SET @needs_source := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'xhs_cookies'
      AND COLUMN_NAME = 'source'
);
SET @ddl := IF(@needs_source = 0,
    'ALTER TABLE xhs_cookies ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''MANUAL'' COMMENT ''MANUAL/QR_LOGIN/SEED''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 加 login_session_id 列，指回 xhs_login_sessions.session_id
SET @needs_link := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'xhs_cookies'
      AND COLUMN_NAME = 'login_session_id'
);
SET @ddl := IF(@needs_link = 0,
    'ALTER TABLE xhs_cookies ADD COLUMN login_session_id VARCHAR(64) NULL COMMENT ''来源 xhs_login_sessions.session_id''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填历史数据（若 source 为空，标记 MANUAL；若是 seeder 插入的，保留 MANUAL，没关系）
UPDATE xhs_cookies SET source = 'MANUAL' WHERE source IS NULL;


-- ---------- 3. 完成提示 ----------

SELECT '✅ db-migration-2026-04-xhs-login.sql executed. xhs_login_sessions + xhs_cookies.source/login_session_id ready.' AS status;
