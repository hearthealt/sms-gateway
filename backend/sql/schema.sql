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

-- ----------------------------------------------------------------------------
-- 【这里自己声明字符集，不要依赖上面那条客户端参数】
--
-- 真正被坑到的是 Docker：官方 MySQL 镜像启动时会把 docker-entrypoint-initdb.d/ 下的
-- 脚本灌进去，而那个 mysql 客户端不带任何字符集参数、容器里又没有 locale，
-- 于是退回 latin1 —— 种子数据里的中文一进库就是 `éªŒè¯ç` 这样的乱码
-- （UTF-8 的字节被当成 latin1 读），**而且不报任何错**，直到有人打开采集规则页才看得出来。
--
-- 写在这里就与调用方式无关了：客户端怎么起、带不带参数，都按 utf8mb4 解释这份 SQL。
-- ----------------------------------------------------------------------------
SET NAMES utf8mb4;


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
    -- 设备主动上报「网关已停止」的时刻。与 last_heartbeat_at 一起判在线：
    -- 心跳在 90 秒内 **且** 晚于这个时刻才算在线。没有它，用户点了停止之后
    -- 管理后台还要再显示 90 秒在线。为空 = 从没报过（老版本 App、或进程被杀没来得及报）。
    reported_offline_at DATETIME DEFAULT NULL COMMENT 'Device reported gateway stopped at',
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
    duplicate_count INT NOT NULL DEFAULT 0 COMMENT '这段内容后来又收到几次；只有正本行会累加',
    receive_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Time SMS was received on device',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_device_message (device_id, local_message_id),
    -- 去重按**设备**做：同一台设备收到同一段内容才算重复（只把计数与更新时间推到那一行上），
    -- 不同设备各自的记录互不影响 —— 「这台机器的码到没到」的答案必须是它自己那一行。
    -- 原先这里是全局唯一的 uk_source_hash(一段内容一行)，两台手机收到同一段内容时，
    -- 第二台会被算成第一台的重复而没有自己的记录。
    UNIQUE KEY uk_device_source_hash (device_id, source_hash),
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

