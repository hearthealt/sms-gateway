export interface Device {
  id: number
  deviceId: string
  deviceName: string | null
  phone: string | null
  platform: string | null
  appVersion: string | null
  /**
   * 展示用状态：online / offline 表示可达性，DISABLED 表示已被管理员禁用。
   * 与 enabled 是两个维度。
   */
  status: 'online' | 'offline' | 'DISABLED' | 'ACTIVE' | 'INACTIVE'
  enabled: boolean
  lastHeartbeat: string | null
  battery: number | null
  network: string | null
  charging: boolean | null
  pendingCount: number | null
  createTime: string | null
}

export interface SmsRecord {
  id: number
  /** 业务设备标识（Android 端为 UUID），不是数据库主键。 */
  deviceId: string | null
  deviceName: string | null
  phone: string
  sender: string
  content: string
  code: string | null
  /**
   * 采集状态。**实际只有两种**：RECEIVED、IGNORED（命中 ignore 规则）。
   *
   * DUPLICATE 只是上报给设备端的响应码（去重是撞 uk_device_source_hash 之后更新原行的
   * duplicateCount，不新插行），PROCESSED 在后端只有枚举声明、没有任何一处写它 ——
   * 两个都落不到这一列上。别照这个字段判「是不是重复」，那看 duplicateCount。
   */
  status: string | null
  receiveTime: string
  /** 这段内容后来又收到过几次。0 = 只收到过一次。 */
  duplicateCount: number
  /** 最后一次收到这条内容的时刻（重复到达会把它顶上去）。 */
  updatedAt: string
}

export interface CollectRule {
  id: number
  ruleName: string
  senderPattern: string
  keywordPattern: string
  matchType: string
  action: 'collect' | 'ignore'
  priority: number
  enabled: boolean
  description: string | null
  createdAt: string
  updatedAt: string
}

/** 外部调用方密钥。由管理后台「API 密钥」页签发，用于 /api/sms/wait 的鉴权。 */
export interface ApiKey {
  id: number
  /** 用途备注，便于识别是哪个调用方。 */
  name: string
  /**
   * 完整密钥明文。后端存的就是明文，打码只做在展示层 ——
   * 列表默认显示掩码，点「显示」才展开，点「复制」始终取完整值。
   */
  apiKey: string
  enabled: boolean
  /** 过期时间，null 表示不过期。 */
  expiresAt: string | null
  /** 最后一次通过鉴权的时间，从未使用过则为 null。 */
  lastUsedAt: string | null
  createdAt: string
}

export interface Stats {
  onlineDevices: number
  offlineDevices: number
  totalDevices: number
  todaySms: number
  todayCodes: number
}

/** 仪表盘趋势图的单日数据点。 */
export interface DailyStat {
  /** 日期，格式 yyyy-MM-dd。 */
  day: string
  /** 当日短信总数。 */
  value: number
  /** 当日识别出验证码的条数。 */
  codes: number
}

export interface LoginResult {
  token: string
  username: string
  displayName: string | null
  /** 令牌有效期（秒），服务端下发。前端据此自己判断过期，不必等一次失败请求。 */
  expiresIn: number
}

/**
 * 一条运行事件（`GET /api/admin/eventlog/list`）。
 *
 * **这里没有 content，也没有 code** —— 不是漏了，是后端那张表本来就不存正文与验证码
 * （它保留 7 天，比短信本身活得久）。要看内容按 smsMessageId 去「短信记录」页查。
 */
export interface EventLogItem {
  id: number
  /** 枚举名，如 SMS_STORED。筛选与逻辑判断用它。 */
  eventType: string
  /** 中文标签，直接渲染。来自后端枚举，前端不硬编码。 */
  typeLabel: string
  /** INFO / WARN / ERROR。 */
  level: string
  /** 业务设备标识；设备已删时为 null，此时看 deviceCode 兜底的值也不会有。 */
  deviceId: string | null
  deviceName: string | null
  sender: string | null
  phone: string | null
  /** 判定结果 / 原因码 / HTTP 状态。 */
  reason: string | null
  /**
   * 关联的短信主键。**可能已被保留策略清掉**，只能当作「可跳转的线索」，
   * 不能假定一定查得到。
   */
  smsMessageId: number | null
  createdAt: string
}

/** 事件类型的下拉选项，来自后端枚举（`GET /api/admin/eventlog/types`）。 */
export interface EventTypeOption {
  value: string
  label: string
  /** 该类型默认的级别，用于给选项上色。 */
  level: string
}

export interface PaginatedResponse<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
}

export interface PageParam {
  page?: number
  pageSize?: number
}

/**
 * 设备恢复码。管理员为某台设备签发，设备扫码后取回身份。
 *
 * `enrollSecret` 是**明文**，只在签发那一次返回 —— 服务端只留 SHA-256，
 * 之后再也要不回来。没记下就重新签一张，旧的自然作废。
 */
export interface RecoveryCode {
  deviceId: string
  enrollSecret: string
}

