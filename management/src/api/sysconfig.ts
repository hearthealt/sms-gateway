import { get, put } from './http'
import type { SysConfigItem } from '../types'

/**
 * 运行期配置，改完**立即生效、不用重启后端**。
 *
 * 能配哪些项、每项是什么意思，全由后端决定（`SysConfigKey` 上带着 label 与说明）——
 * 前端不硬编码一份，否则加一项配置要改两个仓库，而漏改的那次表现为「设置页少了
 * 一项」这种只有人去看才会发现的漏。
 */
export function getSysConfigList(): Promise<SysConfigItem[]> {
  return get('/admin/sysconfig/list')
}

/**
 * 改一项。值一律传字符串，类型由后端按该键的定义校验。
 *
 * 值不合法时后端回 400 并带具体原因（如「短信保留天数：不能是负数」），
 * 由拦截器统一提示。
 */
export function updateSysConfig(key: string, value: string): Promise<void> {
  return put('/admin/sysconfig', { key, value })
}
