<template>
  <!--
    恢复码。两个场景会用到：设备重装后本地密钥随应用数据一起没了；
    以及本次变更之前注册的老设备（它们本来就没有密钥，不签一张就永远无法重新注册）。

    二维码里带的是**设备身份**，所以这里要显眼地把设备 ID 摆出来让人核对，
    并明确说清「采用之后这台设备会以该身份上报」。

    抽成组件是因为设备详情页与设备列表页都要用它：这是一条安全敏感的流程
    （明文密钥只出现这一次），两份实现迟早会分叉 —— 分叉的那一半多半是
    「某一边忘了清掉明文」。
  -->
  <el-dialog
    v-model="visible"
    title="设备恢复码"
    width="460px"
    :close-on-click-modal="false"
    @closed="clear"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="明文密钥只显示这一次"
      description="关闭后取不回来（服务端只存哈希）。没记下就重新生成，旧密钥随之作废。"
    />
    <!--
      与「快速连接」同一条：地址只有本机能访问时（本地调试会看到）照旧能用，
      但要说清二维码里写的就是这个地址 —— 那台设备扫了会连到它自己。
      写成一小行旁注而不是 el-alert：它是一条提醒，不是一块要处置的区域，
      上面那块「明文密钥只显示这一次」才是这块弹窗真正要人看的。
    -->
    <div v-if="localOnlyAddress" class="recovery-addr-warn">
      <el-icon><WarningFilled /></el-icon>
      <span>地址是本机地址（localhost），手机扫了连的是它自己</span>
    </div>
    <div v-if="recoveryQr" class="recovery-qr">
      <img :src="recoveryQr" alt="设备恢复码" />
    </div>
    <div v-else class="recovery-qr recovery-qr--loading">
      <el-icon class="is-loading"><Loading /></el-icon>
    </div>

    <el-descriptions :column="1" border size="small" class="recovery-detail">
      <el-descriptions-item label="设备 ID">
        <span class="mono">{{ recovery?.deviceId }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="重注册密钥">
        <span class="mono">{{ recovery?.enrollSecret }}</span>
      </el-descriptions-item>
    </el-descriptions>

    <template #footer>
      <el-button @click="handleCopySecret">复制密钥</el-button>
      <el-button type="primary" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import { deviceServerUrl, isLocalOnlyAddress, issueRecoveryCode } from '../api/device'
import { copyText } from '../utils/clipboard'
import type { RecoveryCode } from '../types'

const visible = ref(false)
const recovery = ref<RecoveryCode | null>(null)
const recoveryQr = ref('')
const serverUrl = ref('')

/** 与「快速连接」同一条：只有本机能访问的地址会被写进二维码，必须显式警告一下。 */
const localOnlyAddress = computed(() => isLocalOnlyAddress(serverUrl.value))

/**
 * 为某台设备签发一张恢复码并弹窗。由父组件通过 ref 调用。
 *
 * 失败（没配服务器地址、接口报错）时什么都不做 —— 错误提示已经由这里或 http 拦截器给出，
 * 父组件不必再处理返回值。
 */
async function open(deviceId: string) {
  // 地址就是当前 origin（后端只监听回环，前端是唯一入口）。它若是 localhost 这种
  // 手机访问不到的地址，弹窗里会有警告条 —— 但不拦着不放，本地调试要看这个界面。
  serverUrl.value = deviceServerUrl()

  // 先弹窗再签发：签发是一次网络往返，期间弹窗里显示加载态，点下去立刻有反馈。
  recovery.value = null
  recoveryQr.value = ''
  visible.value = true

  try {
    const code = await issueRecoveryCode(deviceId)
    recovery.value = code

    // 二维码里只有服务器地址与设备身份。**不带设备名** ——
    // 名字现在跟随手机本身，扫码端不再认这个字段（见 Android 端 QrConfig），
    // 带着它只会让人以为扫码能把名字带过去。
    recoveryQr.value = await QRCode.toDataURL(
      JSON.stringify({
        url: serverUrl.value,
        deviceId: code.deviceId,
        enrollSecret: code.enrollSecret,
      }),
      { width: 320, margin: 1, errorCorrectionLevel: 'M' }
    )
  } catch {
    // 拦截器已经统一弹过错误提示，这里不再重复。签发失败时把空弹窗关掉，
    // 免得留下一个永远转圈、又没有任何说明的框。
    visible.value = false
  }
}

/** 关闭时清掉明文：它只该存在于这一次弹窗的生命周期里。 */
function clear() {
  recovery.value = null
  recoveryQr.value = ''
}

async function handleCopySecret() {
  const secret = recovery.value?.enrollSecret
  if (!secret) return
  if (await copyText(secret)) {
    ElMessage.success('密钥已复制')
  } else {
    ElMessage.error('复制失败')
  }
}

defineExpose({ open })
</script>

<style scoped>
/* 地址只有本机能访问时的一行旁注（见模板里的说明）：小字 + 警示色，不做成大色块 */
.recovery-addr-warn {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-warning);
}
.recovery-qr {
  display: flex;
  justify-content: center;
  padding: 12px 0;
}
.recovery-qr img {
  width: 260px;
  height: 260px;
}
.recovery-qr--loading {
  height: 260px;
  align-items: center;
  font-size: 24px;
  color: var(--el-text-color-placeholder);
}
.recovery-detail {
  margin-top: 8px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
