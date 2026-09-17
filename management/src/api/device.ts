import { get, put } from './http'
import type { Device, Stats, PaginatedResponse } from '../types'

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
