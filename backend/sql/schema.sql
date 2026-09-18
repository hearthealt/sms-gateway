-- ============================================================================
-- 短信网关 · 建库 / 升级脚本（单文件）
-- ============================================================================
--
-- 用法（**先选好库再执行**）：
--
--     mysql -u root -p sms_gateway < schema.sql
--
-- 或者先建库再灌：
--
--     CREATE DATABASE sms_gateway DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--     mysql -u root -p sms_gateway < schema.sql
--
-- ----------------------------------------------------------------------------
-- 【这个文件是幂等的】—— 全新库、已有库、跑几遍都行
--
-- 分成三段，每段各自保证可重复执行：
--   一、建表        用 CREATE TABLE IF NOT EXISTS
--   二、补列 / 补索引  先查 information_schema 再决定动不动（见下）
--   三、种子数据     只在表为空时插入
--
-- 之所以要写存储过程来做第二、三段：MySQL 8.0 **不支持**
-- `ADD COLUMN IF NOT EXISTS` / `DROP INDEX IF EXISTS`（那是 MariaDB 的扩展），
-- 也不支持 `CREATE INDEX IF NOT EXISTS`。所以只能自己查 information_schema 判断，
-- 否则无法做到"跑第二遍不报错"。
--
-- ----------------------------------------------------------------------------
-- 【刻意没有 CREATE DATABASE / USE】
--
-- 这个文件以前开头有一句硬编码的 `USE sms_gateway;`。它造成过一个真实的坑：
-- 有人用 `mysql <测试库> < schema.sql` 往测试库里灌，脚本自己切回了生产库，
-- 建表因为是 IF NOT EXISTS 而无声通过，接着种子数据的 INSERT 就重复插进了生产数据。
--
-- 现在由调用方在命令行上指定库，文件本身不切库 —— 灌错库会直接报
-- "No database selected"，而不是静默写到别处去。
-- （docker-entrypoint-initdb.d 的用法不受影响：容器启动时已按 MYSQL_DATABASE 选好库。）
--
-- ----------------------------------------------------------------------------
-- 运行前请确认：你这个 MySQL 的 character_set_client 与文件编码一致。
-- 中文 Windows 版 MySQL 的默认 client 字符集常常是 gbk，直接灌 UTF-8 的脚本会报
-- `Data too long for column 'rule_name'` 这类看起来毫不相干的错。加参数即可：
--
--     mysql --default-character-set=utf8mb4 -u root -p sms_gateway < schema.sql
-- ============================================================================


