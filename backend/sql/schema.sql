-- SMS Gateway Database Schema & Seed (consolidated, v2)
-- ============================================================
-- Run: mysql -u root -p sms_gateway < schema.sql
--
-- 本文件是 v2 最终态建库脚本，已合并原 migration_v2.sql 的全部内容：
--   - sms_device 的遥测字段 platform / app_version / battery /
--     network / charging / pending_count
--   - admin_user 管理后台账号表
--   - api_key 外部调用方密钥表（取代 application.yml 里的 app.client.token）
--
-- 适用于初始化全新数据库。仓库不再提供针对已存在 v1 库的增量升级脚本，
-- 老库升级请比对本文件手工补列。
-- ============================================================

CREATE DATABASE IF NOT EXISTS sms_gateway DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sms_gateway;

-- ============================================================
-- 1. Device table (sms_device)
-- ============================================================
CREATE TABLE IF NOT EXISTS sms_device (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    device_id VARCHAR(128) NOT NULL UNIQUE COMMENT 'Unique device identifier',
    device_token VARCHAR(256) NOT NULL COMMENT 'Device auth token (HMAC-SHA256)',
    device_name VARCHAR(255) DEFAULT NULL COMMENT 'Device friendly name',
    phone_number VARCHAR(32) DEFAULT NULL COMMENT 'Associated phone number',
    platform VARCHAR(50) DEFAULT NULL COMMENT 'OS platform, e.g. android',
    app_version VARCHAR(50) DEFAULT NULL COMMENT 'Client app version',
    battery INT DEFAULT NULL COMMENT 'Battery percentage 0-100',
    network VARCHAR(20) DEFAULT NULL COMMENT 'Network type, e.g. wifi/4g/5g',
    charging TINYINT(1) DEFAULT NULL COMMENT 'Whether the device is charging',
    pending_count INT DEFAULT NULL COMMENT 'Pending upload count reported by device',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE, DISABLED',
    last_heartbeat_at DATETIME DEFAULT NULL COMMENT 'Last heartbeat timestamp',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_device_id (device_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 2. SMS message table (sms_message)
-- ============================================================
CREATE TABLE IF NOT EXISTS sms_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    device_id BIGINT NOT NULL COMMENT 'FK to sms_device',
    local_message_id VARCHAR(128) NOT NULL COMMENT 'Client-side message ID for idempotency',
    phone VARCHAR(32) NOT NULL COMMENT 'Phone number',
    sender VARCHAR(100) NOT NULL COMMENT 'SMS sender',
    content TEXT NOT NULL COMMENT 'SMS content',
    code VARCHAR(20) DEFAULT NULL COMMENT 'Extracted verification code',
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED, DUPLICATE, PROCESSED',
    source_hash VARCHAR(64) NOT NULL COMMENT 'SHA-256 hash of content for dedup',
    receive_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Time SMS was received on device',
    is_read TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_device_message (device_id, local_message_id),
    UNIQUE KEY uk_source_hash (source_hash),
    INDEX idx_device_id (device_id),
    INDEX idx_sender (sender),
    INDEX idx_status (status),
    INDEX idx_phone_sender_time (phone, sender, receive_time),
    INDEX idx_code (code),
    INDEX idx_receive_time (receive_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 3. SMS collect rule table (sms_collect_rule)
-- ============================================================
CREATE TABLE IF NOT EXISTS sms_collect_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_name VARCHAR(100) NOT NULL,
    sender_pattern VARCHAR(255) NOT NULL COMMENT 'Sender number pattern (like %, exact match, or regex)',
    keyword_pattern VARCHAR(255) DEFAULT NULL COMMENT 'Keyword content pattern',
    match_type VARCHAR(20) NOT NULL DEFAULT 'EXACT' COMMENT 'EXACT, LIKE, REGEX',
    description VARCHAR(500) DEFAULT NULL,
    action VARCHAR(20) NOT NULL DEFAULT 'collect' COMMENT 'collect, ignore',
    priority INT NOT NULL DEFAULT 0 COMMENT 'Higher priority wins',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sender_pattern (sender_pattern),
    INDEX idx_enabled (enabled),
    INDEX idx_enabled_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 4. Admin user table (admin_user)
-- ============================================================
-- 种子账号不在此处插入，由后端 DataInitializer 启动时按
-- application.yml 的 app.admin.default-username / default-password 创建，
-- 避免在 SQL 里硬编码密码哈希。
CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE COMMENT 'Login username',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt password hash',
    display_name VARCHAR(100) DEFAULT NULL COMMENT 'Display name',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DISABLED',
    last_login_at DATETIME DEFAULT NULL COMMENT 'Last successful login',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 5. API key table (api_key)
-- ============================================================
-- 外部调用方凭证，由管理后台「API 密钥」页签发，用于 /api/sms/wait 的鉴权。
-- 替代了原先写死在 application.yml 的 app.client.token（已删除）。
--
-- api_key 存的是**明文**：管理后台列表默认打码，但要支持「点显示看完整值」，
-- 所以不能只存哈希。库被读走等同于密钥泄露，属内部系统的取舍。
-- expires_at 为 NULL 表示不过期。
CREATE TABLE IF NOT EXISTS api_key (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '用途备注，便于识别调用方',
    api_key VARCHAR(64) NOT NULL COMMENT '密钥明文，格式 sk- + 32 位十六进制',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    expires_at DATETIME DEFAULT NULL COMMENT '过期时间，NULL 表示不过期',
    last_used_at DATETIME DEFAULT NULL COMMENT '最后一次通过鉴权的时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_key (api_key),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 6. Seed collect rules (only data — no mock devices/sms)
-- ============================================================
-- 匹配语义见 CollectRuleEngine：
--   一条规则命中 = 发送方命中 且（关键词为空 或 关键词命中正文）
--   priority 降序，首条命中即定论；全部不命中默认采集（保底不丢验证码）
-- match_type 对发送方与关键词共用，所以「任意发送方」在 LIKE 下写 %、在
-- REGEX 下必须写 .* —— 正则里的 % 是普通字符，写成 '%' 永远匹配不上。
INSERT INTO sms_collect_rule (rule_name, sender_pattern, keyword_pattern, match_type, action, priority, enabled, description)
VALUES
('验证码短信',   '%',  '验证码',                   'LIKE',  'collect', 100, 1, '所有含"验证码"的短信，任意发送方'),
('国际验证码',   '.*', 'code|verification|verify', 'REGEX', 'collect', 100, 1, '英文验证码，任意发送方'),
('营销类忽略',   '.*', '营销|广告|退订',           'REGEX', 'ignore',   10, 1, '明显营销短信');
