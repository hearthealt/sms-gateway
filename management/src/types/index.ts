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
  /** 采集状态：RECEIVED / DUPLICATE / PROCESSED / IGNORED（命中 ignore 规则）。 */
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