-- ---------------------------------------------------------------------------
-- 6. Device enrollment token table (device_enroll_token) —— 单行表
-- ---------------------------------------------------------------------------
-- 「快速连接」用的接入口令：管理后台生成、随二维码下发，新设备**首次注册**时必须携带。
--
-- 挡的是这条路径：/api/device/register 必须免鉴权（设备得先能注册才拿得到令牌），
-- 在那之前没有任何东西能区分「自己人」和「碰巧知道地址的人」—— 于是任何知道服务器
-- 地址的人都能注册一台设备进来。
--
-- 单行表（id 恒为 1），不是每设备一张：现场用法是「一张码贴在那里，谁来了扫一下」，
-- 全局一个口令 + 一键轮换最贴合，管理成本也最低。
--
-- token 存**明文**，取舍同 api_key：管理后台要把它显示进二维码，不可回读的哈希
-- 做不到这一点。库被读走等同于口令泄露，属内部系统换取「管理员随时能再看到它」的取舍。
--
-- **表为空、或 enabled=0 时不校验**，注册接口退回开放 —— 这是刻意的向后兼容：
-- 老部署灌完这份脚本后，新设备不会突然接不进来。要启用准入控制，得管理员在
-- 控制台「快速连接」里显式生成口令。
--
-- 准入只在首次注册时判定。**已存在设备的重新注册不看这张表**，它走的是
-- sms_device.enroll_secret_hash 那条路（见 DeviceService.verifyEnrollment）——
-- 否则一开启口令，所有老设备重装后就全部失联了。
CREATE TABLE IF NOT EXISTS device_enroll_token (
    id BIGINT PRIMARY KEY COMMENT '恒为 1：这是单行表，不需要自增',
    token VARCHAR(64) NOT NULL COMMENT '接入口令明文，32 字节随机 → base64url（43 字符）',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0 = 关闭准入校验，注册接口退回开放',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- 7. 转发渠道（notify_channel）
-- ---------------------------------------------------------------------------
-- v2「消息转发」：短信到达后由服务端主动推给群机器人 / 个人 IM。
-- 配置方法见 docs/notify-channel-setup.md；支持的渠道见 NotifyChannelType。
--
-- config_cipher 存的是**密文**（AES-GCM，密钥来自 app.notify.encrypt-key）。
-- 与 api_key 的明文存储刻意相反：那里管理端要支持「点显示看完整值」，
-- 而 webhook 地址创建时贴一次就够，之后只需要知道「配好了」，没有回显明文的场景。
-- 能做加密就做 —— 库被读走时，密文在没有密钥的情况下读不出 webhook URL。
--
-- **没有 template / mask_policy 这类列。** 转发出去的就是短信原文，不做模板、
-- 不打码。这意味着群里所有人都能用看到的验证码登录对应账号 —— 这是配置渠道时
-- 就该知道的事（谁在那个群里），不是运行时要拦的事。管理端在建渠道时提示一次。
CREATE TABLE IF NOT EXISTS notify_channel (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '渠道名，如「运维群」',
    type VARCHAR(32) NOT NULL COMMENT '渠道类型，见 NotifyChannelType',
    config_cipher TEXT NOT NULL COMMENT '加密后的渠道配置 JSON（AES-GCM）',
    rate_limit_per_min INT NOT NULL DEFAULT 20 COMMENT '每分钟上限，0=不限',
    max_retries INT NOT NULL DEFAULT 3,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    -- 健康状态：连续失败到阈值会自动停用，见 NotifyDispatcher
    last_success_at DATETIME DEFAULT NULL,
    last_error_at DATETIME DEFAULT NULL,
    last_error VARCHAR(500) DEFAULT NULL COMMENT '**脱敏后**的错误摘要，不得出现完整 webhook URL',
    consecutive_failures INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled),
    INDEX idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 8. 转发规则（notify_route + notify_route_channel）
-- ---------------------------------------------------------------------------
-- 刻意**不复用 sms_collect_rule**，关键一条是语义不同：
-- 采集规则是「首条命中即定论」的二值判定（采不采集），而路由是**并集** ——
-- 一条短信可以同时进「运维群」和「我的微信」，规则必须累加而不是短路。
-- 另一条：采集规则决定「哪些短信进系统」，是数据入口策略；路由决定「进来的短信
-- 发给谁」，是通知策略。混在一张表里，改通知会牵动数据采集。
CREATE TABLE IF NOT EXISTS notify_route (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    route_name VARCHAR(100) NOT NULL,
    -- 匹配条件语义与 sms_collect_rule 一致，共用 RuleMatcher 的匹配逻辑
    sender_pattern VARCHAR(255) DEFAULT NULL COMMENT '空=不限发送方',
    keyword_pattern VARCHAR(255) DEFAULT NULL COMMENT '空=不限正文',
    match_type VARCHAR(20) NOT NULL DEFAULT 'LIKE' COMMENT 'EXACT/LIKE/REGEX',
    device_id VARCHAR(128) DEFAULT NULL COMMENT '限定设备，空=不限',
    phone_pattern VARCHAR(64) DEFAULT NULL COMMENT '限定接收号码（多卡场景），空=不限',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 一条规则配多个渠道用关联表，而不是在 notify_route 上加一个 channel_id：
-- 后者要一组规则配 N 个渠道就得复制 N 行，改匹配条件时得改 N 处。
CREATE TABLE IF NOT EXISTS notify_route_channel (
    route_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    PRIMARY KEY (route_id, channel_id),
    INDEX idx_channel (channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 9. 投递记录（notify_delivery）—— 兼作 outbox
-- ---------------------------------------------------------------------------
-- 事务性发件箱：这一行的 INSERT 与 sms_message 的 INSERT 在**同一个事务**里。
-- 事务提交则投递任务必然存在，回滚则两者都不存在 —— 不存在「短信存了但没转发」
-- 的中间态。这也是「转发必须做在服务端」的根本理由：放在设备上就没有事务可依附。
--
-- 兼作投递日志，省掉一张表。
--
-- **刻意不存渲染后的短信正文**：正文含验证码明文，落库等于把验证码写了两遍、
-- 且第二遍没有 TTL。排查用 last_error + response_code 够；要看正文按
-- sms_message_id 关联回 sms_message（那里本来就有）。
--
-- **告警也走这张表**（source_type=ALERT），不另起一张表 + 第二套调度器：
-- 那要复制退避、限流、卡死回收、自动停用、脱敏与控制台页面约 400 行并发代码，
-- 两份必然分叉。代价是下面那几列，以及三处必须按 source_type 分支的读取点
-- （见 NotifyDispatcher.sendOne / NotifyDeliveryService.toView）。
CREATE TABLE IF NOT EXISTS notify_delivery (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- 告警投递为 NULL。**唯一索引 uk_sms_channel 原样保留**：InnoDB 的唯一索引
    -- 允许出现多个 NULL，所以告警行不受它约束，而短信行的幂等性一个字没变。
    sms_message_id BIGINT DEFAULT NULL COMMENT '关联短信；告警投递为 NULL（source_type=ALERT）',
    channel_id BIGINT NOT NULL,
    source_type VARCHAR(16) NOT NULL DEFAULT 'SMS' COMMENT 'SMS / ALERT。默认 SMS，老行因此不需要迁移',
    alert_type VARCHAR(32) DEFAULT NULL COMMENT '见 AlertType 枚举；仅 ALERT 行有值',
    subject_key VARCHAR(160) DEFAULT NULL COMMENT '告警主体，如 device:42 / channel:7。去重与「是哪台设备」都靠它',
    -- 与短信那一侧**刻意相反**：告警的正文摘要落库。短信不落是因为正文含验证码明文；
    -- 而重新渲染告警要回头解析 subject_key 对应的设备行 —— 那一行可能已经被删了，
    -- 于是控制台上会出现一行没有内容的告警。
    alert_summary VARCHAR(255) DEFAULT NULL COMMENT '告警正文摘要，仅 ALERT 行有值',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SUCCESS/FAILED/DEAD',
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '到点才捞，限流推迟也写这里',
    response_code INT DEFAULT NULL COMMENT '对端 HTTP 状态码',
    last_error VARCHAR(500) DEFAULT NULL COMMENT '脱敏后的错误摘要',
    sent_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 幂等：同一条短信对同一渠道只会有一条投递记录
    UNIQUE KEY uk_sms_channel (sms_message_id, channel_id),
    -- 调度主查询：(status, next_retry_at)
    INDEX idx_dispatch (status, next_retry_at),
    INDEX idx_channel_created (channel_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- 10. 故障告警规则（alert_rule + alert_rule_channel）
-- ---------------------------------------------------------------------------
-- 与转发规则（notify_route）**刻意分开**，与「不复用 sms_collect_rule」是同一个
-- 理由，只是这一层更直接：路由的匹配维度是短信的（发送方 / 正文关键词 / 接收号码），
-- 而告警的维度是「什么坏了 + 哪台设备」。把 sender_pattern 临时改成「告警类型」，
-- 就是让一列承载两种语义 —— 改一次告警就要回头读一遍转发逻辑。
--
-- 与转发规则相同的地方是刻意保留的：渠道走关联表（一条规则配多个渠道）、
-- device_id 用精确相等（设备号是标识符，用 LIKE 匹配它只会让人以为可以模糊查）。
--
-- **没有总开关。** 转发总开关（notify.enabled）已经管着整条投递链路，再加一个
-- alert.enabled 会让两者的交互变成要解释的事（「转发开着、告警关着」算不算配错？），
-- 而每条规则已经有自己的 enabled。
--
-- alert_type 为 NULL 表示**不限类型**（所有告警都发），与 notify_route 里
-- 「空模式 = 不限制这一项」的写法一致。
CREATE TABLE IF NOT EXISTS alert_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_name VARCHAR(100) NOT NULL,
    alert_type VARCHAR(32) DEFAULT NULL COMMENT '见 AlertType 枚举；NULL = 不限类型',
    device_id VARCHAR(128) DEFAULT NULL COMMENT '限定设备（业务标识，精确相等）；空 = 不限',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 一条规则配多个渠道，与 notify_route_channel 同构。
CREATE TABLE IF NOT EXISTS alert_rule_channel (
    rule_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    PRIMARY KEY (rule_id, channel_id),
    INDEX idx_channel (channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- 11. 远程指令（device_command）—— 服务端 → 设备的下行通道
-- ---------------------------------------------------------------------------
-- 在此之前这个协议是单向的：设备上报，服务端只能看。管理员要改一台设备的状态
-- 必须有人走到那台手机前面。这张表让「远程动作」成为可能。
--
-- **指令搭心跳响应下发，不新开长轮询。** 设备没有第二条常连通道（内网部署、
-- 没有 FCM），而它本来就每 30 秒问一次；长轮询要多造一整条连接生命周期
-- （hold 超时、重连退避、与在线判定冲突），换来的只是 30 秒的延迟改进 ——
-- 而这几条指令没有一条是时间敏感的。详见 DeviceCommandService 的类注释。
--
-- **SENT 会被重复下发，直到回执或过期。** 这是「回执丢了怎么办」的答案：
-- 效果执行成功但回执丢在路上时，指令停在 SENT，下一个心跳再下一次，设备按
-- command id 认出「这条我做过」因而不重复执行、只重发一次回执。
-- 「恰好一次投递」需要服务端的 claim/lease 协议，而设备端每种指令本来就是幂等的
-- （起一个已在跑的服务、删一次已上传行都是空操作），投递语义的强度用不着一张新协议。
--
-- 过期时刻**在创建时按指令类型定死并落库**，不用一个全局的 TTL 配置：
-- 「一周前下发的停止」如果在设备回来后生效，现场只会看到一台莫名不动的机器，
-- 而没有人记得为什么。落库而不是查询时算，是为了让当时的策略可审计。
CREATE TABLE IF NOT EXISTS device_command (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- 按**主键**而不是业务标识：设备行被删时这条命令一并删（见 AdminDeviceService.delete），
    -- 与投递记录必须赶在短信之前删是同一个道理 —— 没有外键，不主动删就是永久孤儿。
    device_id BIGINT NOT NULL COMMENT 'FK to sms_device.id',
    device_code VARCHAR(128) NOT NULL COMMENT '设备业务标识冗余，设备行不在了也认得出',
    command_type VARCHAR(32) NOT NULL COMMENT '见 DeviceCommandType 枚举',
    argument VARCHAR(255) DEFAULT NULL COMMENT '指令参数，目前只有 SET_PHONE 用到（号码）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/ACKED/FAILED/EXPIRED/CANCELLED',
    attempts INT NOT NULL DEFAULT 0 COMMENT '已下发次数。设备重复收到靠 command id 去重，这个计数只用于放弃判定',
    next_deliver_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '到点才下发。否则一条未回执的指令会在每个心跳上都重发一次',
    sent_at DATETIME DEFAULT NULL,
    expires_at DATETIME NOT NULL COMMENT '过期即不再下发。**按类型定死**，见 DeviceCommandType.ttl()',
    acked_at DATETIME DEFAULT NULL,
    result_detail VARCHAR(255) DEFAULT NULL COMMENT '设备回报的一句话。**受控文案**：设备侧只发固定短语，服务端截断到 255；不得回传短信正文',
    issued_by VARCHAR(64) DEFAULT NULL COMMENT '签发这条指令的管理员账号。「谁把这台设备停了」是现场最常问的一句话',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 心跳下发时的主查询：(device_id, status)，且按 id 升序取前 N 条
    INDEX idx_device_status (device_id, status),
    -- 过期清理任务按 (status, expires_at) 扫
    INDEX idx_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 注：刻意**没有**再加一个「取设备最新一条指令」用的索引。管理端列表按
-- (device_id, id desc) 走 idx_device_status 的前缀即可，而设备数不大。
-- 新表不进 sms_gateway_schema_upgrade：CREATE TABLE IF NOT EXISTS 已经幂等，
-- 那个过程只服务「给**已存在**的表补列/补索引」。


-- ---------------------------------------------------------------------------
-- 12. 外发短信（sms_outbound）—— 服务端下发、由设备发出去的短信
-- ---------------------------------------------------------------------------
-- 在它之前，这套系统只有「收码」这半边：设备上报，服务端识码、转发。
-- 表让「补发一条短信」「给运营发一条通知短信」这类需求不必再拿起别人的手机。
--
-- **与 device_command 分开两张表**，虽然两者都走心跳下发：
-- 指令是「让设备做个动作」，外发短信是「一条有正文、要计费、要回执的业务数据」，
-- 它的状态机、列表页、计费口径、甚至重试语义都不同（见下面那条）。
--
-- **外发短信只在心跳里下发一次，绝不重发。**
-- 这是它与指令最关键的差别：指令重发的代价是「设备可能多做一次」（而且那些动作
-- 本来幂等），而短信重发的代价是**真的又发一条出去**、又计费一次、收信方多收一条。
-- 所以回执丢了就只能记成「结果未知」，不能靠重发去确认。
--
-- 正文**存库**（与 sms_message 的取舍相反）：收进来的短信不存正文是因为里面有验证码
-- 明文，多存一遍就是多一处泄漏面；而外发短信的正文本来就是我们自己写进去的，
-- 不存反而等于「发出去一条谁也不知道内容的短信」。
CREATE TABLE IF NOT EXISTS sms_outbound (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    device_id BIGINT NOT NULL COMMENT 'FK to sms_device.id',
    device_code VARCHAR(128) NOT NULL COMMENT '设备业务标识冗余，设备行不在了也认得出',
    phone VARCHAR(32) NOT NULL COMMENT '收信方号码',
    content VARCHAR(1000) NOT NULL COMMENT '正文。上限 500 字符由服务端校验（见 SmsOutboundService）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/DISPATCHED/SENT/FAILED/CANCELLED/UNKNOWN',
    source VARCHAR(16) NOT NULL COMMENT 'ADMIN / API —— 谁发起的',
    created_by VARCHAR(64) DEFAULT NULL COMMENT '管理员账号或 API Key 名称。「谁发出去的」必须查得到',
    outbound_key VARCHAR(64) NOT NULL COMMENT '幂等键，随心跳下发、由设备回执时带回',
    segments INT DEFAULT NULL COMMENT '分段数（一条长短信可能被拆成多条计费）',
    sim_slot INT DEFAULT NULL COMMENT '指定卡槽；NULL = 设备自己选一张',
    error_reason VARCHAR(255) DEFAULT NULL COMMENT '失败原因，**受控文案**（对端码 / 异常类名）',
    -- 「交给设备」与「设备发出去」必须分开记：前者只说明我们把它塞进了心跳响应，
    -- 后者才说明那条短信真的离开了那台手机。合成一个就会在回执丢的时候
    -- 把「不知道发没发」说成「已发出」—— 而这条短信是**要计费**的。
    dispatched_at DATETIME DEFAULT NULL COMMENT '交给设备的时刻（心跳下发）',
    sent_at DATETIME DEFAULT NULL COMMENT '设备回报「已发出」的时刻',
    delivered_at DATETIME DEFAULT NULL COMMENT '设备回报「对方已收到」的时刻（多数运营商拿不到）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_outbound_key (outbound_key),
    -- 心跳下发时的主查询：(device_id, status)
    INDEX idx_device_status (device_id, status),
    -- 每日限额要数「这台设备今天发了多少条」，按 (device_id, created_at) 走
    INDEX idx_device_created (device_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- 13. 运行期配置（sys_config）
-- ---------------------------------------------------------------------------
-- 管理后台「系统设置」页读写这张表，**改完立即生效、不用重启**。
--
-- 原先这些值在 application.yml 里，改了必须重启。项目里已有先例：api_key 当初
-- 就是从 yml 的 app.client.token 迁到库里的，理由一模一样。
--
-- **哪些适合放这里**：运维过程中会想调、且调完就想看到效果的（转发总开关、
-- 短信保留天数、转发调度参数）。
-- **哪些不适合**：密钥（加密密钥尤其不能进库 —— 用库里的密钥解库里的密文是循环
-- 依赖）、引导类配置（管理员初始密码）、以及极少改的超时/并发数。
--
-- 值一律按**字符串**存，类型转换由 SysConfigKey 上的 Type 决定：
-- 这样加一项新配置只要加一个枚举值，**不用改这张表的结构**，
-- 所以这里**没有种子数据** —— 缺哪个键就用枚举里的默认值。
CREATE TABLE IF NOT EXISTS sys_config (
    config_key VARCHAR(64) PRIMARY KEY COMMENT '配置键，见 SysConfigKey；不认识的行会被忽略',
    config_value VARCHAR(500) NOT NULL COMMENT '值，一律按字符串存',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- 14. 运行事件（event_log）
-- ---------------------------------------------------------------------------
-- 起因是一条验证码短信**静默丢失**：设备说没传上去、服务端说没收到，两边都查不到。
-- 这张表回答「这条上报在服务端这一侧到底被判成了什么」——存下 / 重复 /
-- 被采集规则忽略 / 被拒，外加注册与令牌异常、设备上下线。
--
-- **刻意不存短信正文与验证码**：与 sms_message 不同，这张表是长期留存的运行记录
-- （默认保留 7 天），正文和验证码不该在里面出现第二遍。要正文按 sms_message_id
-- 关联回那张表（它自己有一份过期策略）。
--
-- **刻意不存 device_token / enroll_secret**：令牌可以冒充设备，写进任何长期留存的
-- 地方都是净损失。认证失败只记「令牌无效」这个结论。
--
-- device_code 冗余存业务标识：设备行被删掉之后（删设备会连同它的历史事件一起清，
-- 但删除这个动作本身要留痕）列表上仍然认得出它是什么设备。
CREATE TABLE IF NOT EXISTS event_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(48) NOT NULL COMMENT '见 EventType 枚举',
    level VARCHAR(16) NOT NULL DEFAULT 'INFO' COMMENT 'INFO / WARN / ERROR',
    device_id BIGINT DEFAULT NULL COMMENT '关联 sms_device.id；设备已删或未认出时为 NULL',
    device_code VARCHAR(128) DEFAULT NULL COMMENT '设备业务标识，设备行不在了也认得出',
    local_message_id VARCHAR(128) DEFAULT NULL COMMENT '设备侧消息 ID，用于与 sms_message 对照',
    sms_message_id BIGINT DEFAULT NULL COMMENT '关联 sms_message.id；该行可能已被保留策略清掉，展示必须容错',
    sender VARCHAR(100) DEFAULT NULL COMMENT '短信发送方',
    phone VARCHAR(32) DEFAULT NULL COMMENT '接收号码',
    reason VARCHAR(255) DEFAULT NULL COMMENT '判定结果 / 原因码 / HTTP 状态。**不得写入正文或验证码**',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at),
    INDEX idx_type_created (event_type, created_at),
    INDEX idx_device_created (device_id, created_at)
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

    -- ---- 投递记录：支持与短信无关的告警投递 ------------------------------
    --
    -- 告警（设备离线、渠道被自动停用、远程指令失败）也走这条投递链路，
    -- 而不是另起一张表 + 第二套调度器：那要复制退避、限流、卡死回收、自动停用、
    -- 脱敏与控制台页面约 400 行并发代码，两份必然分叉。
    --
    -- **sms_message_id 改为可空。** 唯一索引 uk_sms_channel 原样保留：
    -- InnoDB 的唯一索引允许出现多个 NULL，所以告警行（NULL, channel）不受它约束，
    -- 而短信行的幂等性一个字都没变 —— 不需要动索引。
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'notify_delivery'
          AND COLUMN_NAME = 'sms_message_id'
          AND IS_NULLABLE = 'NO'
    ) THEN
        ALTER TABLE notify_delivery
            MODIFY COLUMN sms_message_id BIGINT NULL
            COMMENT '关联短信；告警投递为 NULL（source_type=ALERT）';
    END IF;

    -- source_type 是**载荷分支**，不是元数据：NotifyDeliveryService 与 NotifyDispatcher
    -- 都按它决定「去哪儿取正文」。默认 'SMS' 让已有的行不需要数据迁移。
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'notify_delivery'
          AND COLUMN_NAME = 'source_type'
    ) THEN
        ALTER TABLE notify_delivery
            ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'SMS'
            COMMENT 'SMS / ALERT。默认 SMS，老行因此不需要迁移';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'notify_delivery'
          AND COLUMN_NAME = 'alert_type'
    ) THEN
        ALTER TABLE notify_delivery
            ADD COLUMN alert_type VARCHAR(32) DEFAULT NULL COMMENT '见 AlertType 枚举；仅 ALERT 行有值';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'notify_delivery'
          AND COLUMN_NAME = 'subject_key'
    ) THEN
        ALTER TABLE notify_delivery
            ADD COLUMN subject_key VARCHAR(160) DEFAULT NULL
            COMMENT '告警主体，如 device:42 / channel:7。去重与「是哪台设备」都靠它';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'notify_delivery'
          AND COLUMN_NAME = 'alert_summary'
    ) THEN
        ALTER TABLE notify_delivery
            ADD COLUMN alert_summary VARCHAR(255) DEFAULT NULL
            COMMENT '告警正文摘要，仅 ALERT 行有值';
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
