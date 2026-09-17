import { get, post, put, del } from './http'
import type { CollectRule } from '../types'

export type RulePayload = Omit<CollectRule, 'id' | 'createdAt' | 'updatedAt'>

export function getRuleList(): Promise<CollectRule[]> {
  return get('/admin/rule/list')
}

export function createRule(rule: RulePayload): Promise<CollectRule> {
  return post('/admin/rule', rule)
}

export function updateRule(id: number, rule: RulePayload): Promise<CollectRule> {
  return put(`/admin/rule/${id}`, rule)
}

export function deleteRule(id: number): Promise<void> {
  return del(`/admin/rule/${id}`)
}

export function toggleRule(id: number, enabled: boolean): Promise<CollectRule> {
  return put(`/admin/rule/${id}/enabled`, undefined, { params: { enabled } })
}