-- ============================================================================
-- 一、建表
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Device table (sms_device)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sms_device (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    device_id VARCHAR(128) NOT NULL UNIQUE COMMENT 'Unique device identifier',
    device_token VARCHAR(256) NOT NULL COMMENT 'Device auth token (HMAC-SHA256)',
    enroll_secret_hash VARCHAR(64) DEFAULT NULL COMMENT 'SHA-256 of the re-enrollment secret. NULL = 该设备尚未启用重注册校验（本次变更之前注册的老设备），需由管理员签发一次恢复码来启用',
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
    INDEX idx_status (status),
    -- 每个已鉴权的设备请求都要按 device_token 反查设备（DeviceAuthInterceptor 的必经之路），
    -- 这一列以前没有索引 —— 设备数上万后每次请求都是全表扫描，会把同库的核心写入一起拖慢。
    INDEX idx_device_token (device_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 注：这里以前还有一个 INDEX idx_device_id (device_id)，与 device_id 上的 UNIQUE
-- 完全重复（唯一约束自带索引），白占一份写放大，已删除。

-- ---------------------------------------------------------------------------
-- 2. SMS message table (sms_message)
-- ---------------------------------------------------------------------------
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
    -- source_hash 是**唯一**的：一段内容一行。代码侧的 findBySourceHash 返回 Optional、
    -- 建表侧唯一，两边一直是这个约定 —— 曾经有一处重复短信的插入路径违反它，
    -- 导致那条 INSERT 必然撞约束、被兜成 500，设备端于是无限重试。
    UNIQUE KEY uk_source_hash (source_hash),
    INDEX idx_device_id (device_id),
    INDEX idx_sender (sender),
    INDEX idx_status (status),
    INDEX idx_phone_sender_time (phone, sender, receive_time),
    INDEX idx_code (code),
    INDEX idx_receive_time (receive_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3. SMS collect rule table (sms_collect_rule)
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 4. Admin user table (admin_user)
-- ---------------------------------------------------------------------------
-- 种子账号不在此处插入，由后端 DataInitializer 启动时按
-- application.yml 的 app.admin.default-username / default-password 创建，
-- 避免在 SQL 里硬编码密码哈希。
-- 需要**轮换**一个已存在账号的密码时用 APP_ADMIN_RESET_PASSWORD（见 DataInitializer）。
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

-- ---------------------------------------------------------------------------
-- 5. API key table (api_key)
-- ---------------------------------------------------------------------------
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


-- ============================================================================
-- 二、补列 / 补索引（只为**已存在的旧库**服务）
--
-- 上面的 CREATE TABLE IF NOT EXISTS 对已有表是空操作，所以旧库缺的列和索引
-- 要在这里补上。全新库跑这段时，检查发现都已存在，什么都不会做。
-- ============================================================================

DROP PROCEDURE IF EXISTS sms_gateway_schema_upgrade;

DELIMITER //
CREATE PROCEDURE sms_gateway_schema_upgrade()
BEGIN

    -- ---- 补列：重注册密钥 ----------------------------------------------
    --
    -- 背景：原注册接口对**已存在**的 deviceId，会把该设备的令牌原样返还给任何
    -- 知道这个 deviceId 的人 —— 等于「知道设备号就能冒充这台设备」。而设备号在
    -- 管理后台列表、设备端界面、任何截图里都可见。
    --
    -- 现在设备首次注册时自带一个随机密钥，服务端只存它的 SHA-256；之后凡是该
    -- deviceId 已存在的注册都必须带上它。留 NULL 表示「本次变更之前注册的老设备」，
    -- 它们不会再被返还令牌，需管理员在控制台签发一次恢复码来启用。
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'sms_device'
          AND COLUMN_NAME = 'enroll_secret_hash'
    ) THEN
        ALTER TABLE sms_device
            ADD COLUMN enroll_secret_hash VARCHAR(64) DEFAULT NULL
            COMMENT 'SHA-256 of the re-enrollment secret. NULL = 尚未启用重注册校验';
    END IF;

    -- ---- 补索引：device_token -------------------------------------------
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'sms_device'
          AND INDEX_NAME = 'idx_device_token'
    ) THEN
        CREATE INDEX idx_device_token ON sms_device (device_token);
    END IF;

    -- ---- 删冗余索引：idx_device_id ---------------------------------------
    -- 与 device_id 上的 UNIQUE 重复（唯一约束自带索引），只增加写放大。
    IF EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'sms_device'
          AND INDEX_NAME = 'idx_device_id'
    ) THEN
        DROP INDEX idx_device_id ON sms_device;
    END IF;

    -- ---- 种子数据：只在表为空时插入 ---------------------------------------
    --
    -- 用「表为空」而不是「逐条判断这条规则在不在」：后者会在管理员**故意删掉**
    -- 某条默认规则之后，下次执行又把它加回来。与 DataInitializer 建管理员账号
    -- 的策略（只在表为空时建）保持一致。
    --
    -- 匹配语义见 CollectRuleEngine：
    --   一条规则命中 = 发送方命中 且（关键词为空 或 关键词命中正文）
    --   priority 降序，首条命中即定论；全部不命中默认采集（保底不丢验证码）
    -- match_type 对发送方与关键词共用，所以「任意发送方」在 LIKE 下写 %、
    -- 在 REGEX 下必须写 .* —— 正则里的 % 是普通字符，写成 '%' 永远匹配不上。
    IF NOT EXISTS (SELECT 1 FROM sms_collect_rule) THEN
        INSERT INTO sms_collect_rule
            (rule_name, sender_pattern, keyword_pattern, match_type, action, priority, enabled, description)
        VALUES
            ('验证码短信', '%',  '验证码',                   'LIKE',  'collect', 100, 1, '所有含"验证码"的短信，任意发送方'),
            ('国际验证码', '.*', 'code|verification|verify', 'REGEX', 'collect', 100, 1, '英文验证码，任意发送方'),
            ('营销类忽略', '.*', '营销|广告|退订',           'REGEX', 'ignore',   10, 1, '明显营销短信');
    END IF;

END //
DELIMITER ;

CALL sms_gateway_schema_upgrade();
DROP PROCEDURE sms_gateway_schema_upgrade;
