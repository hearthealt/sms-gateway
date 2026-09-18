<template>
  <div class="device-detail" v-loading="loading">
    <template v-if="device">
      <!-- Breadcrumb -->
      <div class="breadcrumb">
        <!-- underline 用字符串取值：布尔形式在 Element Plus 3.0 已弃用，控制台会刷警告 -->
        <el-link type="primary" underline="never" @click="$router.push('/devices')">设备管理</el-link>
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
              <!-- 签发期间的等待由弹窗自己显示（先弹窗再签发），按钮不再需要 loading -->
              <el-button size="default" plain @click="handleIssueRecoveryCode">
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
        <!-- el-descriptions 是按列数渲染表格的，CSS 改不动，只能把列数算出来 -->
        <el-descriptions :column="isMobile ? 1 : 2" border>
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
          <el-table-column prop="sender" label="发送号码" width="160" />
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
          <!-- 重复到达只累加在同一行上，不单开一行；计数单独一列，
               与短信记录页的口径一致（那边在「采集」列） -->
          <el-table-column label="重复" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.duplicateCount > 0" type="warning" size="small" effect="plain">
                {{ row.duplicateCount }} 次
              </el-tag>
              <span v-else class="no-code">-</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="160">
            <template #default="{ row }">
              <span class="time-text">{{ formatTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="smsPage"
            :page-size="smsPageSize"
            :total="totalSms"
            size="small"
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

    <!-- 恢复码弹窗与设备列表页共用一份实现（含「明文只显示这一次」的处理） -->
    <RecoveryCodeDialog ref="recoveryDialog" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import StatusBadge from '../components/StatusBadge.vue'
import RecoveryCodeDialog from '../components/RecoveryCodeDialog.vue'
import { getDeviceDetail, toggleDeviceStatus } from '../api/device'
import { getDeviceSms } from '../api/sms'
import { copyText } from '../utils/clipboard'
import { useIsMobile } from '../composables/useMediaQuery'
import type { Device, SmsRecord } from '../types'

const route = useRoute()
const isMobile = useIsMobile()
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

// 恢复码的签发与明文处理都在 RecoveryCodeDialog 里，这里只负责打开它 ——
// 那套逻辑（先要地址再签发、关闭即清明文、只显示一次）在设备列表页也要用，
// 抄一份迟早会分叉。
const recoveryDialog = ref<InstanceType<typeof RecoveryCodeDialog>>()

function handleIssueRecoveryCode() {
  const id = device.value?.deviceId
  if (!id) return

  // 同设备列表页：不静默吞掉 ref 未挂载的情况（表现是「点了没反应」）
  if (!recoveryDialog.value) {
    ElMessage.error('恢复码弹窗未挂载（模板里缺少 <RecoveryCodeDialog ref="recoveryDialog" />）')
    return
  }
  recoveryDialog.value.open(id)
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

/* 圆角与边框由 App.vue 的 .el-card 全局规则给，这里只留页面级间距 */
.detail-card {
  margin-bottom: var(--section-gap);
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
  margin-top: var(--section-gap);
  display: flex;
  justify-content: flex-end;
}

.time-text { font-size: 13px; color: var(--color-text-secondary); }
.no-code { color: var(--color-text-placeholder); }
.charging-icon { margin-left: 4px; color: var(--color-warning); }
.battery-high { color: var(--color-success); font-weight: 500; }
.battery-mid { color: var(--color-warning); font-weight: 500; }
.battery-low { color: var(--color-danger); font-weight: 500; }

</style>
