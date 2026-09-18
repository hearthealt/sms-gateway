<template>
  <div class="device-list">
    <el-card shadow="never">
      <!--
        列宽合计 1038px（固定列 + 两列 min-width 的下限），1366 窗口的可用宽约 1042px
        （1366 − 220 侧边栏 − 56 主区留白 − 48 卡片留白），不会触发横向滚动。
        改前合计 1110px，在 1366 下会横向滚动 —— 而那是最常见的一档笔记本分辨率。

        富余宽度只给「设备」和「手机号」两列分摊（min-width），且整表封顶 1200px：
          · 全给一列的话，宽屏下那一列能空出七八百像素（现场看到的就是「设备列太宽」）
          · 不封顶的话，超宽屏上两列还是会各自长到很夸张
        最终 1366 下几乎看不出变化，1920 下每列也就多 80 来像素。
      -->
      <!-- Filter Bar -->
      <div class="filter-bar">
        <el-input
          v-model="searchForm.deviceId"
          placeholder="设备ID"
          clearable
          class="filter-item filter-device"
          @keyup.enter="handleSearch"
        />
        <el-input
          v-model="searchForm.phone"
          placeholder="手机号"
          clearable
          class="filter-item filter-phone"
          @keyup.enter="handleSearch"
        />
        <div class="filter-actions">
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon> 重置
          </el-button>
          <el-button @click="handleRefresh" :loading="loading">
            <el-icon><RefreshRight /></el-icon> 刷新
          </el-button>
        </div>
      </div>

      <!--
        列顺序按「人怎么找设备」排，不按字段的技术重要性：
        设备名称（可辨识）→ 手机号 → 状态 → 心跳 → 电量/网络/版本，
        36 字符的 UUID 挪到最末尾并降成等宽灰色小字。
        改前 UUID 占着第一列 260px —— 扫描列表时最没用的信息反而排在最前面。
      -->
      <el-table
        :data="devices"
        stripe
        style="width: 100%"
        v-loading="loading"
        @row-click="goToDetail"
        highlight-current-row
      >
        <el-table-column label="设备" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- deviceName 可能为空（设备没上报机型），用 ID 兜底，别留一个空白格 -->
            <span class="cell-primary">{{ row.deviceName || row.deviceId }}</span>
          </template>
        </el-table-column>
        <!-- 设备读不到本机号码时 phone 为空，用 - 兜底，与相邻列保持一致。
             宽度与短信记录页对齐（都是 150）：两页的同一列宽度不同，看着像没调过。
             用 min-width：宽屏下的富余宽度由「设备」和它**两列分摊**，
             单独一列吃下全部富余的话，那一列会空得离谱。 -->
        <el-table-column label="手机号" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <status-badge :status="row.status" />
          </template>
        </el-table-column>
        <!-- 只显示到分钟：列表里看的是「多久之前」，秒级信息在这个宽度里不值得占位，
             完整时刻放 tooltip 与设备详情页 -->
        <el-table-column label="最后心跳" width="140">
          <template #default="{ row }">
            <el-tooltip v-if="row.lastHeartbeat" :content="formatTime(row.lastHeartbeat)" placement="top">
              <span class="time-text">{{ formatShortTime(row.lastHeartbeat) }}</span>
            </el-tooltip>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <el-table-column label="电量" width="100">
          <template #default="{ row }">
            <span v-if="row.battery !== null" :class="batteryClass(row.battery)">{{ row.battery }}%</span>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <!-- 90 是给 "cellular"（这列最长的取值）留的，更长的由 tooltip 兜底 -->
        <el-table-column label="网络" width="100" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.network">{{ row.network }}</span>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <!-- 80 是下限，不能再窄：表头「应用版本」四个字加单元格内边距正好占满，
             砍到 72 会让表头折成两行 -->
        <el-table-column label="应用版本" width="100" prop="appVersion" show-overflow-tooltip />
        <!-- 用 shortDeviceId 而不是原样截断：ID 是 "android-" + SSAID，前缀对所有设备
             都一样，按宽度硬截只会得到一列 "android-a53…"，一台也认不出来。
             完整值放在显式 tooltip 上（show-overflow-tooltip 给的是单元格里的文本，
             那已经是短形式了）。 -->
        <!-- 定长列，用 width 不用 min-width：ID 的长度是固定的，多给宽度只会多出一片空白。
             让它 min-width 的话，宽屏下它会和「设备」列一起把富余宽度吃掉一半 ——
             现场看到的就是「设备ID 后面很宽」。富余宽度只留给「设备」列。 -->
        <el-table-column label="设备ID" width="240" prop="deviceId"/>
        <!--
          操作。三个动作按「用到频率 × 后果」排：恢复码（换机/重装时用，无破坏）→
          禁用/启用（可逆）→ 删除（不可逆，红色）。
          标签刻意短：「生成恢复码」写成「恢复码」，省下的宽度够放另外两个动作。

          **不要加 fixed="right"**：整表在 1366 下就放得下，不会横向滚动，固定列没有意义；
          而它一旦固定，前面的列就不再自动填满剩余宽度，宽屏下会在设备与操作之间空出一大块。

          .stop 不能省 —— 整行是可点击的（进设备详情），不拦会把点击同时送到行上。
        -->
        <el-table-column label="操作" width="150" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small"
              :disabled="!!actingId" @click.stop="handleRecovery(row)">恢复码</el-button>
            <el-button link :type="row.enabled ? 'warning' : 'success'" size="small"
              :disabled="!!actingId" @click.stop="handleToggleEnabled(row)">
              {{ row.enabled ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small"
              :disabled="!!actingId" @click.stop="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无设备数据" :image-size="80" />
        </template>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next, sizes"
          :page-sizes="[10, 20, 50]"
          @current-change="loadData"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 行内「恢复码」打开的弹窗。必须在模板里挂上并绑定 ref，
         否则 recoveryDialog.value 永远是 undefined，点按钮会静默无反应。 -->
    <RecoveryCodeDialog ref="recoveryDialog" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusBadge from '../components/StatusBadge.vue'
import RecoveryCodeDialog from '../components/RecoveryCodeDialog.vue'
import { deleteDevice, getDeviceList, toggleDeviceStatus } from '../api/device'
import { useAdminEvents } from '../composables/useAdminEvents'
import { shortDeviceId } from '../utils/device'
import type { Device } from '../types'

const router = useRouter()
const devices = ref<Device[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const searchForm = reactive({
  deviceId: '',
  phone: '',
})

function formatTime(t: string | null) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
}

/** 列表里只显示到分钟；完整时刻由 tooltip 与设备详情页给。 */
function formatShortTime(t: string) {
  return dayjs(t).format('MM-DD HH:mm')
}

function batteryClass(b: number): string {
  if (b > 50) return 'battery-high'
  if (b > 20) return 'battery-mid'
  return 'battery-low'
}

/*
 * 请求序号：连点页码 2、3 时，page=2 的响应可能后到并覆盖表格，
 * 而分页控件已经停在第 3 页 —— 表格和页码对不上。
 * 这里用序号比较丢弃过期响应，不用 AbortController：取消请求会走到
 * http 拦截器的错误分支，白弹一条「网络错误」。
 */
let loadSeq = 0

async function loadData() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = await getDeviceList({
      page: page.value,
      pageSize: pageSize.value,
      deviceId: searchForm.deviceId || undefined,
      phone: searchForm.phone || undefined,
    })
    if (seq !== loadSeq) return
    devices.value = res.records
    total.value = res.total
  } catch (e) {
    console.error('Failed to load devices', e)
  } finally {
    // 过期请求不能把最新请求的 loading 关掉，否则转圈提前消失
    if (seq === loadSeq) loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadData()
}

function handleReset() {
  searchForm.deviceId = ''
  searchForm.phone = ''
  page.value = 1
  loadData()
}

function handleRefresh() {
  loadData()
}

function handleSizeChange() {
  // 每页条数变大后当前页可能已越界（比如停在第 5 页、每页改成 50 条）：
  // 不回到第 1 页，会先请求一个空页，再由 el-pagination 自动钳位触发第二次请求
  page.value = 1
  loadData()
}

function goToDetail(row: Device) {
  router.push(`/devices/${encodeURIComponent(row.deviceId)}`)
}

// ---------- 行内操作 ----------

const recoveryDialog = ref<InstanceType<typeof RecoveryCodeDialog>>()

/** 正在行内操作的那台设备。非空时禁用所有行内按钮，避免连点发出第二个请求。 */
const actingId = ref('')

/** 设备名可能为空（设备没上报机型），回落到 ID —— 确认框里也要让人认得出是哪台。 */
function displayName(row: Device) {
  return row.deviceName || row.deviceId
}

function handleRecovery(row: Device) {
  // 不用 `?.` 静默吞掉：模板里漏挂 <RecoveryCodeDialog ref="..."> 时，
  // ref 永远是 undefined，表现就是「点按钮没反应」—— 没有报错、没有日志，
  // 现场只会反复点。宁可弹一条明确的错误。
  if (!recoveryDialog.value) {
    ElMessage.error('恢复码弹窗未挂载（模板里缺少 <RecoveryCodeDialog ref="recoveryDialog" />）')
    return
  }
  recoveryDialog.value.open(row.deviceId)
}

async function handleToggleEnabled(row: Device) {
  const willDisable = row.enabled
  try {
    // 与设备详情页同一套文案：可逆操作也确认一次，避免误点
    await ElMessageBox.confirm(
      `确定要${willDisable ? '禁用' : '启用'}设备「${displayName(row)}」吗？`,
      '操作确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return // 用户取消
  }

  actingId.value = row.deviceId
  try {
    await toggleDeviceStatus(row.deviceId, !willDisable)
    ElMessage.success(`设备已${willDisable ? '禁用' : '启用'}`)
    loadData()
  } catch {
    // 失败提示由 http 拦截器统一给出
  } finally {
    actingId.value = ''
  }
}

async function handleDelete(row: Device) {
  try {
    // 说清楚「会连短信一起删」：这是不可撤销的操作，不能只在事后提示
    await ElMessageBox.confirm(
      `将删除设备「${displayName(row)}」。它的短信记录会一并删除` +
        '（否则会留下查不到设备的孤儿记录），且无法恢复。',
      '删除设备',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  actingId.value = row.deviceId
  try {
    const removedSms = await deleteDevice(row.deviceId)
    ElMessage.success(
      removedSms > 0 ? `设备已删除，同时清掉 ${removedSms} 条短信记录` : '设备已删除'
    )

    // 删掉的是本页最后一条时退回上一页，否则会停在一个空页上
    if (devices.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    loadData()
  } catch {
    // 同上
  } finally {
    actingId.value = ''
  }
}

onMounted(loadData)

/**
 * 设备上下线时服务端推一条，收到就刷新当前页 —— 不轮询。
 *
 * 只认 devices 事件：短信事件不影响设备列表，没必要跟着重拉一遍。
 * hello 是重连后的第一条，用来补齐断线期间漏掉的变更。
 */
useAdminEvents((event) => {
  if (event === 'devices' || event === 'hello') loadData()
})
</script>

<style scoped>
/*
 * 筛选栏和表格现在同处一张卡，中间用一条细分隔线分区。
 * 改前是上下两张独立的 el-card：两条边框加 24px 空隙，
 * 一屏还没见到数据就先被页面装饰吃掉一截。
 * 圆角与边框仍由 App.vue 的 .el-card 全局规则给，这里不重复声明。
 */

/*
 * 筛选栏布局，与 SmsList.vue 保持一致。
 * 用 flex + 固定宽度代替 el-form inline：inline 表单靠 inline-block 折行，
 * 断点不可控，且 el-form-item 默认 18px 下边距需要用负边距抵消（原先的
 * margin-bottom: -18px 补丁就是为此而打）。
 * 空间不足时按钮靠右收尾（margin-left: auto），换行也显得是有意为之。
 */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding-bottom: 20px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--color-border-light);
}

.filter-item {
  flex: 0 0 auto;
}

.filter-phone { width: 120px; }
/* 设备 ID 是 36 字符的 UUID，窄了看不见自己在搜什么 */
.filter-device { width: 260px; }

.filter-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
  margin-left: auto;
}

.pagination-wrap {
  margin-top: var(--section-gap);
  display: flex;
  justify-content: flex-end;
}

:deep(.el-table) {
  cursor: pointer;
}
/* 表头配色、行高、斑马纹统一在 App.vue 的 .el-table 里定义，此处不再覆盖 */

/*
 * 行高统一：数据单元格一律不换行，超出部分截断成省略号。
 *
 * el-table 的 .cell 默认 white-space: normal，内容超宽就折行，行高随之变化 ——
 * 「设备名称」长度随机型变、「网络」wifi 短 cellular 长，同一页里个别行被撑成
 * 两行，表格看着参差不齐。靠调列宽治标不治本：数据长度不可控，禁掉换行才是根治。
 * 需要看全的列都配了 show-overflow-tooltip，悬停即可看到完整值。
 *
 * 与 SmsList.vue 保持同一策略；那张表多了展开行，选择器需排除 .el-table__expanded-cell。
 */
:deep(.el-table__body td.el-table__cell > .cell) {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.time-text {
  font-size: 13px;
  color: var(--color-text-secondary);
}

/* 主标识列：比相邻的次要列重一点，扫描时眼睛先落在这里 */
.cell-primary {
  font-weight: 500;
  color: var(--color-text-primary);
}
/* 技术标识列（UUID 之类）：等宽 + 弱化，需要时能逐位核对，平时不抢注意力 */
.cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.battery-high { color: var(--color-success); font-weight: 500; }
.battery-mid { color: var(--color-warning); font-weight: 500; }
.battery-low { color: var(--color-danger); font-weight: 500; }

/*
 * 窄屏：筛选控件各占一行，按钮回到左侧自成一行。
 * flex-wrap 本身就能换行，这里只是让每个控件铺满，否则 120px 的输入框
 * 和 260px 的设备 ID 混在一行里对不齐。与 SmsList.vue 保持一致。
 */
@media (max-width: 640px) {
  .filter-item { width: 100% !important; }
  .filter-actions {
    margin-left: 0;
    width: 100%;
    justify-content: space-between;
  }
}
</style>
