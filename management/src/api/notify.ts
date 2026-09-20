import { get, post, put, del } from './http'
import type {
  NotifyChannel,
  NotifyChannelPayload,
  NotifyChannelTestResult,
  NotifyChannelTypeOption,
  NotifyDelivery,
  NotifyRoute,
  NotifyRoutePayload,
  PaginatedResponse,
} from '../types'

// ---------------------------------------------------------------- 状态

/**
 * 后端的**渠道加密密钥**是否已配置好。
 *
 * 进页面时先问一次：没配好就直接显示配置指引，而不是让人填完整个渠道表单、
 * 点保存，才发现存不进去（那一刻填的东西全白填了）。
 *
 * 注意它问的不是「转发总开关开没开」—— 那项在「系统设置」页，随时可改、
 * 改完立即生效，不必拦着人配渠道。
 */
export function getNotifyStatus(): Promise<{ ready: boolean }> {
  return get('/admin/notify/status')
}

// ---------------------------------------------------------------- 渠道

/**
 * 支持的渠道类型。**从后端取而不是前端硬编码** —— 硬编码一份的话，
 * 后端加了新渠道前端不显示（白加了），后端删了前端还在显示（选了发不出去）。
 */
export function getChannelTypes(): Promise<NotifyChannelTypeOption[]> {
  return get('/admin/notify/channel/types')
}

export function getChannelList(): Promise<NotifyChannel[]> {
  return get('/admin/notify/channel/list')
}

export function createChannel(payload: NotifyChannelPayload): Promise<NotifyChannel> {
  return post('/admin/notify/channel', payload)
}

/**
 * 修改渠道。
 *
 * **`config` 留空表示不修改**，不是清空 —— 回显的是打码值，
 * 若把它原样提交回来会被当成新地址存进去，渠道从此静默失效。
 * 后端用 NotifyConfigMasker 认这种情况，但前端也该在没改动时不发这个字段。
 */
export function updateChannel(id: number, payload: NotifyChannelPayload): Promise<NotifyChannel> {
  return put(`/admin/notify/channel/${id}`, payload)
}

export function deleteChannel(id: number): Promise<void> {
  return del(`/admin/notify/channel/${id}`)
}

export function toggleChannel(id: number, enabled: boolean): Promise<NotifyChannel> {
  return put(`/admin/notify/channel/${id}/enabled`, undefined, { params: { enabled } })
}

/**
 * 发一条测试消息。
 *
 * 配置期的必需能力：配好之后必须能立刻验证，否则要等到真有短信才知道配没配对，
 * 而那时用户正等着验证码。测试内容不含真实验证码。
 */
export function testChannel(id: number): Promise<NotifyChannelTestResult> {
  return post(`/admin/notify/channel/${id}/test`)
}

// ---------------------------------------------------------------- 规则

export function getRouteList(): Promise<NotifyRoute[]> {
  return get('/admin/notify/route/list')
}

export function createRoute(payload: NotifyRoutePayload): Promise<NotifyRoute> {
  return post('/admin/notify/route', payload)
}

export function updateRoute(id: number, payload: NotifyRoutePayload): Promise<NotifyRoute> {
  return put(`/admin/notify/route/${id}`, payload)
}

export function deleteRoute(id: number): Promise<void> {
  return del(`/admin/notify/route/${id}`)
}

export function toggleRoute(id: number, enabled: boolean): Promise<NotifyRoute> {
  return put(`/admin/notify/route/${id}/enabled`, undefined, { params: { enabled } })
}

// ---------------------------------------------------------------- 投递记录

export function getDeliveryList(params: {
  page?: number
  pageSize?: number
  channelId?: number
  status?: string
}): Promise<PaginatedResponse<NotifyDelivery>> {
  return get('/admin/notify/delivery/list', params)
}

/** 手动重投。退回待投递状态，由调度器发出 —— 不绕过限流与同渠道串行。 */
export function retryDelivery(id: number): Promise<NotifyDelivery> {
  return post(`/admin/notify/delivery/${id}/retry`)
}
