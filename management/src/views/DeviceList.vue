<template>
  <div class="device-list">
    <!-- Search Bar -->
    <el-card shadow="never" class="search-card">
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
    </el-card>

    <!-- Device Table -->
    <el-card shadow="never" class="table-card">
      <el-table
        :data="devices"
        stripe
        style="width: 100%"
        v-loading="loading"
        @row-click="goToDetail"
        highlight-current-row
        empty-text="暂无设备数据"
      >
        <!-- 设备 ID 现在是 36 字符的 UUID，150px 会挤成一团；配 tooltip 让长值可悬停查看 -->
        <el-table-column prop="deviceId" label="设备ID" min-width="260" show-overflow-tooltip />
        <!-- deviceName 是 "厂商 + 机型"（如 "Xiaomi Redmi Note 12 Pro"），130px 会折行 -->
        <el-table-column prop="deviceName" label="设备名称" min-width="180" show-overflow-tooltip />
        <!-- 设备读不到本机号码时 phone 为空，用 - 兜底，与相邻列保持一致 -->
        <el-table-column label="手机号" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <status-badge :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="最后心跳" width="170">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.lastHeartbeat) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="应用版本" width="90" prop="appVersion" show-overflow-tooltip />
        <el-table-column label="电量" width="90">
          <template #default="{ row }">
            <span v-if="row.battery !== null" :class="batteryClass(row.battery)">{{ row.battery }}%</span>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <!-- 取值 wifi / cellular / ethernet，80px 装不下 cellular，会折行 -->
        <el-table-column label="网络" width="90" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.network">{{ row.network }}</span>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import StatusBadge from '../components/StatusBadge.vue'
import { getDeviceList } from '../api/device'
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

onMounted(loadData)
</script>

<style scoped>
.search-card {
  margin-bottom: 16px;
  border-radius: var(--border-radius-base);
  border: 1px solid var(--color-border-light);
}
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

.table-card {
  border-radius: var(--border-radius-base);
  border: 1px solid var(--color-border-light);
}

.pagination-wrap {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}

:deep(.el-table) {
  cursor: pointer;
}
:deep(.el-table th.el-table__cell) {
  background: var(--color-bg) !important;
  color: var(--color-text-regular);
  font-weight: 600;
}

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
.battery-high { color: var(--color-success); font-weight: 500; }
.battery-mid { color: var(--color-warning); font-weight: 500; }
.battery-low { color: var(--color-danger); font-weight: 500; }
</style>
