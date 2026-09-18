<template>
  <div class="sms-list">
    <!-- Filter Bar -->
    <el-card shadow="never" class="search-card">
      <div class="filter-bar">
        <el-input
          v-model="searchForm.phone"
          placeholder="手机号"
          clearable
          class="filter-item filter-phone"
          @keyup.enter="handleSearch"
        />
        <el-input
          v-model="searchForm.code"
          placeholder="验证码"
          clearable
          class="filter-item filter-code"
          @keyup.enter="handleSearch"
        />
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
        <div class="filter-actions">
          <div class="filter-switch">
            <span class="switch-label">显示已忽略</span>
            <el-switch v-model="includeIgnored" @change="handleSearch" />
          </div>
          <div class="filter-buttons">
            <el-button type="primary" @click="handleSearch">
              <el-icon><Search /></el-icon> 搜索
            </el-button>
            <el-button @click="handleReset">
              <el-icon><Refresh /></el-icon> 重置
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- SMS Table -->
    <el-card shadow="never" class="table-card">
      <el-table
        :data="records"
        stripe
        style="width: 100%"
        v-loading="loading"
        :row-key="(row: any) => row.id"
        empty-text="暂无短信记录"
      >
        <el-table-column type="expand" width="40">
          <template #default="{ row }">
            <div class="expand-content">
              <p class="expand-label">完整内容</p>
              <p class="expand-text">{{ row.content }}</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="设备" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link
              v-if="row.deviceId"
              type="primary"
              :underline="false"
              @click.stop="$router.push(`/devices/${encodeURIComponent(row.deviceId)}`)"
            >
              {{ row.deviceId }}
            </el-link>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="140" show-overflow-tooltip />
        <el-table-column prop="sender" label="发送号码" width="150" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="170" show-overflow-tooltip />
        <el-table-column label="验证码" width="100">
          <template #default="{ row }">
            <el-tag
              v-if="row.code"
              type="warning"
              effect="dark"
              size="small"
              style="cursor: pointer"
              @click.stop="copyCode(row.code)"
            >{{ row.code }}</el-tag>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.receiveTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="采集" width="86" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'IGNORED'" type="danger" size="small" effect="plain">已忽略</el-tag>
            <el-tag v-else-if="row.status === 'DUPLICATE'" type="info" size="small" effect="plain">重复</el-tag>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <el-table-column label="读取" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isRead ? 'success' : 'info'" size="small" effect="plain">
              {{ row.isRead ? '已读' : '未读' }}
            </el-tag>
          </template>
        </el-table-column>
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
import { useRoute } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { getSmsList } from '../api/sms'
import { copyText } from '../utils/clipboard'
import type { SmsRecord } from '../types'

const route = useRoute()
const records = ref<SmsRecord[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(15)
const total = ref(0)
const dateRange = ref<string[] | null>(null)
// 视图选项而非搜索条件：不参与「重置」，切换后立即重新查询
const includeIgnored = ref(false)

const searchForm = reactive({
  phone: '',
  code: '',
  deviceId: '',
  startDate: undefined as string | undefined,
  endDate: undefined as string | undefined,
})

function formatTime(t: string) {
  return dayjs(t).format('YYYY-MM-DD HH:mm:ss')
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

async function copyCode(code: string) {
  if (await copyText(code)) {
    ElMessage.success('验证码已复制')
  } else {
    ElMessage.error('复制失败')
  }
}

// 请求序号，作用同 DeviceList.vue：连点页码时丢弃过期响应
let loadSeq = 0

async function loadData() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = await getSmsList({
      page: page.value,
      pageSize: pageSize.value,
      phone: searchForm.phone || undefined,
      code: searchForm.code || undefined,
      deviceId: searchForm.deviceId || undefined,
      startDate: searchForm.startDate,
      endDate: searchForm.endDate,
      includeIgnored: includeIgnored.value,
    })
    if (seq !== loadSeq) return
    records.value = res.records
    total.value = res.total
  } catch (e) {
    console.error('Failed to load SMS records', e)
  } finally {
    // 过期请求不能把最新请求的 loading 关掉
    if (seq === loadSeq) loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadData()
}

function handleReset() {
  searchForm.phone = ''
  searchForm.code = ''
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
  // 设备详情页跳转过来时带 deviceId，作为初始过滤条件
  const queryDeviceId = route.query.deviceId
  if (typeof queryDeviceId === 'string' && queryDeviceId) {
    searchForm.deviceId = queryDeviceId
  }
  loadData()
})
</script>

<style scoped>
.search-card {
  margin-bottom: 16px;
  border-radius: var(--border-radius-base);
  border: 1px solid var(--color-border-light);
}

/*
 * 筛选栏一行放下全部条件。
 * 用 flex + 固定宽度代替 el-form inline：inline 表单靠 inline-block 折行，
 * 断点不可控，容易出现「第二行只剩时间选择和按钮」的散乱布局。
 * 空间不足时按钮靠右收尾（margin-left: auto），换行也显得是有意为之。
 *
 * 宽度预算：窗口宽 − 220(侧边栏) − 56(主区 padding) − 40(卡片 padding) = 可用宽。
 * 控件合计约 636px，加动作区（开关 + 两个按钮）约 310px，总计约 946px。
 * 因此 ≥1366 窗口（可用 1050）也能一行放下。
 * 日期选择器用 format="MM-DD HH:mm" 压缩显示，否则两个完整日期时间要 340px+。
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
.filter-code { width: 95px; }
.filter-device { width: 135px; }

/* el-date-editor 自带宽度，需覆盖 */
.filter-date {
  width: 250px !important;
}

.filter-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 16px;
  margin-left: auto;
}

.filter-switch {
  display: flex;
  align-items: center;
  gap: 6px;
}

.switch-label {
  font-size: 13px;
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.filter-buttons {
  display: flex;
  gap: 8px;
}

.table-card {
  border-radius: var(--border-radius-base);
  border: 1px solid var(--color-border-light);
}

.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
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
 * 「设备」（36 字符 UUID）必然会折，「发送号码」（10086 到 16 位 106 号段不等）
 * 只有长号段才折，于是同一页里个别行被撑成两行，表格看着参差不齐。
 * 靠调列宽治标不治本：数据长度不可控，禁掉换行才是根治。
 * 需要看全的列都配了 show-overflow-tooltip，悬停即可看到完整值。
 *
 * 展开行（.el-table__expanded-cell）承载完整短信正文，必须保留换行，故排除；
 * 它的内容直接挂在 td 下，本就没有 .cell 包裹，这里显式排除是为了意图明确。
 */
:deep(.el-table__body td.el-table__cell:not(.el-table__expanded-cell) > .cell) {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.expand-content {
  padding: 16px 24px;
  background: var(--color-bg);
  border-radius: var(--border-radius-small);
}
.expand-label {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin: 0 0 8px;
  font-weight: 500;
}
.expand-text {
  font-size: 14px;
  color: var(--color-text-primary);
  line-height: 1.7;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.time-text { font-size: 13px; color: var(--color-text-secondary); }
.no-code { color: var(--color-text-placeholder); }
</style>
