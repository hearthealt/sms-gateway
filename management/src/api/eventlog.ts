import { get } from './http'
import type { EventLogItem, EventTypeOption, PaginatedResponse } from '../types'

/**
 * 运行事件列表。
 *
 * 类型与级别用字符串传：后端解析不出来时会退化成「不过滤」而不是报 400 ——
 * 筛错了顶多多显示几行，不该让整页打不开。
 */
export function getEventLogList(params: {
  page?: number
  pageSize?: number
  type?: string
  level?: string
  deviceId?: string
  startDate?: string
  endDate?: string
}): Promise<PaginatedResponse<EventLogItem>> {
  return get('/admin/eventlog/list', params)
}

/** 事件类型选项。从后端取，前端不硬编码中文标签。 */
export function getEventLogTypes(): Promise<EventTypeOption[]> {
  return get('/admin/eventlog/types')
}
