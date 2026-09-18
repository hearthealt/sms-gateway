<template>
  <div class="device-detail" v-loading="loading">
    <template v-if="device">
      <!-- Breadcrumb -->
      <div class="breadcrumb">
        <el-link type="primary" :underline="false" @click="$router.push('/devices')">设备管理</el-link>
        <el-icon><ArrowRight /></el-icon>
        <span class="current-page">{{ device.deviceId }}</span>
      </div>

      <!-- Device Info Card -->
      <el-card shadow="never" class="detail-card">
        <template #header>
          <div class="card-header">
            <div class="header-left">
              <status-badge :status="device.status" />
              <span class="device-title">{{ device.deviceName || device.deviceId }}</span>
              <el-tag :type="device.enabled ? 'success' : 'danger'" size="small" effect="dark">
                {{ device.enabled ? '已启用' : '已禁用' }}
              </el-tag>
            </div>
            <div class="header-actions">
              <el-button size="default" plain :loading="issuingRecovery" @click="handleIssueRecoveryCode">
                生成恢复码
              </el-button>
              <el-button
                :type="device.enabled ? 'warning' : 'success'"
                size="default"
                :loading="toggling"
                plain
                @click="handleToggle"
              >
                {{ device.enabled ? '禁用设备' : '启用设备' }}
              </el-button>
            </div>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="设备ID" min-width="140">{{ device.deviceId }}</el-descriptions-item>
          <el-descriptions-item label="设备名称">{{ device.deviceName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ device.phone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="系统平台">{{ device.platform || '-' }}</el-descriptions-item>
          <el-descriptions-item label="应用版本">{{ device.appVersion || '-' }}</el-descriptions-item>
          <el-descriptions-item label="电量">
            <template v-if="device.battery !== null">
              <span :class="batteryClass(device.battery)">{{ device.battery }}%</span>
              <el-icon v-if="device.charging" class="charging-icon"><Lightning /></el-icon>
            </template>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="网络类型">
            <el-tag v-if="device.network" size="small" effect="plain">{{ device.network }}</el-tag>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="最后心跳">{{ formatTime(device.lastHeartbeat) }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ formatTime(device.createTime) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- Recent SMS -->
      <el-card shadow="never" class="detail-card">
        <template #header>
          <div class="card-header">
            <span class="card-section-title">最近短信</span>
            <!-- deviceId 进查询串前必须编码：UUID 虽然安全，但别再依赖「它恰好没有特殊字符」 -->
            <el-button text type="primary" @click="$router.push(`/sms?deviceId=${encodeURIComponent(device.deviceId)}`)">
              查看全部 <el-icon><ArrowRight /></el-icon>
            </el-button>
          </div>
        </template>
        <el-table :data="smsRecords" stripe style="width: 100%" v-loading="smsLoading" empty-text="暂无短信记录">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="sender" label="发送号码" width="150" />
          <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
          <el-table-column label="验证码" width="110">
            <template #default="{ row }">
              <!-- 与短信记录页一致：点一下即复制。本表没有整行点击行为，所以不需要 .stop -->
              <el-tag
                v-if="row.code"
                type="warning"
                effect="dark"
                size="small"
                style="cursor: pointer"
                @click="copyCode(row.code)"
              >{{ row.code }}</el-tag>
              <span v-else class="no-code">-</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">
              <span class="time-text">{{ formatTime(row.receiveTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="已读" width="70" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isRead ? 'success' : 'info'" size="small" effect="plain">
                {{ row.isRead ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="smsPage"
            :page-size="smsPageSize"
            :total="totalSms"
            small
            layout="total, prev, pager, next"
            @current-change="loadSms"
          />
        </div>
      </el-card>
    </template>

    <!-- 失败态：不能再用「device 为空」当加载中，否则请求失败时全屏 loading 永远停不下来 -->
    <el-result
      v-else-if="loadError"
      icon="error"
      title="设备信息加载失败"
      sub-title="设备可能已被删除，或网络异常"
    >
      <template #extra>
        <el-button type="primary" @click="loadDevice">重试</el-button>
        <el-button @click="$router.push('/devices')">返回设备列表</el-button>
      </template>
    </el-result>

    <!--
      恢复码。两个场景会用到：设备重装后本地密钥随应用数据一起没了；
      以及本次变更之前注册的老设备（它们本来就没有密钥，不签一张就永远无法重新注册）。

      二维码里带的是**设备身份**，所以这里要显眼地把设备 ID 摆出来让人核对，
      并明确说清「采用之后这台设备会以该身份上报」。
    -->
    <el-dialog
      v-model="recoveryVisible"
      title="设备恢复码"
      width="460px"
      :close-on-click-modal="false"
      @closed="clearRecovery"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="明文密钥只显示这一次"
        description="关闭后就取不回来了（服务端只保存它的哈希）。没记下就重新生成一张，旧密钥随之作废。"
      />
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
        <el-button type="primary" @click="recoveryVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import QRCode from 'qrcode'
import StatusBadge from '../components/StatusBadge.vue'
import { deviceServerUrl, getDeviceDetail, issueRecoveryCode, toggleDeviceStatus } from '../api/device'
import { getDeviceSms } from '../api/sms'
import { copyText } from '../utils/clipboard'
import type { Device, RecoveryCode, SmsRecord } from '../types'

const route = useRoute()
const device = ref<Device | null>(null)
// 加载态与错误态各自独立：原先以「device 为空」兼任加载态，请求失败时 device 永远是 null，
// 全屏 loading 就一直转，页面上又什么都没有
const loading = ref(false)
const loadError = ref(false)
const toggling = ref(false)
const smsRecords = ref<SmsRecord[]>([])
const smsLoading = ref(false)
const smsPage = ref(1)
const smsPageSize = ref(10)
const totalSms = ref(0)

function formatTime(t: string | null) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
}

function batteryClass(b: number): string {
  if (b > 50) return 'battery-high'
  if (b > 20) return 'battery-mid'
  return 'battery-low'
}

async function copyCode(code: string) {
  if (await copyText(code)) {
    ElMessage.success('验证码已复制')
  } else {
    ElMessage.error('复制失败')
  }
}

// ---------- 恢复码 ----------

const recoveryVisible = ref(false)
const recovery = ref<RecoveryCode | null>(null)
const recoveryQr = ref('')
const issuingRecovery = ref(false)

async function handleIssueRecoveryCode() {
  // 先要地址再签发。顺序很重要：不能先把服务端的密钥轮换了、再发现二维码没地址可写 ——
  // 那样这台设备的旧密钥已经被作废，而现场什么都没拿到。
  const serverUrl = deviceServerUrl()
  if (!serverUrl) {
    ElMessage.error(
      '未配置 VITE_DEVICE_SERVER_URL，无法生成恢复码：二维码里要写设备能访问的服务器地址，' +
        '这个值没配时猜不出来（开发时控制台的 origin 是 localhost，对手机没有意义）。'
    )
    return
  }

  issuingRecovery.value = true
  try {
    const code = await issueRecoveryCode(route.params.deviceId as string)
    recovery.value = code

    // 二维码字段名必须与 Android 端 QrConfig 一致（url / deviceId / enrollSecret / deviceName）
    recoveryQr.value = await QRCode.toDataURL(
      JSON.stringify({
        url: serverUrl,
        deviceId: code.deviceId,
        enrollSecret: code.enrollSecret,
        deviceName: device.value?.deviceName ?? undefined,
      }),
      { width: 320, margin: 1, errorCorrectionLevel: 'M' }
    )
    recoveryVisible.value = true
  } catch {
    // 拦截器已经统一弹过错误提示，这里不再重复
  } finally {
    issuingRecovery.value = false
  }
}

/** 关闭时清掉明文：它只该存在于这一次弹窗的生命周期里。 */
function clearRecovery() {
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

async function loadDevice() {
  loading.value = true
  loadError.value = false
  try {
    device.value = await getDeviceDetail(route.params.deviceId as string)
    // 设备拿到了再拉短信：设备不存在时这条请求注定也失败，只会多弹一条拦截器的错误提示
    loadSms()
  } catch {
    // 具体原因（404 / 网络）拦截器已经弹过，这里只切到可重试的错误态，不再重复弹窗
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadSms() {
  smsLoading.value = true
  try {
    const res = await getDeviceSms(route.params.deviceId as string, {
      page: smsPage.value,
      pageSize: smsPageSize.value,
    })
    smsRecords.value = res.records
    totalSms.value = res.total
  } catch (e) {
    console.error('Failed to load device SMS', e)
  } finally {
    smsLoading.value = false
  }
}

async function handleToggle() {
  if (!device.value) return
  const willDisable = device.value.enabled

  try {
    await ElMessageBox.confirm(
      `确定要${willDisable ? '禁用' : '启用'}设备 ${device.value.deviceId} 吗？`,
      '操作确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    toggling.value = true
    // 后端返回更新后的设备，直接替换，避免本地状态与后端不一致
    device.value = await toggleDeviceStatus(device.value.deviceId, !willDisable)
    ElMessage.success(`设备已${willDisable ? '禁用' : '启用'}`)
  } catch {
    // 用户取消，或请求失败（失败提示由 http 拦截器统一处理）
  } finally {
    toggling.value = false
  }
}

onMounted(loadDevice)
</script>

<style scoped>
.device-detail {
  width: 100%;
  /* 首屏内容还没渲染时容器高度为 0，会把 loading 遮罩压成一条细线 */
  min-height: 200px;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 14px;
  color: var(--color-text-secondary);
}
.current-page {
  color: var(--color-text-primary);
  font-weight: 500;
}

.detail-card {
  margin-bottom: 16px;
  border-radius: var(--border-radius-base);
  border: 1px solid var(--color-border-light);
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.device-title {
  font-weight: 600;
  font-size: 16px;
  color: var(--color-text-primary);
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-section-title {
  font-weight: 600;
  font-size: 16px;
}

:deep(.el-descriptions__title) {
  font-weight: 600;
}
:deep(.el-descriptions__label) {
  font-weight: 500;
  color: var(--color-text-secondary);
}

.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.time-text { font-size: 13px; color: var(--color-text-secondary); }
.no-code { color: var(--color-text-placeholder); }
.charging-icon { margin-left: 4px; color: var(--color-warning); }
.battery-high { color: var(--color-success); font-weight: 500; }
.battery-mid { color: var(--color-warning); font-weight: 500; }
.battery-low { color: var(--color-danger); font-weight: 500; }

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
