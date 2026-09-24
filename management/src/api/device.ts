import { del, get, post, put } from './http'
import type {
  Device,
  DeviceCommand,
  DeviceCommandType,
  EnrollToken,
  RecoveryCode,
  Stats,
  PaginatedResponse,
} from '../types'

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

/**
 * 删除设备。**连同它的短信记录一起删**（理由见后端 AdminDeviceService.delete），
 * 返回被一并删掉的短信条数 —— 调用方据此在提示里说清「删掉了什么」。
 *
 * 不可撤销，调用方必须先二次确认。
 */
export function deleteDevice(deviceId: string): Promise<number> {
  return del(`/admin/device/${encodeURIComponent(deviceId)}`)
}

/**
 * 为设备签发一张恢复码。
 *
 * 两个场景会用到：设备重装后本地密钥随应用数据一起没了；以及本次变更之前注册的
 * 老设备，它们本来就没有密钥，不签一张就永远无法重新注册。
 */
export function issueRecoveryCode(deviceId: string): Promise<RecoveryCode> {
  return post(`/admin/device/${encodeURIComponent(deviceId)}/recovery-code`)
}

/**
 * 「快速连接」用的接入口令。
 *
 * 它是**服务器**级别的准入凭证，不是设备身份 —— 一台全新手机扫码接入时，
 * 服务端靠它区分「自己人」和「碰巧知道服务器地址的人」。
 */
export function getEnrollToken(): Promise<EnrollToken> {
  return get('/admin/enroll-token')
}

/**
 * 生成一张新口令并启用准入校验；已有口令时即轮换。
 *
 * 轮换会让现场那张旧二维码**立刻失效**，所以调用方必须先二次确认。
 */
export function rotateEnrollToken(): Promise<EnrollToken> {
  return post('/admin/enroll-token/rotate')
}

/**
 * 启用 / 停用准入校验。停用不删除口令，重新启用不必换一张。
 *
 * 停用后注册接口退回完全开放 —— 任何知道服务器地址的人都能注册设备进来。
 * 这是给「临时批量接入」留的口子，界面必须把这句后果说清楚。
 */
export function setEnrollTokenEnabled(enabled: boolean): Promise<EnrollToken> {
  return put('/admin/enroll-token/enabled', undefined, { params: { enabled } })
}

/**
 * 签发一条远程指令。
 *
 * **这是会改变设备本机状态的写操作**，调用方必须先二次确认，并把后果写进确认框
 * （例如「停掉之后短信会留在手机上不再上传」）。服务端会拒掉同一设备的同类型
 * 重复指令（返回已有的那条），所以连点两下不会产生两条记录。
 *
 * @param argument 只有 SET_PHONE 需要（号码）。其余类型传了会被服务端拒绝 ——
 *   那是刻意的：静默丢掉参数会让调用方以为它生效了。
 */
export function issueDeviceCommand(
  deviceId: string,
  type: DeviceCommandType,
  argument?: string | null
): Promise<DeviceCommand> {
  return post(`/admin/device/${encodeURIComponent(deviceId)}/command`, {
    type,
    argument: argument ?? null,
  })
}

/** 某台设备的指令列表，最近的在前（后端按 id 倒序）。 */
export function getDeviceCommands(
  deviceId: string,
  params: { page?: number; pageSize?: number } = {}
): Promise<PaginatedResponse<DeviceCommand>> {
  return get(`/admin/device/${encodeURIComponent(deviceId)}/command/list`, params)
}

/**
 * 撤销一条指令。
 *
 * 这是**唯一**能在指令送达设备之前阻止它生效的手段 —— 「已下发但还没回执」的指令
 * 也能撤销（设备离线时点错了，就靠这个）。已经走到终态的指令服务端会拒绝，报 400。
 *
 * 路径里不带 deviceId：id 是全局唯一的，两个都能到达同一条指令的地址只会让人困惑。
 */
export function cancelDeviceCommand(id: number): Promise<DeviceCommand> {
  return post(`/admin/device/command/${id}/cancel`)
}

/**
 * 设备要访问的服务器地址 —— 会写进恢复码 / 快速连接的二维码里。
 *
 * 取**当前后台的 origin**：后端只监听回环、前端那层 nginx 是这套系统对外的唯一入口，
 * 所以「你此刻访问后台用的地址」就是设备要用的地址（设备的 /api/... 也由它转发给后端）。
 * 以后上域名也是自动的 —— 用域名打开后台，二维码里就是域名。**这里没有任何要配的东西。**
 *
 * 什么时候返回 null（调用方会明确报错）：当前地址是 localhost / 127.0.0.1。
 * 那种地址对手机来说指向它自己，写进二维码只会让现场懵 —— 早先的版本就是这么坑的
 * （开发时控制台跑在 localhost:5173，扫出来的二维码把设备指到了它自己）。
 * 开发时想看二维码，用 `npm run dev -- --host`，再从局域网 IP 访问即可。
 *
 * ⚠️ 有一种情况这里**认不出来**，现场排查时记得：用 SSH 隧道 / VPN 从别的内网地址打开后台，
 * origin 会是个手机访问不到的地址，而二维码照样生成得出来、看起来一切正常。
 * 生成前弹窗里会把地址显示出来，扫之前看一眼。
 */
export function deviceServerUrl(): string {
  return window.location.origin
}

/**
 * 这个地址是不是只有本机能访问（localhost / 127.0.0.1 / ::1）。
 *
 * 二维码里写这种地址，手机扫了会连到它自己 —— 所以两个二维码弹窗都会就此**显式警告**
 * （见 QuickConnectDialog / RecoveryCodeDialog 里的警告条）。
 *
 * 早先这里是直接拒绝生成，结果本地 `npm run dev` 调试时连弹窗都打不开，什么也测不了。
 * 改成「只警告、不拦」：风险照样摆在眼前，但不挡人做事。
 */
export function isLocalOnlyAddress(url: string): boolean {
  return /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])([:/]|$)/i.test(url)
}
