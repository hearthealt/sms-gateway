/**
 * 设备 ID 的展示用短形式。
 *
 * 必须先剥掉 `android-` 前缀再截断：新式设备 ID 是 `android-` + SSAID
 * （见 Android 端 DevicePrefs.newDeviceId），这段前缀对**所有设备都一样** ——
 * 直接截前 8 位的话，整列会齐刷刷显示成 "android-"，一台也认不出来。
 * 老式 ID 是裸 UUID，没有前缀，照常截。
 *
 * 放在这里而不是各页面各写一份：这是条容易被忘掉的领域规则，
 * 两处实现迟早有一处会忘（曾经就是）。
 *
 * @param max 最多显示多少个字符（不含省略号）。列的宽度决定它。
 */
export function shortDeviceId(id: string, max = 8): string {
  const body = id.startsWith('android-') ? id.slice('android-'.length) : id
  return body.length > max ? `${body.slice(0, max)}…` : body
}
