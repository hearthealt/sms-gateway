<template>
  <!--
    快速连接：让一台**全新**手机接进来。

    与 RecoveryCodeDialog 的分工要说清楚，两者都会出二维码但完全不同：
      · 恢复码  —— 认的是「我是哪台设备」，必须先有设备记录，给重装/换机的**老**设备用。
      · 快速连接 —— 认的是「我被允许接入本服务器」，给还没在库里出现过的**新**设备用，
                    扫完由手机自行注册成一台新设备。

    二维码里**不含 deviceId / enrollSecret**：那两样一旦能由一张二维码写给任意手机，
    就等于「给你看一张码，你的手机从此变成我的设备」。接入口令是服务器准入凭证，
    与设备身份是两回事，字段也分开。
  -->
  <el-dialog
    v-model="visible"
    title="快速连接"
    width="520px"
    :close-on-click-modal="false"
    @closed="clear"
  >
    <!--
      服务器地址。这是整个功能的**前提** —— 码里要写设备能访问的地址，而控制台自己
      只知道「从哪个 origin 取数据」，不知道设备该连哪台机器（开发时 origin 是
      localhost，对手机毫无意义）。没配就直接不给开这个弹窗，见 open()。
    -->
    <div class="section-label">服务器地址</div>
    <div class="url-row">
      <span class="mono url-text">{{ serverUrl }}</span>
      <el-button size="small" @click="handleCopyUrl">复制地址</el-button>
    </div>
    <!--
      启用了口令之后，「复制地址」单独一段是**不完整**的：手机粘过去只拿到地址，
      注册会被服务端以「没带接入口令」拒掉，而现场看不出是少了什么。
      所以再给一个把地址与口令一起带走的按钮。

      没有口令时不显示它 —— 那时候两个按钮复制的内容完全一样，多一个只是噪音。
    -->
    <div v-if="token?.token" class="connect-info-row">
      <el-button size="small" @click="handleCopyConnectInfo">复制连接信息（含口令）</el-button>
      <span class="hint hint--inline">没相机扫码时，把这个整段贴给手机即可</span>
    </div>

    <div v-if="qr" class="qr-box">
      <img :src="qr" alt="快速连接二维码" />
    </div>
    <div v-else class="qr-box qr-box--loading">
      <el-icon class="is-loading"><Loading /></el-icon>
    </div>

    <p class="hint">
      在手机 App 主页点「扫码连接服务器」对准这里。已配置过的手机可走
      「设置 → 配置二维码 → 扫码导入」。
    </p>

    <el-divider />

    <div class="section-label">接入口令</div>

    <!--
      未生成：注册接口是**开放**的。这不是一个可以轻描淡写带过的状态 ——
      任何知道上面这个地址的人都能注册一台设备进来，所以这里显式警告，
      并且把口令的生成做成一个明确的按钮，不自动生成：静默改变安全边界
      比多点一次更糟。
    -->
    <el-alert
      v-if="!token"
      type="warning"
      :closable="false"
      show-icon
      title="尚未启用接入口令"
      description="此刻任何知道上面这个地址的人都能注册一台设备进来。生成一张口令后，新设备必须扫这张码才能接入。"
    />
    <template v-else>
      <el-alert
        v-if="!token.enabled"
        type="warning"
        :closable="false"
        show-icon
        title="接入口令已停用"
        description="注册接口现在是开放的，任何知道该地址的人都能注册设备。口令本身还留着，重新启用即可，不必换一张。"
      />
      <el-alert
        v-else
        type="success"
        :closable="false"
        show-icon
        title="接入口令已启用"
        description="新设备必须携带这张二维码里的口令才能注册。"
      />

      <div class="token-row">
        <span class="mono token-text">{{ revealed ? token.token : maskToken(token.token) }}</span>
        <el-button link type="primary" size="small" @click="revealed = !revealed">
          {{ revealed ? '隐藏' : '显示' }}
        </el-button>
        <el-button link type="primary" size="small" @click="handleCopyToken">复制</el-button>
      </div>
    </template>

    <template #footer>
      <el-button v-if="!token" type="primary" :loading="busy" @click="handleRotate">
        生成接入口令
      </el-button>
      <template v-else>
        <el-button :loading="busy" @click="handleToggleEnabled">
          {{ token.enabled ? '停用' : '启用' }}
        </el-button>
        <el-button type="primary" :loading="busy" @click="handleRotate">重新生成</el-button>
      </template>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import {
  deviceServerUrl,
  getEnrollToken,
  rotateEnrollToken,
  setEnrollTokenEnabled,
} from '../api/device'
import { copyText } from '../utils/clipboard'
import type { EnrollToken } from '../types'

const visible = ref(false)
const serverUrl = ref('')
const token = ref<EnrollToken | null>(null)
const qr = ref('')
/** 口令默认打码，与 API 密钥列表一致：截图和投屏时不会顺手把口令带出去。 */
const revealed = ref(false)
const busy = ref(false)

/**
 * 由父组件通过 ref 调用。
 *
 * 失败（没配服务器地址、接口报错）时什么都不做 —— 错误提示已经由这里或 http
 * 拦截器给出，父组件不必再处理返回值。
 */
