# SMS Gateway v2 设计文档 · 消息转发

> 状态：**设计稿，未实施**　|　目标版本：v2.0　|　撰写日期：2026-09-17
> 关联文档：[README](../README.md)

---

## 1. 这一版要解决什么

v1 的短信只有两个出口：**管理后台页面看**，和**外部系统调 API 取**（`/api/sms/list`、`/api/sms/wait`）。两者都要求调用方主动来拉。

v2 增加**推送**：短信到达后，服务端主动把它转发到群机器人 / 个人 IM / 第三方推送服务。

典型用法：运维群里实时看到某台设备的验证码；或者验证码直接推到你自己的微信，不用登录后台。

---

## 2. 先说清楚哪些能做、哪些不能做

这一节写在最前面，因为它直接决定方案边界。你原始需求里列的「微信 / QQ / 企业微信 / 飞书」，实际可落地的范围比字面窄得多。

### 2.1 可行性总表

| 你想发到 | 能不能做 | 怎么做 | 结论 |
| --- | --- | --- | --- |
| **企业微信群** | ✅ 能做，且最省事 | 群机器人 webhook，URL 里带 key | **推荐** |
| **飞书群** | ✅ 能做 | 自定义机器人 webhook，可选加签 | **推荐** |
| **钉钉群** | ✅ 能做 | 自定义机器人 webhook，需配安全设置 | **推荐** |
| **Telegram** | ✅ 能做 | Bot API，token 在 URL 里 | **推荐** |
| **Slack** | ✅ 能做 | Incoming Webhook | 可行，限流严 |
| **你自己的微信（个人）** | ⚠️ 只能绕道 | WxPusher / Server酱 / PushPlus 这类第三方推送服务 | 可行参见 §2.3 |
| **企业微信里的个人** | ✅ 能做 | 应用消息，需要企业主体 + 自建应用 | 可行但重 |
| **微信公众号推送** | ❌ 不建议 | 见 §2.4，有违规风险 | **不实现** |
| **个人微信群**（家庭群、朋友群） | ❌ 做不到 | 官方无任何 API | **不实现** |
| **个人 QQ 群 / QQ 好友** | ❌ 不建议 | 官方主动推送已被下线；社区方案会封号 | **不实现** |

### 2.2 QQ：为什么不做

这是本次调研里最重要的发现，需要单独说明。

**官方途径已被关闭。** QQ 机器人官方公告《关于 QQ 机器人消息推送策略调整通知》（2025-04-16 发布）原文：

> 由于业务运营策略调整，预计 4 月起 QQ 机器人将不再支持「主动消息推送功能」，平台将陆续进行能力收敛。

官方文档源码中至今保留红色警告：**「主动推送能力于 2025 年 4 月 21 日起不再提供，接口调用时会收到错误信息。」**

这对本项目的打击是**根本性**的：短信转发的本质就是「由外部事件触发、无用户前置交互的主动推送」——恰好是被砍掉的那个能力。

> 📌 需要说明：官方渲染版文档（2026-07-21 更新）里又重新列出了主动消息频控表（群聊 60 qpm / 单关系 20 qpm / 每日 1000 条）。**两份官方材料互相矛盾**，现状是灰度收敛中。据社区消息（非官方），2025 年 6 月起腾讯以「残缺版」恢复：群聊场景默认关闭，需群主手动在机器人设置页打开开关，且用户可单方面关闭接收。
>
> 即便按最乐观的口径，也要受「群主手动开开关 + 20 qpm + 用户可拒绝」三重约束，而官方原文明确「如果设置了关闭，主动消息一律发送失败」。**把高可靠送达的场景建立在这种能力上，工程上不可接受。**

**社区途径有明确的合规与封号风险。**

- 腾讯《QQ 软件许可及服务协议》第 8.2.2 条明文禁止：**「形式包括但不限于使用插件、外挂或非腾讯经授权的第三方工具/服务接入本软件和相关系统」**，且逐字列出了「机器人、自动化程序、脚本」。
- 技术上这些方案（NapCat / LLBot 走 NTQQ 客户端 Hook，Lagrange 走协议逆向）在服务端视角就是「伪装成正常客户端」，被检测到会判定为风险账号。
- 实际封禁形态：风险警告 → 踢下线 → 限制社交功能 → 封 7 天 → **永久冻结且申诉失败**。被封的是**用户自己的主 QQ 号**。
- 生态本身也在萎缩：**go-cqhttp 已停止维护**（作者原话「协议库的时代已经过去」，代码冻结于 2024-05）；LiteLoaderQQNT 已归档；Shamrock 仓库已 404。目前只有 NapCat 活跃。

> ⚠️ 协议原文来自搜索转录——腾讯规则页 `rule.tencent.com` 有反爬，本次未能直连抓取，建议人工核对原页面。

**结论：QQ 不进入 v2 实现范围。** 若你确实需要腾讯系推送，用**企业微信群机器人**——它是真正的「一个 URL 即可推送」，合规且稳定。

### 2.3 「转发到我的微信」的唯一可行路径

个人微信没有官方机器人 API。能把消息送到你个人微信的，只有两类：

**A. 第三方推送服务**（推荐，接入成本最低）

它们本质是「注册一个公众号/应用，用模板消息把消息推给关注了的你」，把合规问题留给了它们自己：

