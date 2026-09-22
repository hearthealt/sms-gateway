<template>
  <div class="event-log">
    <el-card shadow="never">
      <!-- Filter Bar -->
      <div class="filter-bar">
        <el-select
          v-model="searchForm.type"
          placeholder="事件类型"
          clearable
          class="filter-item filter-type"
          @change="handleSearch"
        >
          <el-option
            v-for="opt in typeOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-select
          v-model="searchForm.level"
          placeholder="级别"
          clearable
          class="filter-item filter-level"
          @change="handleSearch"
        >
          <el-option label="需要处理" value="ERROR" />
          <el-option label="留意" value="WARN" />
          <el-option label="正常" value="INFO" />
        </el-select>
        <el-input
          v-model="searchForm.deviceId"
          placeholder="设备ID"
          clearable
          class="filter-item filter-device"
          @keyup.enter="handleSearch"
        />
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          format="MM-DD HH:mm"
          value-format="YYYY-MM-DD HH:mm:ss"
          class="filter-item filter-date"
          @change="handleDateChange"
        />
        <div class="filter-buttons">
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon> 重置
          </el-button>
        </div>
      </div>

      <!--
        列序按「排查时先看什么」排：时间 → 级别 → 事件 → 设备 → 发送方/号码 → 原因。
        级别紧跟时间，是因为「一眼扫出红的」是打开这一页最常见的动作。
        没有内容列 —— 这张表本来就不存短信正文（见 EventLogItem 的注释）。
      -->
      <el-table :data="records" stripe style="width: 100%" v-loading="loading">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="levelTagType(row.level)" size="small" effect="plain">
              {{ levelLabel(row.level) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="typeLabel" label="事件" width="160" show-overflow-tooltip />
        <el-table-column label="设备" width="150">
          <template #default="{ row }">
            <!--
              设备行可能已经被删掉（删设备会连同它的历史事件一起清，但「删除」那一条留着），
              那时 deviceId 是 null，只剩 deviceCode 兜底 —— 所以这里判的是两个都为空。
            -->
            <!--
              只在 deviceName 有值时可点。后端的 deviceId 是**兜底过**的：设备行已经不在了
              （删设备那条事件就是这种情况）时它填的是行上冗余的 device_code，
              照它跳过去会落到一个 404 的设备详情页。deviceName 只有活着的设备才有。
            -->
            <template v-if="row.deviceId && row.deviceName">
              <el-tooltip :content="row.deviceId" placement="top">
                <el-link
                  type="primary"
                  underline="never"
                  @click.stop="$router.push(`/devices/${encodeURIComponent(row.deviceId)}`)"
                >
                  {{ row.deviceName }}
                </el-link>
              </el-tooltip>
            </template>
            <span v-else-if="row.deviceId" class="muted" :title="row.deviceId">
              {{ shortDeviceId(row.deviceId) }}（已删除）
            </span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="发送方 / 号码" width="200">
          <template #default="{ row }">
            <span v-if="row.sender || row.phone">
              {{ row.sender || '-' }}
              <span class="muted"> / {{ row.phone || '-' }}</span>
            </span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.reason">{{ row.reason }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="关联短信" width="110" align="center">
          <template #default="{ row }">
            <!--
              只作线索：短信可能已被保留策略清掉（它有自己的过期天数），
              所以这里给的是「按设备跳到短信记录页」，而不是一条必然存在的详情链接。
            -->
            <el-link
              v-if="row.smsMessageId && row.deviceId"
              type="primary"
              underline="never"
              @click.stop="$router.push(`/sms?deviceId=${encodeURIComponent(row.deviceId)}`)"
            >查看</el-link>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无运行事件" :image-size="80" />
        </template>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next, sizes"
          :page-sizes="[10, 15, 30, 50]"
          @current-change="loadData"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import dayjs from 'dayjs'
import { getEventLogList, getEventLogTypes } from '../api/eventlog'
import { shortDeviceId } from '../utils/device'
import { useAdminEvents } from '../composables/useAdminEvents'
import type { EventLogItem, EventTypeOption } from '../types'

const records = ref<EventLogItem[]>([])
const typeOptions = ref<EventTypeOption[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(15)
const total = ref(0)
const dateRange = ref<string[] | null>(null)

const searchForm = reactive({
  type: undefined as string | undefined,
  level: undefined as string | undefined,
  deviceId: '',
  startDate: undefined as string | undefined,
  endDate: undefined as string | undefined,
})

function formatTime(t: string) {
  return dayjs(t).format('YYYY-MM-DD HH:mm:ss')
}

/** 级别 → 标签配色。ERROR 红、WARN 橙、INFO 蓝，与设备列表的在线状态同一套语义。 */
function levelTagType(level: string) {
  if (level === 'ERROR') return 'danger'
  if (level === 'WARN') return 'warning'
  return 'info'
}

function levelLabel(level: string) {
  if (level === 'ERROR') return '需要处理'
  if (level === 'WARN') return '留意'
  return '正常'
}

function handleDateChange(val: [string, string] | null) {
  if (val) {
    searchForm.startDate = val[0]
    searchForm.endDate = val[1]
  } else {
    searchForm.startDate = undefined
    searchForm.endDate = undefined
  }
}

// 请求序号，作用同 SmsList.vue：连点页码时丢弃过期响应
let loadSeq = 0

async function loadData() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = await getEventLogList({
      page: page.value,
      pageSize: pageSize.value,
      type: searchForm.type,
      level: searchForm.level,
      deviceId: searchForm.deviceId || undefined,
      startDate: searchForm.startDate,
      endDate: searchForm.endDate,
    })
    if (seq !== loadSeq) return
    records.value = res.records
    total.value = res.total
  } catch (e) {
    console.error('Failed to load event log', e)
  } finally {
    // 过期请求不能把最新请求的 loading 关掉
    if (seq === loadSeq) loading.value = false
  }
}

/** 类型选项失败不该让整页打不开：拿不到就退化成「不按类型筛」，其余照常。 */
async function loadTypes() {
  try {
    typeOptions.value = await getEventLogTypes()
  } catch (e) {
    console.error('Failed to load event types', e)
  }
}

function handleSearch() {
  page.value = 1
  loadData()
}

function handleReset() {
  searchForm.type = undefined
  searchForm.level = undefined
  searchForm.deviceId = ''
  searchForm.startDate = undefined
  searchForm.endDate = undefined
  dateRange.value = null
  page.value = 1
  loadData()
}

function handleSizeChange() {
  page.value = 1
  loadData()
}

onMounted(() => {
  loadTypes()
  loadData()
})

/**
 * 有新事件落库时推过来，收到就重新拉**当前这一页** —— 不轮询。
 * `hello` 覆盖断线期间漏掉的事件（服务端在订阅成功时会立刻补推一条）。
 */
useAdminEvents((event) => {
  if (event === 'events' || event === 'hello') loadData()
})
</script>

<style scoped>
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

.filter-type { width: 150px; }
.filter-level { width: 120px; }
.filter-device { width: 150px; }

/* el-date-editor 自带宽度，需覆盖 */
.filter-date {
  width: 250px !important;
}

.filter-buttons {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.pagination-wrap {
  margin-top: var(--section-gap);
  display: flex;
  justify-content: flex-end;
}

/* 行高统一：数据单元格一律不换行，超出截断成省略号（与 SmsList.vue 一致） */
:deep(.el-table__body td.el-table__cell > .cell) {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.time-text { font-size: 13px; color: var(--color-text-secondary); }
.muted { color: var(--color-text-placeholder); }

@media (max-width: 640px) {
  .filter-item { width: 100% !important; }
  .filter-buttons {
    margin-left: 0;
    width: 100%;
  }
}
</style>
