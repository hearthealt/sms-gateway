import { del, get, post, put } from './http'
import type { Device, EnrollToken, RecoveryCode, Stats, PaginatedResponse } from '../types'

export function getDeviceList(params: {
  page?: number
  pageSize?: number
  deviceId?: string
  phone?: string
}): Promise<PaginatedResponse<Device>> {
  return get('/admin/device/list', params)
}

export function getDeviceDetail(deviceId: string): Promise<Device> {
  return get(`/admin/device/${encodeURIComponent(deviceId)}`)
}

export function getDeviceStats(): Promise<Stats> {
  return get('/admin/device/stats')
}

/** 启用/禁用设备。注意这与"在线/离线"是两个维度。 */
export function toggleDeviceStatus(deviceId: string, enabled: boolean): Promise<Device> {
  return put(`/admin/device/${encodeURIComponent(deviceId)}/enabled`, undefined, {
    params: { enabled },
  })
}

/**
 * 删除设备。**连同它的短信记录一起删**（理由见后端 AdminDeviceService.delete），
 * 返回被一并删掉的短信条数 —— 调用方据此在提示里说清「删掉了什么」。
 *
 * 不可撤销，调用方必须先二次确认。
 */
export function deleteDevice(deviceId: string): Promise<number> {
  return del(`/admin/device/${encodeURIComponent(deviceId)}`)
}

/**
 * 为设备签发一张恢复码。
 *
 * 两个场景会用到：设备重装后本地密钥随应用数据一起没了；以及本次变更之前注册的
 * 老设备，它们本来就没有密钥，不签一张就永远无法重新注册。
 */
export function issueRecoveryCode(deviceId: string): Promise<RecoveryCode> {
  return post(`/admin/device/${encodeURIComponent(deviceId)}/recovery-code`)
}

/**
 * 「快速连接」用的接入口令。
 *
 * 它是**服务器**级别的准入凭证，不是设备身份 —— 一台全新手机扫码接入时，
 * 服务端靠它区分「自己人」和「碰巧知道服务器地址的人」。
 */
export function getEnrollToken(): Promise<EnrollToken> {
  return get('/admin/enroll-token')
}

/**
 * 生成一张新口令并启用准入校验；已有口令时即轮换。
 *
 * 轮换会让现场那张旧二维码**立刻失效**，所以调用方必须先二次确认。
 */
export function rotateEnrollToken(): Promise<EnrollToken> {
  return post('/admin/enroll-token/rotate')
}

/**
 * 启用 / 停用准入校验。停用不删除口令，重新启用不必换一张。
 *
 * 停用后注册接口退回完全开放 —— 任何知道服务器地址的人都能注册设备进来。
 * 这是给「临时批量接入」留的口子，界面必须把这句后果说清楚。
 */
export function setEnrollTokenEnabled(enabled: boolean): Promise<EnrollToken> {
  return put('/admin/enroll-token/enabled', undefined, { params: { enabled } })
}

/**
 * 设备要访问的服务器地址 —— 会写进恢复码 / 快速连接的二维码里。
 *
 * 取**当前后台的 origin**：后端只监听回环、前端那层 nginx 是这套系统对外的唯一入口，
 * 所以「你此刻访问后台用的地址」就是设备要用的地址（设备的 /api/... 也由它转发给后端）。
 * 以后上域名也是自动的：用域名打开后台，二维码里就是域名。
 *
 * 开发环境除外 —— 那里 vite 跑在 localhost:5173，而 localhost 对手机来说指向它自己，
 * 所以**开发时**仍读 management/.env 里的 VITE_DEVICE_SERVER_URL（局域网地址）。
 *
 * ⚠️ 用 origin 的代价，现场排查时记得这一条：二维码里写的就是「你访问后台用的地址」，
 * 所以**用 SSH 隧道 / VPN 从 localhost 或内网 IP 打开后台时，生成的二维码是错的** ——
 * 手机连不上，而二维码看起来一切正常。生成二维码请用设备能访问到的那个地址打开后台。
 * 弹窗里会把地址显示出来，生成完扫之前看一眼。
 */
export function deviceServerUrl(): string | null {
  // 开发时用 .env 里配的局域网地址（镜像里没有这个值：management/.dockerignore 排掉了 .env）
  const configured = import.meta.env.VITE_DEVICE_SERVER_URL
  if (typeof configured === 'string' && configured.trim()) {
    return configured.trim().replace(/\/+$/, '')
  }

  // 开发环境没配就返回 null，让调用方明确报错 —— 这时候 origin 是 localhost:5173，
  // 写进二维码只会让现场懵（早先的版本就是这么坑的）。
  if (import.meta.env.DEV) {
    return null
  }

  // 正经部署：后台就是唯一入口，用它的 origin。
  return window.location.origin
}