/**
 * 设备接入口令 —— 「快速连接」页里那个二维码携带的准入凭证。
 *
 * 与 `RecoveryCode` 是**两回事**，别混：那个认的是「我是哪台设备」（针对已存在的设备），
 * 这个认的是「我被允许接入本服务器」（只在设备首次注册时校验）。
 *
 * `token` 是**明文**（与 API 密钥同理：要显示进二维码，哈希回读不出来），
 * 界面上默认打码，点「显示」才展开。`token` 为 null 表示从未生成过，
 * 此时准入校验未启用、注册接口是开放的。
 *
 * ⚠️ 判断「有没有生成过」**只能看 `token` 字段**：未生成时后端返回的仍然是这个对象
 * （三个字段分别是 null / false / null），不是 null。拿对象本身当判据会把「没生成过」
 * 当成「已停用」—— 界面上于是给出一个「启用」按钮，点下去后端只能回 400
 * 「尚未生成接入口令，无法启用或停用」。
 */
export interface EnrollToken {
  token: string | null
  /**
   * 与 `token` 是两个维度：停用时 token 仍然留着，重新启用不必换一张。
   * 前端据此区分「没生成过」和「生成过但关掉了」。
   */
  enabled: boolean
  updatedAt: string | null
}

/**
 * 转发渠道。
 *
 * `config` 是**打码过**的回显值（webhook 地址在库里是密文），
 * 提交时约定「留空 = 不修改」—— 见 NotifyChannelPayload。
 */
export interface NotifyChannel {
  id: number
  name: string
  type: NotifyChannelType
  config: Record<string, unknown>
  rateLimitPerMin: number
  maxRetries: number
  enabled: boolean
  // 健康状态
  lastSuccessAt: string | null
  lastErrorAt: string | null
  lastError: string | null
  consecutiveFailures: number
  /** 还没发出去的条数。比连续失败次数更早暴露「这个渠道卡住了」。 */
  backlog: number
  createdAt: string
  updatedAt: string
}

/** 与后端 NotifyChannelType 一一对应（下拉选项本身是从 /channel/types 取的）。 */
export type NotifyChannelType =
  | 'WECOM_BOT'
  | 'FEISHU_BOT'
  | 'DINGTALK_BOT'
  | 'TELEGRAM_BOT'
  | 'SLACK_WEBHOOK'
  | 'WXPUSHER'
  | 'SERVERCHAN'
  | 'PUSHPLUS'
  | 'GENERIC_WEBHOOK'
  | 'WECOM_APP'

export interface NotifyChannelPayload {
  name?: string
  type?: NotifyChannelType
  /** 明文配置。**留空或不传 = 保持原值不变**，不是清空。 */
  config?: Record<string, unknown>
  rateLimitPerMin?: number
  maxRetries?: number
  enabled?: boolean
}

export interface NotifyChannelTestResult {
  success: boolean
  statusCode: number | null
  detail: string
}

/** 转发规则。匹配条件语义与采集规则一致：空 = 不限制这一项。 */
export interface NotifyRoute {
  id: number
  routeName: string
  senderPattern: string | null
  keywordPattern: string | null
  matchType: string
  deviceId: string | null
  phonePattern: string | null
  enabled: boolean
  channelIds: number[]
  /** 与 channelIds 一一对应，便于列表直接展示。 */
  targetChannelNames: string[]
  createdAt: string
  updatedAt: string
}

export interface NotifyRoutePayload {
  routeName?: string
  senderPattern?: string | null
  keywordPattern?: string | null
  matchType?: string
  deviceId?: string | null
  phonePattern?: string | null
  channelIds?: number[]
  enabled?: boolean
}

export type NotifyDeliveryStatus =
  | 'PENDING'
  | 'SENDING'
  | 'SUCCESS'
  | 'FAILED'
  | 'DEAD'
  /** 渠道被停用，这条不再投递。与 DEAD 的区别：这个是预期内的，不需要人处理。 */
  | 'CANCELLED'

export interface NotifyDelivery {
  id: number
  smsMessageId: number
  channelId: number
  channelName: string
  /**
   * 所属渠道是否启用。
   *
   * **它决定这条记录还会不会发出去**：渠道被停用后记录会一直停在待投递、
   * `nextRetryAt` 也停在过去 —— 只看状态会读成「马上要重试了」，实际不会再发。
   */
  channelEnabled: boolean
  status: NotifyDeliveryStatus
  attempts: number
  nextRetryAt: string
  responseCode: number | null
  lastError: string | null
  sentAt: string | null
  createdAt: string
  sender: string | null
  phone: string | null
  /** 短信原文预览 —— 投递记录刻意不存渲染后的正文（存了等于把验证码写两遍且第二遍没有 TTL）。 */
  contentPreview: string | null
}

export interface NotifyChannelTypeOption {
  value: NotifyChannelType
  label: string
}

/**
 * 一项运行期配置。改完立即生效、不用重启后端。
 *
 * `label` / `description` / `type` 都由后端从 `SysConfigKey` 带出来 ——
 * 前端不硬编码一份，否则加一项配置要改两处，漏改的那次表现为「设置页少了一项」。
 */
export interface SysConfigItem {
  key: string
  value: string
  /** 代码里的默认值。「已修改」标签与「恢复默认」都用它。 */
  defaultValue: string
  /**
   * 决定用什么控件。
   * `TIME` 是 `HH:mm`，用时间选择器而不是让人手填 cron。
   */
  type: 'BOOLEAN' | 'INT' | 'STRING' | 'TIME'
  label: string
  /** 第一行是简述，其余是详细说明（按 `\n` 分段渲染）。 */
  description: string
  /** 分组标签，页面按它把配置分成几张卡片。 */
  group: string
}