async function open() {
  // 先要地址再拉口令。顺序与 RecoveryCodeDialog 一致：地址拿不到就没有继续的意义，
  // 猜一个写进二维码只会让设备连到错的地方。
  const url = deviceServerUrl()
  if (!url) {
    ElMessage.error(
      '未配置 VITE_DEVICE_SERVER_URL，无法生成快速连接二维码：二维码里要写设备能访问的' +
        '服务器地址，这个值没配时猜不出来（开发时控制台的 origin 是 localhost，对手机没有意义）。'
    )
    return
  }

  serverUrl.value = url
  token.value = null
  qr.value = ''
  revealed.value = false
  visible.value = true

  try {
    applyState(await getEnrollToken())
  } catch {
    // 拦截器已经统一弹过错误提示，这里不再重复。拿不到口令状态就渲染不出正确的
    // 二维码（不知道要不要带口令），所以直接关掉，免得留下一个永远转圈的框。
    visible.value = false
  }
}

/** 记下新状态并重画二维码。三个写接口的返回值都是完整状态，统一走这里。 */
async function applyState(next: EnrollToken) {
  token.value = next
  await refreshQr()
}

/**
 * 连接信息：二维码里装的那一段，也是「复制连接信息」复制的那一段。
 *
 * 口令存在就带上，**不管它是启用还是停用**：停用期间扫这张码照样能注册
 * （校验会放行），带上它省得管理员为了「重新启用」还得再换一张码。
 *
 * 抽成一个函数是为了让二维码与复制按钮**永远同源** —— 两处各拼一遍的话，
 * 迟早有一处忘了带口令，而那种不一致只会在现场扫了才发现。
 */
function connectPayload(): string {
  const payload = token.value?.token
    ? { url: serverUrl.value, enrollToken: token.value.token }
    : { url: serverUrl.value }
  return JSON.stringify(payload)
}

async function refreshQr() {
  qr.value = await QRCode.toDataURL(connectPayload(), {
    width: 320,
    margin: 1,
    errorCorrectionLevel: 'M',
  })
}

async function handleCopyConnectInfo() {
  if (await copyText(connectPayload())) {
    ElMessage.success('连接信息已复制（含服务器地址与接入口令）')
  } else {
    ElMessage.error('复制失败')
  }
}

/** 打码而不是直接不显示：管理员要能一眼看出「有没有口令」，只是不该默认看到内容。 */
function maskToken(value: string | null): string {
  if (!value) return ''
  return '•'.repeat(Math.min(value.length, 24))
}

async function handleCopyUrl() {
  if (await copyText(serverUrl.value)) {
    ElMessage.success('服务器地址已复制')
  } else {
    ElMessage.error('复制失败')
  }
}

async function handleCopyToken() {
  const value = token.value?.token
  if (!value) return
  if (await copyText(value)) {
    ElMessage.success('接入口令已复制')
  } else {
    ElMessage.error('复制失败')
  }
}

async function handleRotate() {
  // 轮换会让现场那张旧二维码立刻失效，所以只在「已经有口令」时才确认 ——
  // 首次生成没有可作废的东西，多一次确认只是多一次点击。
  if (token.value) {
    try {
      await ElMessageBox.confirm(
        '重新生成会让现有二维码立刻失效：还没扫过的设备将无法再接入，需要拿新二维码重扫。' +
          '已注册的设备不受影响（它们认的是自己的设备密钥，不看这张口令）。',
        '确认重新生成接入口令？',
        { type: 'warning', confirmButtonText: '重新生成', cancelButtonText: '取消' }
      )
    } catch {
      return // 用户取消
    }
  }

  busy.value = true
  try {
    applyState(await rotateEnrollToken())
    ElMessage.success('接入口令已生成，请用新二维码接入设备')
  } catch {
    // 拦截器已提示
  } finally {
    busy.value = false
  }
}

async function handleToggleEnabled() {
  const current = token.value
  if (!current) return

  const next = !current.enabled
  if (!next) {
    try {
      await ElMessageBox.confirm(
        '停用后注册接口会退回开放状态：任何知道服务器地址的人都能注册设备进来。' +
          '口令本身会留着，随时可以重新启用，不必换一张。',
        '确认停用接入口令？',
        { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }

  busy.value = true
  try {
    applyState(await setEnrollTokenEnabled(next))
    ElMessage.success(next ? '接入口令已启用' : '接入口令已停用')
  } catch {
    // 拦截器已提示
  } finally {
    busy.value = false
  }
}

/** 关闭时清掉口令明文：它不该在内存里留过这一次弹窗的生命周期。 */
function clear() {
  token.value = null
  qr.value = ''
  revealed.value = false
  busy.value = false
}

defineExpose({ open })
</script>

<style scoped>
.section-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin-bottom: 8px;
}
.url-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.connect-info-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.hint--inline {
  text-align: left;
}
.url-text {
  flex: 1;
  /* 地址是这一页最需要被核对的东西，不能截断（要能看清每一个字符） */
  word-break: break-all;
  font-size: 13px;
}
.qr-box {
  display: flex;
  justify-content: center;
  padding: 12px 0;
}
.qr-box img {
  width: 260px;
  height: 260px;
}
.qr-box--loading {
  height: 260px;
  align-items: center;
  font-size: 24px;
  color: var(--el-text-color-placeholder);
}
.hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.token-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.token-text {
  flex: 1;
  font-size: 12px;
  word-break: break-all;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