| 服务 | API | 免费额度 | 备注 |
| --- | --- | --- | --- |
| **WxPusher** | `POST /api/send/message` | **无每日条数上限**（限 ~2 QPS） | **官方文档明确点名「短信转发系统」是其 SPT 模式典型场景** |
| Server酱 Turbo | `POST https://sctapi.ftqq.com/<SendKey>.send` | **5 条/天** | 免费额度对短信转发基本不够用 |
| PushPlus | `POST http://www.pushplus.plus/send` | 未实名 0 条；**实名后 200 次/天** | 接口是**异步**的，返回 200 只代表收到请求 |

**WxPusher 是首选。** 它的 SPT 极简推送模式只需一个 GET 请求、无需注册应用、无每日条数上限：

```
GET https://wxpusher.zjiecode.com/api/send/message/{SPT}/{内容}
```

代价是 SPT 泄漏后任何人都能给你发消息——按 §6 的凭证管理处理。

**B. 企业微信应用消息** — 需要企业主体、自建应用、维护 access_token 生命周期（见 §7.2）。如果你已经有企业微信组织，这是合规且可控的方案。

### 2.4 公众号模板消息：能用，但不该用

澄清一个常见误解：**模板消息并没有下线**。官方文档顶部原文写着「模板消息能力可正常使用」，接口文档更新于 2025-11-26，无任何下线公告。网上的「已下线」说法源于 2023 年的一次改版（历史模板库停止新增，改走类目模板库）被内容农场误读。

**但对本项目它是错的工具**，因为官方运营规范明文规定：

> 模板消息的定位是**用户触发后的通知消息，不允许在用户没做任何操作或未经用户同意接收的前提下，主动下发消息给用户**。
> 某用户仅仅是关注服务号，没有和服务号及其所属主体有任何交互行为，却无故收到该服务号下发的模板消息，**属于违规行为**。

短信转发正是「无用户触发的主动下发」，**语义直接冲突**。违规后果是阶梯性接口封禁直至封号。此外还有：仅认证服务号可用、必须用户已关注、服务端 IP 白名单强制（出口 IP 浮动会直接 40164）。

**结论：不实现公众号模板消息。**

---

## 3. 现状分析

改造前的转发链路（`SmsService.receiveSms()`）：

```
设备上报 → ① 幂等校验 → ② source_hash 去重 → ③ 采集规则判定
         → ④ 存库 → ⑤ 解析验证码 → ⑥ 写 Redis 缓存 sms:code:{phone}
         → ⑦ Redis pub/sub 广播 sms:channel:{phone}:{sender} → 返回
```

问题点：

| # | 问题 | 影响 |
| --- | --- | --- |
| 1 | **⑦ 是即发即忘的 pub/sub，无持久化** | 进程在 ④ 之后、⑦ 之前崩溃，设备已收到 200 不会重传，**这条短信的转发永久丢失** |
| 2 | **`@EnableAsync` 已开但未配线程池**（`SmsGatewayApplication.java:8`） | 直接用 `@Async` 会走 `SimpleAsyncTaskExecutor`，每任务新建线程且不复用，高频 IO 场景会打爆线程数 |
| 3 | **无任何出站 HTTP 客户端配置** | 需要新增超时/连接池/重试策略 |
| 4 | **无渠道概念，无投递记录** | 无法回答「这条短信发出去了吗」「为什么没收到」 |

因此 v2 不能简单地在 ⑦ 后面挂一个 `send()` 调用，必须先把投递做成**可持久化、可重试、可观测**的。

---

## 4. 总体设计

### 4.1 架构

```
                    ┌──────────────────────────────────────────┐
设备上报 ──────────▶│  SmsService.receiveSms()  【单一事务】      │
                    │   ①幂等 ②去重 ③采集规则 ④存库 ⑤解析验证码 │
                    │   ⑥Redis缓存+pub/sub（v1 行为不变）        │
                    │   ⑦匹配转发规则 → INSERT notify_delivery  │◀── 新增（outbox）
                    └────────────────────┬─────────────────────┘
                                         │ 事务提交
                                         ▼
                    ┌──────────────────────────────────────────┐
                    │  NotifyDispatcher（定时轮询 PENDING）      │
                    │   · 渠道级串行，防并发触发限流             │
                    │   · 令牌桶限流（Redis，多实例安全）        │
                    │   · 失败按 Full Jitter 退避重试            │
                    │   · 不可重试错误直接终态                    │
                    └────────────────────┬─────────────────────┘
                                         ▼
                    ┌──────────────────────────────────────────┐
                    │  NotifySender 适配器（按渠道类型分派）      │
                    │  DirectSender: 企微/飞书/钉钉/TG/Slack/…  │
                    │  TokenSender:  企微应用消息（需 TokenMgr）│
                    └──────────────────────────────────────────┘
```

### 4.2 核心决策：事务性发件箱（Transactional Outbox）

**不新增消息队列中间件**（不引入 Kafka/RabbitMQ），直接用 MySQL 表当队列。理由：

- `notify_delivery` 的插入与 `sms_message` 的插入在**同一事务**内完成。事务提交则投递任务必然存在，事务回滚则两者都不存在——**不存在「短信存了但没转发」的中间态**。
- 项目现有依赖只有 MySQL + Redis（见 `backend/pom.xml`），保持这个体量。投递量级（个人短信网关，日峰值几百条）远达不到需要专业 MQ 的程度。
- `notify_delivery` 表还兼做投递日志，省掉一张表。

