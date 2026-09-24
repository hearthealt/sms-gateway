import { get, post } from './http'
import type { PaginatedResponse, SmsOutbound } from '../types'

/** 外发记录列表，条件均可选。最近的在前。 */
export function getOutboundList(params: {
  deviceId?: string
  status?: string
  page?: number
  pageSize?: number
}): Promise<PaginatedResponse<SmsOutbound>> {
  return get('/admin/outbound/list', params)
}

/**
 * 用某台设备发一条短信。
 *
 * **这是全项目唯一一个会产生费用的写接口。** 界面必须二次确认，并把
 * 「按运营商计费、发出去无法撤回」写清楚 —— 服务端只管住每日上限。
 */
export function sendSms(payload: {
  deviceId: string
  phone: string
  content: string
}): Promise<SmsOutbound> {
  return post('/admin/outbound', payload)
}

/**
 * 撤销一条**还没交给设备**的。
 *
 * 已经下发的撤不了（服务端会拒绝）：那时它可能已经在对方手机上发出去了，
 * 而「撤销」在界面上会被读成「那条没发出去」。
 */
export function cancelOutbound(id: number): Promise<SmsOutbound> {
  return post(`/admin/outbound/${id}/cancel`)
}

/**
 * 重发一条**失败的**。
 *
 * 「结果未知」的会被服务端拒绝并说明原因 —— 那条可能已经发出去了，
 * 重发就是真的再发一条、再计费一次。界面上只在「发送失败」的行上给这个按钮。
 */
export function retryOutbound(id: number): Promise<SmsOutbound> {
  return post(`/admin/outbound/${id}/retry`)
}
