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
    width="min(640px, 94vw)"
    :close-on-click-modal="false"
    @closed="clear"
  >
    <!--
      安全状态放最上面，一行说清。**只有出问题时才升级成整块 el-alert** ——
      一切正常时也用 alert 报「已启用」，等于拿一个占三行的框去说一件不需要
      处置的事，真正需要看见的那两种（没口令 / 已停用）反而被稀释。
    -->
    <div v-if="token?.enabled" class="qc-status">
      <el-icon><CircleCheck /></el-icon>
      <span>接入口令已启用 —— 新设备必须扫下面这张码才能接入。</span>
    </div>
    <!--
      未生成：注册接口是**开放**的。这不是一个可以轻描淡写带过的状态 ——
      任何知道下面这个地址的人都能注册一台设备进来，所以这里显式警告，
      并且把口令的生成做成一个明确的按钮，不自动生成：静默改变安全边界
      比多点一次更糟。
    -->
    <el-alert
      v-else-if="!token"
      type="warning"
      :closable="false"
      show-icon
      title="尚未启用接入口令"
      description="此刻任何知道下面这个地址的人都能注册一台设备进来。生成一张口令后，新设备必须扫这张码才能接入。"
    />
    <el-alert
      v-else
      type="warning"
      :closable="false"
      show-icon
      title="接入口令已停用"
      description="注册接口现在是开放的，任何知道该地址的人都能注册设备。口令本身还留着，重新启用即可，不必换一张。"
    />

    <!--
      左右两栏：二维码在左、要读要复制的信息在右。

      原先是一列向下排（地址、二维码、口令三段各占一行），弹窗总高约 660px ——
      而这张弹窗真正要传达的只有一件事：扫这张码。二维码竖着占掉 260px 之后，
      剩下的信息被推到折叠线以下，弹窗在笔电屏幕上顶到天花板。
      分栏之后二维码旁边那块空白正好用来放地址与口令，高度掉到 300px 出头。
    -->
    <div class="qc-body">
      <div class="qc-qr-col">
        <div v-if="qr" class="qc-qr">
          <img :src="qr" alt="快速连接二维码" />
        </div>
        <div v-else class="qc-qr qc-qr--loading">
          <el-icon class="is-loading"><Loading /></el-icon>
        </div>
      </div>

      <div class="qc-info-col">
        <!--
          服务器地址是整个功能的**前提** —— 码里要写设备能访问的地址，而控制台
          自己只知道「从哪个 origin 取数据」，不知道设备该连哪台机器（开发时 origin
          是 localhost，对手机毫无意义）。没配就直接不给开这个弹窗，见 open()。
        -->
        <div class="qc-label">服务器地址</div>
        <div class="mono qc-url-text">{{ serverUrl }}</div>
        <div class="qc-actions">
          <el-button size="small" @click="handleCopyUrl">复制地址</el-button>
          <!--
            启用了口令之后，「复制地址」单独一段是**不完整**的：手机粘过去只拿到地址，
            注册会被服务端以「没带接入口令」拒掉，而现场看不出是少了什么。
            没有口令时不显示它 —— 那时两个按钮复制的内容完全一样，多一个只是噪音。
          -->
          <el-button v-if="token?.token" size="small" @click="handleCopyConnectInfo">
            复制连接信息
          </el-button>
        </div>

        <template v-if="token">
          <div class="qc-label qc-label--spaced">接入口令</div>
          <div class="qc-token">
            <span class="mono qc-token-text">
              {{ revealed ? token.token : maskToken(token.token) }}
            </span>
            <el-button link type="primary" size="small" @click="revealed = !revealed">
              {{ revealed ? '隐藏' : '显示' }}
            </el-button>
            <el-button link type="primary" size="small" @click="handleCopyToken">复制</el-button>
          </div>
          <!--
            口令自己的操作留在这里，不放到弹窗页脚：页脚是全弹窗的动作区，
            摆在那里会让「停用」看起来像在关掉这个弹窗。
          -->
          <div class="qc-actions">
            <el-button size="small" :loading="busy" @click="handleToggleEnabled">
              {{ token.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="primary" :loading="busy" @click="handleRotate">
              重新生成
            </el-button>
          </div>
        </template>
      </div>
    </div>

    <!--
      这段说明放在两栏**下方通栏**，而不是塞进左栏：塞在二维码底下时左栏比右栏高出一截，
      弹窗底部是参差的，而它本来是两个栏目共用的旁注。
    -->
    <p class="qc-hint qc-hint--foot">
      手机 App 主页点「扫码连接服务器」对准二维码；已配置过的手机走
      「设置 → 配置二维码 → 扫码导入」。
    </p>

    <template #footer>
      <!-- 还没有口令时，「生成」是这个弹窗当前唯一该做的事，所以留在页脚 -->
      <el-button v-if="!token" type="primary" :loading="busy" @click="handleRotate">
        生成接入口令
      </el-button>
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
/* 顶部那行安全状态。用成功色的浅底而不是 el-alert：一行能说清的事不该占三行 */
.qc-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  margin-bottom: 12px;
  border-radius: 6px;
  font-size: 13px;
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

/*
 * 左右两栏。窄到放不下时（flex-wrap）自动上下堆叠，而不是把二维码压扁 ——
 * 压扁的二维码扫不出来，而堆叠只是让弹窗长一点。
 */
.qc-body {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: flex-start;
}

.qc-qr-col {
  flex: 0 0 auto;
  width: 200px;
}
.qc-qr {
  display: flex;
  justify-content: center;
}
.qc-qr img {
  width: 200px;
  height: 200px;
}
.qc-qr--loading {
  height: 200px;
  align-items: center;
  font-size: 24px;
  color: var(--el-text-color-placeholder);
}

.qc-info-col {
  flex: 1 1 260px;
  /* 不加这句，里面那个不换行的长地址会把这一栏撑破、把二维码挤出弹窗 */
  min-width: 0;
}

.qc-label {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.qc-label--spaced {
  margin-top: 16px;
}
.qc-url-text {
  /* 地址是这一页最需要被核对的东西，不能截断（要能看清每一个字符） */
  font-size: 13px;
  line-height: 1.5;
  word-break: break-all;
}
.qc-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
/* 与弹窗页脚同一个理由：这几颗按钮带 :loading，钉住宽度就不会随转圈抖动 */
.qc-actions .el-button {
  min-width: 88px;
}

.qc-token {
  display: flex;
  align-items: center;
  gap: 8px;
}
.qc-token-text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  word-break: break-all;
}

.qc-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.qc-hint--foot {
  margin-top: 14px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
