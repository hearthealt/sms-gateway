import { get, post, put, del } from './http'
import type { ApiKey } from '../types'

/** 签发入参。密钥值由服务端生成，客户端只能给备注名和可选的过期时间。 */
export interface ApiKeyPayload {
  name: string
  /** ISO 格式，null 表示不过期。 */
  expiresAt: string | null
}

export function getApiKeyList(): Promise<ApiKey[]> {
  return get('/admin/apikey/list')
}

export function createApiKey(payload: ApiKeyPayload): Promise<ApiKey> {
  return post('/admin/apikey', payload)
}

export function deleteApiKey(id: number): Promise<void> {
  return del(`/admin/apikey/${id}`)
}

export function toggleApiKey(id: number, enabled: boolean): Promise<ApiKey> {
  return put(`/admin/apikey/${id}/enabled`, undefined, { params: { enabled } })
}