代价是轮询有延迟（可接受，默认 1s）。若将来量级上来，再把 dispatch 换成 Redis Stream 或 MQ，**表结构不用改**。

### 4.3 关键流程

**写入侧（事务内）**

```java
// SmsService.receiveSms() 内，保存 sms_message 之后
if (!decision.ignored()) {
    List<NotifyRoute> routes = notifyRouteEngine.match(request, device, code);
    for (Long channelId : collectChannelIds(routes)) {
        notifyDeliveryRepository.save(NotifyDelivery.pending(message.getId(), channelId));
    }
}
```

**投递侧（事务外，定时任务）**

```java
@Scheduled(fixedDelay = 1000)
public void dispatch() {
    // 1. 取到期任务，按 channel 分组 —— 同一渠道串行，不同渠道并行
    // 2. 每渠道先取令牌，取不到则推迟 next_retry_at
    // 3. 渲染模板 → 调 sender → 更新状态
}
```

---

## 5. 数据模型（schema v3）

新增 4 张表。**不改动任何现有表**，v1 → v2 升级可零停机。

### 5.1 `notify_channel` — 转发渠道

```sql
CREATE TABLE notify_channel (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '渠道名，如「运维群」',
    type VARCHAR(32) NOT NULL COMMENT '见 §5.5 渠道类型枚举',
    -- 凭据密文。AES-GCM 加密，密钥来自 app.notify.encrypt-key。
    -- 与 api_key 表不同，webhook 地址创建后没有任何需要回显明文的场景，
    -- 所以这里可以加密存储，管理端只回显打码值。
    config_cipher TEXT NOT NULL COMMENT '加密后的渠道配置 JSON',
    -- 消息模板，含 {code} {phone} {sender} {content} {time} {device} 占位符
    template TEXT DEFAULT NULL COMMENT '空则用该渠道类型的默认模板',
    mask_policy VARCHAR(16) NOT NULL DEFAULT 'MASKED'
        COMMENT 'FULL=发完整验证码 / MASKED=打码 / NONE=完全不提验证码',
    rate_limit_per_min INT NOT NULL DEFAULT 20 COMMENT '每分钟上限，0=不限',
    max_retries INT NOT NULL DEFAULT 3,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    -- 健康状态
    last_success_at DATETIME DEFAULT NULL,
    last_error_at DATETIME DEFAULT NULL,
    last_error VARCHAR(500) DEFAULT NULL COMMENT '脱敏后的错误摘要',
    consecutive_failures INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled),
    INDEX idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 5.2 `notify_route` — 转发规则

```sql
CREATE TABLE notify_route (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    route_name VARCHAR(100) NOT NULL,
    -- 匹配条件，语义与 sms_collect_rule 保持一致（复用同一套匹配工具方法）
    sender_pattern VARCHAR(255) DEFAULT NULL COMMENT '空=不限发送方',
    keyword_pattern VARCHAR(255) DEFAULT NULL COMMENT '空=不限正文',
    match_type VARCHAR(20) NOT NULL DEFAULT 'LIKE' COMMENT 'EXACT/LIKE/REGEX',
    device_id VARCHAR(128) DEFAULT NULL COMMENT '限定设备，空=不限',
    -- 限定接收号码，用于多卡场景；空=不限
    phone_pattern VARCHAR(64) DEFAULT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notify_route_channel (
    route_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    PRIMARY KEY (route_id, channel_id),
    INDEX idx_channel (channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 5.3 为什么路由规则要独立于采集规则

**不复用 `sms_collect_rule`**，理由有三：

1. **语义模型不同。** 采集规则是**首条命中即定论**（`CollectRuleEngine` 的 `decide()` 命中第一条就 return），因为「采不采集」是个二值判定。但路由是**并集**语义——一条短信可以同时进「运维群」和「我的微信」，规则应该累加而不是短路。
2. **改动频率和权限不同。** 采集规则决定「哪些短信进系统」，是数据入口策略；路由规则决定「进来的短信发给谁」，是通知策略。两者混在一张表里，改通知会牵动数据采集。
3. **需要给规则配多个渠道。** 塞进现有表就只能加一个 `channel_id` 列，一组规则配 N 个渠道要复制 N 行，改匹配条件时得改 N 处。用关联表更自然。

**但匹配逻辑复用。** `CollectRuleEngine` 里的 `matchesValue()` / `likeToRegex()` / 正则缓存是通用的，应提取为 `RuleMatcher` 工具类供两者共用——避免第二套 LIKE→正则的实现出现分叉（v1 已经在 `HashUtil`/`PhoneUtil` 上体现过这个原则）。

### 5.4 `notify_delivery` — 投递记录（兼 outbox）

```sql
CREATE TABLE notify_delivery (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sms_message_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/SENDING/SUCCESS/FAILED/DEAD',
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调度用，到点才捞',
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
```

> **注意：这张表不存渲染后的消息正文。** 正文含明文验证码，落库等于把验证码写了两遍、且第二遍没有 TTL。排查问题用 `last_error` + `response_code` 足够；需要看正文时按 `sms_message_id` 关联回 `sms_message`（那里本来就有）。

### 5.5 渠道类型枚举

```java
public enum NotifyChannelType {
    // —— 一 URL 直发，无状态 ——
    WECOM_BOT,        // 企业微信群机器人
    FEISHU_BOT,       // 飞书自定义机器人
    DINGTALK_BOT,     // 钉钉自定义机器人
    TELEGRAM_BOT,     // Telegram Bot
    SLACK_WEBHOOK,    // Slack Incoming Webhook
    WXPUSHER,         // WxPusher（含 SPT 模式）
    SERVERCHAN,       // Server酱
    PUSHPLUS,         // PushPlus
    GENERIC_WEBHOOK,  // 通用 webhook（自定义 URL + 可选 HMAC 签名）
    // —— 需要 access_token 生命周期管理 ——
    WECOM_APP         // 企业微信应用消息
}
```

---

## 6. 安全设计

这是本设计里**最需要你认真看**的一节。v1 的产品价值是「把验证码安全地交到正确的人手里」；转发功能一旦做错，会直接摧毁这个价值。

### 6.1 验证码泄漏是首要风险

**问题的本质**：验证码的安全性建立在「只有持有手机的人能看到」之上。一旦转发进一个群，**群里所有人都能看到并能使用它**——不只是「别人也看到了」，而是**你的验证码等于作废**（谁手快谁用），更严重的是**未授权的人可以登录你的账号**。

一个 20 人的运维群，转发一条登录验证码，等于把账号登录权限给了这 20 个人。

**对策：按渠道分级，默认脱敏。**

渠道上有 `mask_policy` 字段：

| 值 | 行为 | 适用场景 |
| --- | --- | --- |
| `NONE` | 消息里完全不出现验证码，只说「收到一条验证码短信」 | **群渠道默认** |
| `MASKED` | 打码，如 `4839**` | **群渠道默认可选** |
| `FULL` | 完整验证码 | 仅个人渠道 |

**关键约束：`FULL` 在群类渠道（`WECOM_BOT` / `FEISHU_BOT` / `DINGTALK_BOT` / `SLACK_WEBHOOK`）必须显式确认才能开启**，UI 上给一个红色警示弹窗：

> ⚠️ 转发完整验证码到群聊意味着**群内任何人都可以使用该验证码登录你的账号**。确认这是你想要的吗？

打码实现要注意：**只打码验证码，不要打码整条正文**，否则可能漏掉正文里同时出现的验证码。渲染时先定位 `{code}` 占位符替换为打码值，而对 `{content}` 占位符里的验证码做二次打码（复用 `SmsService.CODE_PATTERNS` 定位）。

### 6.2 Webhook 凭据加密存储

**渠道配置用 AES-GCM 加密后入库**，密钥来自环境变量 `app.notify.encrypt-key`（不进配置文件默认值）。

与 v1 的 `api_key` 明文存储形成对比——那里 `schema.sql` 注释解释了为什么必须明文（管理端要支持「点显示看完整值」）。**webhook 没有这个需求**：创建时贴一次，之后只需要知道「配好了」，不需要回显。所以这里可以做到比 v1 更好。

配套要求：
- 管理端列表和详情**只回显打码值**（`https://qyapi.weixin.qq.com/...key=693a****5aaa`）
- 修改渠道配置时，**留空表示不修改**，而不是清空
- 日志、异常堆栈、`last_error` 里**一律不得出现完整 URL**（对 URL 做 `?key=` / `access_token=` 参数名脱敏）

### 6.3 SSRF 防护（通用 Webhook 渠道必须做）

`GENERIC_WEBHOOK` 允许管理员填任意 URL，服务端会主动去请求它——这正是 OWASP 定义的 SSRF 场景。**如果管理后台账号被盗，攻击者可以借此探测内网。**

必须在发送前校验目标地址：

**封禁网段**

| 类别 | 网段 |
| --- | --- |
| 云厂商 metadata | `169.254.169.254`、`metadata.google.internal` |
| 回环 | `127.0.0.0/8`、`::1/128`、`0.0.0.0/8` |
| RFC1918 私网 | `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16` |
| 组播 | `224.0.0.0/4`、`ff00::/8` |

**实现要点**
- **枚举全部 A + AAAA 记录逐个校验**（防 DNS pinning / DNS rebinding）
- **禁止跟随重定向**（`HttpClient` 配 `Redirect.NEVER`）
- **解析器分歧即拒绝**：`http://example.com\@evil.com` 这类 URL，WHATWG 解析器读 `example.com`，Python 的 `urllib.parse` 读 `evil.com`。只要主机名在不同解析器下读出来不一致，直接拒绝
- 协议限定 `http`/`https`
- **优先白名单**：OWASP 明确「Deny-lists are bypass-prone. Prefer allow-lists.」— 对固定对接方（企微/飞书/钉钉）用域名白名单；`GENERIC_WEBHOOK` 才需要黑名单兜底

> 顺带：`GENERIC_WEBHOOK` 的 URL 允许内网地址是**合理需求**（对接公司内部系统）。建议做成显式开关 `allow_private_network`，默认关，开启时二次确认并记审计日志。

### 6.4 其他

- 转发动作写审计日志（谁在什么时候改了渠道/规则、测试发送了哪条）
- `notify_delivery` 不存正文（见 §5.4）
- 后台「投递记录」页的正文预览要过打码

---

## 7. 各渠道适配规格

以下规格均为官方文档核对结果，**实现时请以此为准**，不要凭印象写。

### 7.1 一 URL 直发类（`DirectSender`）

无状态，一个 URL 加请求体即可发送。抽象最简单。

#### 企业微信群机器人

- **URL**：`https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<KEY>`
- **鉴权**：无签名，URL 里的 `key` 即全部凭证
- **请求体**：

```json
{ "msgtype": "markdown", "markdown": { "content": "**验证码**：483920" } }
```

- **限流**：**20 条/分钟**
- **坑**：
  - 🚨 按 **UTF-8 字节**限长：`text` ≤ 2048 字节、`markdown`/`markdown_v2` ≤ 4096 字节。**中文 1 字 = 3 字节**，4096 字节实际上只有约 680 个汉字。**超长静默截断，不报错**。
  - `markdown` 不支持表格；要表格得用 `markdown_v2`
  - `markdown` 只有 3 种颜色：`info`(绿) / `comment`(灰) / `warning`(橙红)
  - `markdown_v2` 不支持 `<@userid>` @语法，且客户端 4.1.36 以下会退化成纯文本
  - 官方警告「一定要保护好消息推送的 webhook 地址，避免泄漏！」

> 📌 该能力现已更名为「**消息推送**」（文档更新 2025-08-07），webhook 格式未变。

#### 飞书自定义机器人

- **URL**：`https://open.feishu.cn/open-apis/bot/v2/hook/<HOOK_ID>`
- **限流**：**100 次/分钟，5 次/秒**。官方建议避开 10:00、17:30 等整点/半点
- **请求体大小**：≤ **20 KB**
- **签名算法**（开启加签时）：

```java
// ⚠️ 这是全网最容易写错的地方
String stringToSign = timestamp + "\n" + secret;   // timestamp 单位【秒】
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(stringToSign.getBytes(UTF_8), "HmacSHA256"));
byte[] sign = mac.doFinal(new byte[]{});            // 对待签数据传【空字节】
String signStr = Base64.encode(sign);
```

**注意这是反直觉的**：不是常规的 `HMAC(key=secret, msg=stringToSign)`，而是**把 `stringToSign` 当作 HMAC 的 key，签空数据**。

- **参数位置**：`timestamp` 和 `sign` 放在 **JSON body 里**（与钉钉相反）：

```json
{ "timestamp": "1599360473", "sign": "xxx", "msg_type": "text",
  "content": { "text": "验证码：483920" } }
```

- `timestamp` 距当前 **不得超过 1 小时**
- **坑**：
  - 错误码 `19021` 签名失败 / `19022` IP 不在白名单 / `19024` 关键词未命中 / `9499` Bad Request
  - **自定义机器人拿不到用户 open_id**，@人 需额外建自建应用（官方为防止隐私泄露的硬限制）
  - **同一个机器人无法添加到多个群**——多群转发必须为每个群单独建机器人

#### 钉钉自定义机器人

- **URL**：`https://oapi.dingtalk.com/robot/send?access_token=<TOKEN>`
- **限流**：**20 条/分钟，超过会限流 10 分钟**（惩罚比企微重）
- **签名算法**：

```python
timestamp = str(round(time.time() * 1000))        # ⚠️ 单位【毫秒】（飞书是秒）
string_to_sign = '{}\n{}'.format(timestamp, secret)
hmac_code = hmac.new(secret.encode('utf-8'),       # ⚠️ key 是 secret（飞书是 stringToSign）
                     string_to_sign.encode('utf-8'),
                     digestmod=hashlib.sha256).digest()
sign = urllib.parse.quote_plus(base64.b64encode(hmac_code))
```

- **参数位置**：`timestamp` 和 `sign` 必须拼在 **URL query 上**。官方原文警告：「**sign 字段和 timestamp 字段必须拼接到请求 URL 上，否则会出现 310000 的错误信息**」

**三处与飞书的关键差异汇总**（实现时最容易混）：

| | 钉钉 | 飞书 |
| --- | --- | --- |
| HMAC key | `secret` | `timestamp+"\n"+secret` |
| 待签数据 | `timestamp+"\n"+secret` | **空字节** |
| timestamp 单位 | **毫秒** | **秒** |
| 参数位置 | **URL query** | **JSON body** |

- **坑**：
  - 🚨 **`access_token` 是 webhook 的 token，不是应用的 access_token**。官方在 Java SDK 示例里专门注释强调这点——这是最常见的混淆点。
  - **创建机器人时必须至少配一种安全设置**（自定义关键词 / 加签 / IP 白名单），否则直接被拒。若用关键词方式，**需在转发内容前固定拼一个关键词**（如「短信转发」）才能通过校验。
  - markdown **不支持表格、代码块、分割线**——比企微的 `markdown_v2` 弱得多
  - markdown 的 `title` 字段是**会话列表透出的标题**，不是消息体标题，且**必填**
  - IP 白名单**不支持 IPv6**，不支持 `1.1.1.*` 通配，最多 10 个

#### Telegram Bot

- **URL**：`https://api.telegram.org/bot<TOKEN>/sendMessage`
- **请求体**：

```json
{ "chat_id": "-1001234567890", "text": "验证码：<b>483920</b>", "parse_mode": "HTML" }
```

- **限流**：单 chat **1 条/秒**；群组 **20 条/分钟**。429 响应带 `parameters.retry_after`（秒），**应优先采用它而不是自己的退避计算**
- **坑**：
  - 🚨 **不要用 `MarkdownV2`**。它要求转义 18 个字符（`_ * [ ] ( ) ~ ` > # + - = | { } . !`），而短信正文里 `.` `-` `!` `(` `)` `#` `+` `=` 极其常见，转义地狱。**用 `HTML` 模式，只需转义 `& < >` 三个字符**。
  - `text` 上限 4096 字符（转义后），切分要留余量
  - **bot 不能主动私聊未 `/start` 过的用户**（返回 403）。设计上必须假设「用户先 /start」
  - `disable_web_page_preview` 已被 `link_preview_options` 取代，别用旧字段

#### Slack Incoming Webhook

- **URL**：`https://hooks.slack.com/services/T.../B.../XXXX`
- **限流**：**1 条/秒**。官方警告「超限后继续发送可能导致应用被**永久禁用**」
- **坑**：
  - 🚨 **payload 里的 `channel` / `username` / `icon_emoji` / `icon_url` 全部无效**（官方原文明确说明）。这与大量二手博客说法相反。
  - 用 `blocks` 时**仍必须提供顶层 `text`** 作为通知栏和屏幕阅读器的回退
  - 顶层 `text` 上限 **3000 字符**
  - 错误要分类：`invalid_payload` / `no_service` / `no_active_hooks` / `action_prohibited` **均不可重试**——盲目重试可能触发永久禁用

#### WxPusher

- **URL（标准）**：`POST https://wxpusher.zjiecode.com/api/send/message`
- **URL（SPT 极简）**：`GET https://wxpusher.zjiecode.com/api/send/message/{SPT}/{内容}`
- **请求体**：

```json
{ "appToken": "AT_xxx", "content": "验证码：483920", "contentType": 1,
  "uids": ["UID_xxx"], "topicIds": [123] }
```

- **成功判定**：`code == 1000`（**注意不是 200**）
- **限制**：`content` ≤ 40000 字符且 **UTF-8 ≤ 65535 字节**；单请求 `uids` ≤ 2000、`topicIds` ≤ 5；接口限流约 **2 QPS**
- **免费，无每日条数上限**（官方原文「WxPusher 是免费的推送服务」）

#### Server酱 / PushPlus

| | Server酱 Turbo | PushPlus |
| --- | --- | --- |
| 端点 | `POST https://sctapi.ftqq.com/<SendKey>.send` | `POST http://www.pushplus.plus/send` |
| 参数 | `title`(≤32字符，不能含换行) + `desp` | `token` + `title` + `content` + `template` |
| 成功判定 | `code == 0` | `code == 200` |
| 限流 | 50 条/分钟，免费 **5 条/天** | 实名后 1 分钟 5 次 / 200 次/天 |
| 坑 | — | 🚨**接口是异步的**，返回 200 只代表收到请求，不代表发送成功。需配 `callbackUrl` 或查 `shortCode` 才能确认真实结果。**失败的错误请求也计数** |

### 7.2 需 Token 管理类（`TokenSender`）

只有**企业微信应用消息**进入 v2 范围。

- **换 token**：`GET https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=ID&corpsecret=SECRET` → `expires_in: 7200`
- **发消息**：`POST https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=...`

```json
{ "touser": "UserID1|UserID2", "msgtype": "text", "agentid": 1,
  "text": { "content": "验证码：483920" } }
```

- **限流**：**每应用对同一个成员不可超过 30 次/分钟、1000 次/小时**，超过部分被丢弃
- **TokenManager 必须实现**（官方原文要求）：
  - 缓存 access_token（**不能频繁调 gettoken，否则会被频率拦截**）
  - **按应用隔离存储** —— 企业微信每个应用的 token 彼此独立
  - **提前刷新**，不要等到 7200s 到期
  - 缓存击穿保护（并发只有一个线程去换）
  - 收到 `42001`（token 过期）时**强制刷新并重试一次** —— 官方明确「企业微信可能会出于运营需要**提前使 access_token 失效**」
- **坑**：
  - 🚨 **返回 `invaliduser` / `unlicenseduser` 时发送仍然执行**（部分成功）。网关必须解析这两个字段判断真实投递情况，不能只看 `errcode`
  - `unlicenseduser` = 用户在可见范围内但**没有基础接口许可**（这是收费项）
  - 文本 ≤ 2048 字节，**超长截断**
  - 若管理端开了「在微工作台中始终进入主页」，**微信端只能收到文本且被截断到 20 字节**
  - `enable_duplicate_check` 默认 1800 秒内相同内容不重复下发 —— 对重试场景是双刃剑，需显式设 `0`

### 7.3 通用 Webhook

- 自定义 URL + 自定义 JSON 模板
- 可选 HMAC-SHA256 签名（建议对齐 [Standard Webhooks](https://www.standardwebhooks.com/) 规范：签 `msg_id.timestamp.payload`，用 `webhook-id` / `webhook-timestamp` / `webhook-signature` 三个头）
- **必须做 §6.3 的 SSRF 校验**
- 成功判定：**2xx**；**3xx 算失败且不跟随重定向**

---

## 8. 可靠性设计

### 8.1 重试策略

**Full Jitter 指数退避**（AWS 官方推荐）：

```java
long cap = 3600_000;  // 1 小时
long base = 5_000;    // 5 秒
long sleep = ThreadLocalRandom.current().nextLong(
        Math.min(cap, base * (1L << attempt)));
```

> AWS 的结论：「The no-jitter exponential backoff approach is the **clear loser**」——在 100 个竞争客户端下，加抖动把总调用次数**减少一半以上**。纯指数退避会让重试「成簇」同时到达，反而加剧限流。

**重试排期**（默认 3 次，可配）：

| 尝试 | 时间 |
| --- | --- |
| 1 | 立即 |
| 2 | +5s ~ 10s |
| 3 | +10s ~ 20s |
| 失败 | 进 `DEAD`，管理端可见并可手动重投 |

### 8.2 错误分类：哪些该重试

这是最容易写错的地方——**盲目重试会被对端永久封禁**（Slack 官方明确警告）。

| 分类 | 判定 | 处理 |
| --- | --- | --- |
| **可重试** | 网络超时、连接失败、HTTP 5xx、HTTP 429、限流错误码 | 退避重试 |
| **不可重试（配置错）** | HTTP 401/403、签名失败（飞书 `19021`、钉钉 `310000`）、token 无效（企微 `42001` 除外，那个刷新后可重试一次） | **立即终态**，标记渠道异常 |
| **不可重试（永久）** | Slack `no_service` / `no_active_hooks` / `invalid_payload`、`410 Gone`、机器人被移出群 | **立即终态 + 自动停用渠道** |
| **可重试一次** | 企微 `42001` token 过期 | 强制刷新 token 后重试一次 |
| **部分成功** | 企微应用消息的 `invaliduser` / `unlicenseduser` | 记 warning，**不重试**（重试也不会成功，且浪费配额） |

### 8.3 限流

**每渠道独立令牌桶，用 Redis 实现**（多实例部署安全）。取不到令牌时**不失败，而是把 `next_retry_at` 推迟**——限流是预期内的，不该消耗重试次数。

各渠道默认值（可由 `rate_limit_per_min` 覆盖）：

| 渠道 | 默认（条/分钟） | 依据 |
| --- | --- | --- |
| 企微群机器人 | 20 | 官方硬限 |
| 钉钉群机器人 | 20 | 官方硬限（且超限罚 10 分钟，**建议设 15 留余量**） |
| 飞书机器人 | 100 | 官方 100/min + 5/s |
| Telegram | 20 | 群组 20/min；单 chat 还要 1/s |
| Slack | 20 | 官方硬限 1/s，**建议设 15 留余量** |
| WxPusher | 60 | 约 2 QPS |
| 企微应用消息 | 30 | 官方单成员 30/min |

**同渠道串行**：调度器按 `channel_id` 分组，同一渠道的任务串行执行，避免并发瞬间打满限额。

### 8.4 渠道健康与自愈

- `consecutive_failures` 连续失败达阈值（默认 10）→ **自动停用渠道** + 记录原因
- 停用后管理端显著提示，修好后手动启用
- **自动停用必须能通知出去**，否则「渠道挂了没人知道」——用一个独立的、最简的渠道（如邮件/企微机器人）做告警，避免用同一个可能在挂的渠道
- 「测试发送」按钮（`POST /api/admin/notify/channel/{id}/test`）是配置期的必需能力

---

## 9. 消息模板

模板支持变量占位符，在渠道上可自定义：

| 变量 | 含义 |
| --- | --- |
| `{code}` | 验证码（受 `mask_policy` 影响） |
| `{phone}` | 接收号码 |
| `{sender}` | 发送方 |
| `{content}` | 短信正文（验证码会被二次打码） |
| `{time}` | 接收时间 |
| `{device}` | 设备名 |

**默认模板**（按渠道类型）：

```
【短信转发】
验证码：{code}
发送方：{sender}
设备：{device}
时间：{time}
```

**消息类型降级策略**：内部统一用 Markdown 表示，发送时按渠道能力降级——

```
飞书卡片 → 企微 markdown_v2 → 钉钉 markdown（无表格） → Telegram HTML → Slack blocks
```

**截断必须分渠道处理**：企微/钉钉按 **UTF-8 字节**（中文 3 字节），Telegram/Slack 按**字符**。用同一个截断函数会导致中文长短信被静默截断。

---

## 10. 接口设计

### 10.1 管理端 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/notify/channel/list` | 渠道列表（凭据打码） |
| POST | `/api/admin/notify/channel` | 新建渠道 |
| PUT | `/api/admin/notify/channel/{id}` | 修改（凭据留空=不改） |
| DELETE | `/api/admin/notify/channel/{id}` | 删除 |
| PUT | `/api/admin/notify/channel/{id}/enabled` | 启停 |
| **POST** | `/api/admin/notify/channel/{id}/test` | **发送测试消息** |
| GET | `/api/admin/notify/route/list` | 转发规则列表 |
| POST | `/api/admin/notify/route` | 新建规则 |
| PUT | `/api/admin/notify/route/{id}` | 修改规则 |
| DELETE | `/api/admin/notify/route/{id}` | 删除规则 |
| GET | `/api/admin/notify/delivery/list` | 投递记录（可按渠道/状态过滤） |
| POST | `/api/admin/notify/delivery/{id}/retry` | 手动重投 |

### 10.2 前端页面

沿用现有 `RuleManagement.vue` 的模式（el-card + el-table + el-dialog + `api/rule.ts` 风格的 api 模块）：

- **新增「转发渠道」页** — 渠道卡片列表，按类型显示不同配置表单；显示健康状态（最近成功时间/连续失败数）；**渠道类型选择器带图标和说明**
- **新增「转发规则」页** — 与采集规则页同构，但渠道是**多选**
- **扩展短信详情** — 显示「已转发到：运维群 ✓ / 我的微信 ✗（重试中）」
- **投递记录页** — 失败筛选 + 重投按钮
- **导航菜单**（`Layout.vue` 的 `menuItems`）新增两项

### 10.3 对现有 API 的影响

**无破坏性变更。** v1 的 `/api/sms/list`、`/api/sms/wait`、设备接口全部不变。转发是纯增量能力。

---

## 11. 配置

```yaml
app:
  notify:
    enabled: true
    # 渠道凭据加密密钥（AES-GCM）。必须通过环境变量注入，不要写默认值。
    encrypt-key: ${NOTIFY_ENCRYPT_KEY}
    dispatcher:
      poll-interval-ms: 1000      # 轮询间隔
      batch-size: 100             # 单轮最大取数
      max-concurrent-channels: 8  # 并发渠道数
    http:
      connect-timeout-ms: 3000
      read-timeout-ms: 10000
      max-connections: 50
```

**必须显式配置线程池**（修正 §3 的问题 2）：

```java
@Bean("notifyExecutor")
public ThreadPoolTaskExecutor notifyExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("notify-");
    // 队列满时由调用线程执行，形成背压，而不是丢弃任务
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return executor;
}
```

---

## 12. 实施计划

建议分期，每期独立可交付。

### 第一期：骨架 + 两个渠道（预计主要工作量）

- schema v3 建表 + 实体 + Repository
- 提取 `RuleMatcher` 工具类（从 `CollectRuleEngine` 抽取，**保证现有测试仍通过**）
- `NotifyRouteEngine` 路由匹配
- Outbox 写入（接进 `SmsService.receiveSms()` 事务）
- `NotifyDispatcher` 调度器 + 令牌桶 + 退避重试 + 错误分类
- 渠道适配器：**企业微信群机器人** + **通用 Webhook**
- 管理端「转发渠道」页 + 测试发送
- **验收**：配一个企微群，收到短信能在群里看到；断网后恢复能补发

### 第二期：主流渠道补全

- 飞书 / 钉钉（**注意两者签名算法完全不同**，是本期最大风险点，需各自写单测）
- Telegram / WxPusher
- 转发规则页 + 投递记录页

### 第三期：个人推送 + 精细化

- 企业微信应用消息 + `TokenManager`
- 消息模板自定义 + 打码策略
- 渠道健康自愈 + 告警

### 第四期（可选）

- Server酱 / PushPlus / Slack
- 通用 Webhook 的 Standard Webhooks 签名

### 明确不做

- ❌ QQ 任何形式（官方能力已下线；社区方案违约且封号）
- ❌ 个人微信群（无官方 API）
- ❌ 公众号模板消息（与「无用户触发的主动下发」语义冲突，违规）
- ❌ 任何依赖非官方协议（iPad 协议、Wechaty 之类）的方案

---

## 13. 测试要点

**单元测试**
- `RuleMatcher` 的 LIKE→正则转换（`%` / `_` / 元字符转义）—— 现有 `CollectRuleEngineTest` 应保持不变
- **飞书签名**：用官方测试向量验证「stringToSign 当 key、签空数据」
- **钉钉签名**：验证毫秒时间戳 + Base64 + URL encode
- 打码逻辑：验证码打码后正文里的验证码也要打掉
- 截断：中文长短信按字节截断不出现半个汉字

**集成测试**
- 幂等：同一 `(sms_message_id, channel_id)` 不会重复投递
- 事务性：模拟投递前进程崩溃，重启后能补发（outbox 的核心保证）
- 重试：Mock 对端返回 500 → 验证退避序列；返回 401 → 验证不重试
- 限流：突发 100 条 → 验证按令牌桶节流且**不消耗重试次数**
- SSRF：验证 `169.254.169.254`、`127.0.0.1`、`10.x` 被拒

---

## 14. 待确认事项

以下几项需要你拍板或核实：

1. **首选渠道是哪个？** 建议先做**企业微信群机器人**——一个 URL、无 token 管理、20 条/分钟对短信量级完全够用，且合规稳定。如果你已经在用飞书/钉钉，告诉我，我调整第一期的顺序。

2. **转发到群时验证码是否打码？** 我的默认建议是**群渠道默认 `MASKED`，个人渠道才允许 `FULL`**。但如果你转发到群的用途就是「群里的人要用这个验证码」，那 `FULL` 是对的——**请明确告诉我群里有谁**，这决定了默认值。

3. **腾讯用户协议原文需人工核对。** 本文引用的「8.2.2 条禁止第三方工具接入」来自搜索结果——`rule.tencent.com` 有反爬，本次未能直连抓取。这只影响 §2.2 的论证强度，不影响「不做 QQ」这个结论（官方主动推送下线是有官方公告的，见 §2.2 引文）。

4. **QQ 主动推送的官方口径目前自相矛盾**（源码说已下线、渲染版又列频控表），处于灰度收敛中。我没有把任何设计建立在这上面——**如果你的团队已经在用 QQ 机器人且确实能收到主动消息，告诉我，我再评估**。

5. **`app.notify.encrypt-key` 的密钥管理方式。** 如果部署在 K8s，建议用 Secret 注入；如果是单机 docker-compose，需要你自己保管。**这个密钥丢了，所有渠道凭据都要重配。**
