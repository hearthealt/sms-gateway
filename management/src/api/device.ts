import { del, get, post, put } from './http'
import type { Device, RecoveryCode, Stats, PaginatedResponse } from '../types'

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
 * 设备要访问的服务器地址 —— 会写进恢复码二维码里。
 *
 * 控制台自己的 axios 走相对路径 `/api`，它只知道「从哪个 origin 取」，不知道
 * 设备该连哪台机器。开发时尤其明显：vite 把 `/api` 代理到 localhost:8080，
 * 而 localhost 对手机毫无意义。
 *
 * 所以优先读 VITE_DEVICE_SERVER_URL，没配才退回当前 origin。
 */
export function deviceServerUrl(): string | null {
  const configured = import.meta.env.VITE_DEVICE_SERVER_URL
  if (typeof configured === 'string' && configured.trim()) {
    return configured.trim().replace(/\/+$/, '')
  }

  // 没配就返回 null，**不要退回 window.location.origin**。
  //
  // 早先的版本就是退回了它，结果开发时控制台跑在 localhost:5173，扫出来的二维码
  // 把设备的服务器地址改成了 http://localhost:5173 —— 对手机来说那是指向它自己，
  // 网关从此再也连不上后端，而且现场看不出是二维码干的。
  //
  // 与其塞一个几乎必然是错的地址，不如让调用方明确报错、逼管理员去配。
  // 配置方式见 management/.env.example。
  return null
}
