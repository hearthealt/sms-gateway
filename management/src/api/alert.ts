import { del, get, post, put } from './http'
import type { AlertRule, AlertTypeOption } from '../types'

/** 告警规则列表。规则数是个位数，不分页（与转发规则页一致）。 */
export function getAlertRuleList(): Promise<AlertRule[]> {
  return get('/admin/alert/rule/list')
}

/**
 * 可选的告警类型。
 *
 * 从后端取而不是前端硬编码（同 EventType 的做法）：加一种告警类型时只需要改后端枚举，
 * 前端自己维护一份映射的话，漏改的表现是下拉里少一项、或选中后显示成英文常量名。
 */
export function getAlertTypes(): Promise<AlertTypeOption[]> {
  return get('/admin/alert/rule/types')
}

export function createAlertRule(payload: {
  ruleName: string
  alertType: string | null
  deviceId: string
  enabled: boolean
  channelIds: number[]
}): Promise<AlertRule> {
  return post('/admin/alert/rule', payload)
}

export function updateAlertRule(
  id: number,
  payload: {
    ruleName: string
    alertType: string | null
    deviceId: string
    enabled: boolean
    channelIds: number[]
  }
): Promise<AlertRule> {
  return put(`/admin/alert/rule/${id}`, payload)
}

export function deleteAlertRule(id: number): Promise<void> {
  return del(`/admin/alert/rule/${id}`)
}

/**
 * 启用 / 停用。
 *
 * 一条没有任何渠道的规则**不允许启用**（后端会拒绝）—— 它开着却什么都不发，
 * 而列表上看起来和正常规则一模一样。
 */
export function toggleAlertRule(id: number, enabled: boolean): Promise<AlertRule> {
  return put(`/admin/alert/rule/${id}/enabled`, undefined, { params: { enabled } })
}
