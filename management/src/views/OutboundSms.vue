<template>
  <div class="outbound-sms">
    <el-card shadow="never">
      <div class="action-bar">
        <div class="action-left">
          <span class="action-title">发送短信</span>
          <el-tag type="info" effect="plain" size="small">{{ total }} 条记录</el-tag>
        </div>
        <el-button type="primary" @click="openDialog">
          <el-icon><Plus /></el-icon> 发送短信
        </el-button>
      </div>

      <div class="filter-bar">
        <el-select
          v-model="filterDeviceId"
          placeholder="全部设备"
          clearable
          style="width: 220px"
          @change="reload"
        >
          <el-option
            v-for="d in devices"
            :key="d.deviceId"
            :label="d.deviceName || d.deviceId"
            :value="d.deviceId"
          />
        </el-select>
        <el-select
          v-model="filterStatus"
          placeholder="全部状态"
          clearable
          style="width: 160px"
          @change="reload"
        >
          <el-option v-for="s in STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <el-table :data="rows" stripe style="width: 100%" v-loading="loading" empty-text="还没有发送过短信">
        <el-table-column label="设备" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-primary">{{ row.deviceName || row.deviceId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="收信方" width="140" />
        <el-table-column prop="content" label="正文" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="plain">
              {{ row.statusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <!--
          分段数 = 计费条数。它由**设备**回报（只有设备知道那张卡走的是 GSM-7 还是
          UCS-2），所以在回执之前是空的 —— 空就是空，不显示估算值：
          显示一个猜的数会让人拿去对账，而对不上时更麻烦。
        -->
        <el-table-column label="计费条数" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.segments">{{ row.segments }}</span>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <el-table-column label="发起" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.createdBy || '-' }}</span>
            <el-tag v-if="row.source === 'API'" size="small" effect="plain">API</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发出时间" width="160">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.sentAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorReason">{{ row.errorReason }}</span>
            <!-- 已下发但还没回执：说清「在等什么」，否则这一格看起来像卡住了 -->
            <span v-else-if="row.status === 'DISPATCHED'" class="time-text">
              已交给设备，等它下一次心跳回报
            </span>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <!-- 只有还没交给设备的能撤：交给设备之后它可能已经发出去了 -->
            <el-button
              v-if="row.status === 'PENDING'"
              link
              type="danger"
              size="small"
              @click="cancel(row)"
            >
              撤销
            </el-button>
            <!--
              失败的可以重发（设备明确说了没发出去，所以不会重复计费）。
              **「结果未知」的不给这个按钮** —— 那条可能已经发出去了，重发就是真的再发一条。
              服务端也会拒绝，这里不给按钮只是为了不让人点出一个报错。
            -->
            <el-button
              v-else-if="row.status === 'FAILED'"
              link
              type="primary"
              size="small"
              @click="retry(row)"
            >
              重发
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          :page-size="PAGE_SIZE"
          :total="total"
          layout="total, prev, pager, next"
          size="small"
          @current-change="load"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      title="发送短信"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form label-width="90px">
        <el-form-item label="用哪台发" required>
          <el-select v-model="form.deviceId" style="width: 100%" placeholder="选择设备">
            <el-option
              v-for="d in devices"
              :key="d.deviceId"
              :label="`${d.deviceName || d.deviceId}${d.enabled ? '' : '（已禁用）'}`"
              :value="d.deviceId"
              :disabled="!d.enabled"
            />
          </el-select>
          <span class="form-hint block">短信由这台手机发出，收信方看到的是它那张卡的号。</span>
        </el-form-item>

        <el-form-item label="收信号码" required>
          <el-input v-model="form.phone" placeholder="例如 13800138000" maxlength="32" />
        </el-form-item>

        <el-form-item label="正文" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="要发的内容"
          />
          <!--
            分段数是**估算**，所以文案里明说以设备回报为准：真实分段取决于那张卡走
            GSM-7 还是 UCS-2（中文 67 字/段、英文 153 字/段），只有设备知道。
            显示一个估算值而不说是估算，会让人拿它去对账单。
          -->
          <span class="form-hint block">
            约 {{ estimateSegments(form.content) }} 条短信（估算，以设备实际回报为准）。
          </span>
        </el-form-item>
      </el-form>

      <!-- 这是全站唯一会花钱的动作，把后果摆在按钮上方，不是摆在某个角落 -->
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="发出去就无法撤回，并按运营商计费"
        description="短信由选中的那台手机发出，收信方会真的收到。每台设备每天有发送上限，可在「系统设置 → 外发短信」里调整。"
      />

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="handleSend">确认发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useAdminEvents } from '../composables/useAdminEvents'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { cancelOutbound, getOutboundList, retryOutbound, sendSms } from '../api/outbound'
import { getDeviceList } from '../api/device'
import type { Device, SmsOutbound } from '../types'

const PAGE_SIZE = 20

const rows = ref<SmsOutbound[]>([])
const devices = ref<Device[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)

const filterDeviceId = ref<string | undefined>(undefined)
const filterStatus = ref<string | undefined>(undefined)

const dialogVisible = ref(false)
const sending = ref(false)
const form = reactive({ deviceId: '', phone: '', content: '' })

const STATUS_OPTIONS = [
  { value: 'PENDING', label: '待下发' },
  { value: 'DISPATCHED', label: '已下发' },
  { value: 'SENT', label: '已发出' },
  { value: 'FAILED', label: '发送失败' },
  { value: 'UNKNOWN', label: '结果未知' },
  { value: 'CANCELLED', label: '已撤销' },
]

function formatTime(t: string | null) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
}

