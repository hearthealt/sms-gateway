import { get } from './http'
import type { SmsRecord, DailyStat, PaginatedResponse } from '../types'

export function getSmsList(params: {
  page?: number
  pageSize?: number
  phone?: string
  code?: string
  startDate?: string
  endDate?: string
  deviceId?: string
  /** 默认 false：后端会过滤掉命中 ignore 规则的短信。 */
  includeIgnored?: boolean
}): Promise<PaginatedResponse<SmsRecord>> {
  return get('/admin/sms/list', params)
}

export function getDeviceSms(
  deviceId: string,
  params: { page?: number; pageSize?: number } = {},
): Promise<PaginatedResponse<SmsRecord>> {
  return get(`/admin/sms/device/${encodeURIComponent(deviceId)}`, params)
}

/** 仪表盘近 N 天短信量趋势。 */
export function getDailyStats(days = 7): Promise<DailyStat[]> {
  return get('/admin/sms/stats/daily', { days })
}