/**
 * 状态 → 标签颜色。
 *
 * DISPATCHED 用 warning 而不是 success：它**不是**「已发出」，只说明我们把它塞进了
 * 心跳响应。合成一个颜色会让人以为那条短信已经出去了 —— 而它是计费的。
 * UNKNOWN 用 danger：那是「不知道发没发」，需要人去对账。
 */
function statusTagType(status: string) {
  switch (status) {
    case 'SENT':
      return 'success'
    case 'DISPATCHED':
      return 'warning'
    case 'FAILED':
    case 'UNKNOWN':
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 分段数的**估算**。
 *
 * 含非 ASCII 字符（中文、emoji）时走 UCS-2：拼接后每段 67 字；纯 ASCII 走 GSM-7：每段 153 字。
 * 真实值以设备回报为准（见界面上那句话），这里只是让人在发送**之前**对条数有概念。
 */
function estimateSegments(text: string): number {
  if (!text) return 0
  const gsm7 = /^[\x20-\x7E\r\n]*$/.test(text)
  // eslint-disable-next-line no-control-regex
  const perSegment = gsm7 ? 153 : 67
  return Math.ceil(text.length / perSegment)
}

async function load() {
  loading.value = true
  try {
    const res = await getOutboundList({
      deviceId: filterDeviceId.value,
      status: filterStatus.value,
      page: page.value,
      pageSize: PAGE_SIZE,
    })
    rows.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

async function reload() {
  page.value = 1
  await load()
}

async function loadDevices() {
  // 外发要挑设备，所以这里把设备列表也拉一次（与设备管理页同源）
  const res = await getDeviceList({ page: 1, pageSize: 100 })
  devices.value = res.records
}

onMounted(async () => {
  await Promise.all([load(), loadDevices()])
})

/*
 * 这一页**有服务端自变源**：状态由设备的心跳回执推进，那不是任何人在界面上的操作。
 * 事件名照旧不窄分支（useAdminEvents 的契约）—— 本页关心的东西只有一个列表。
 * 弹窗开着时跳过：正在填的表单不能被静默覆盖。
 */
useAdminEvents(() => {
  if (dialogVisible.value) return
  load()
})

function openDialog() {
  if (!devices.value.length) {
    ElMessage.warning('还没有设备，先去「设备管理」接入一台')
    return
  }
  dialogVisible.value = true
}

function resetForm() {
  form.deviceId = ''
  form.phone = ''
  form.content = ''
}

async function handleSend() {
  if (!form.deviceId) {
    ElMessage.warning('请选择用哪台设备发送')
    return
  }
  if (!form.phone.trim()) {
    ElMessage.warning('请填写收信号码')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('请填写正文')
    return
  }

  const device = devices.value.find((d) => d.deviceId === form.deviceId)
  const name = device ? device.deviceName || device.deviceId : form.deviceId

  // 二次确认。与服务端无关 —— 它只管住每日上限，拦不住「点错了」
  try {
    await ElMessageBox.confirm(
      `将由「${name}」发出，收信方 ${form.phone.trim()}，约 ${estimateSegments(form.content)} 条。\n\n` +
        '发出去无法撤回，并按运营商计费。',
      '确认发送',
      {
        confirmButtonText: '发送',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    return
  }

  sending.value = true
  try {
    await sendSms({
      deviceId: form.deviceId,
      phone: form.phone.trim(),
      content: form.content.trim(),
    })
    ElMessage.success('已入队，设备下次心跳时发出（约 30 秒）')
    dialogVisible.value = false
    await reload()
  } catch {
    // 超上限、设备被禁用等由拦截器提示
  } finally {
    sending.value = false
  }
}

/**
 * 重发一条失败的。
 *
 * 确认框里说清「为什么它这次可能就成了」—— 上一次的失败原因就写在旁边的「说明」列里，
 * 而用户刚修好那个原因（最常见的是去手机上授权）时，最需要的就是这一句。
 */
async function retry(row: SmsOutbound) {
  try {
    await ElMessageBox.confirm(
      `重新发给 ${row.phone}。\n\n` +
        `上一次失败的原因：${row.errorReason || '未记录'}。如果那个原因已经解决，这次就能发出去。` +
        '（会按运营商计费一次）',
      '重发',
      { confirmButtonText: '重发', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await retryOutbound(row.id)
    ElMessage.success('已重新入队，设备下次心跳时发出')
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function cancel(row: SmsOutbound) {
  try {
    await ElMessageBox.confirm(
      '撤销后这条不会再交给设备。已经交给设备的撤不了。',
      '撤销',
      { confirmButtonText: '撤销', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await cancelOutbound(row.id)
    ElMessage.success('已撤销')
    await load()
  } catch {
    // 400（已下发/已结束）由拦截器提示
  }
}
</script>

<style scoped>
/*
 * 标题行与表格之间用一条细分隔线分区 —— 与其余页面（采集规则 / 投递记录 /
 * 设备列表 / 短信记录 / API 密钥 / 运行日志 / 接口文档 / 系统设置）完全一致。
 */
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 20px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--color-border-light);
}
.action-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.action-title {
  font-weight: 600;
  font-size: 16px;
  color: var(--color-text-primary);
}

.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.cell-primary {
  font-weight: 500;
  color: var(--color-text-primary);
}
.time-text { font-size: 13px; color: var(--color-text-secondary); }
.no-code { color: var(--color-text-placeholder); }
.pagination-wrap {
  margin-top: var(--section-gap);
  display: flex;
  justify-content: flex-end;
}
.form-hint {
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.6;
}
.form-hint.block {
  display: block;
  margin-top: 4px;
}
</style>
