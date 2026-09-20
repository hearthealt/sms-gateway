<template>
  <div class="notify-delivery">
    <el-card shadow="never">
      <div class="filter-bar">
        <el-select v-model="filter.channelId" placeholder="全部渠道" clearable class="filter-item">
          <el-option v-for="c in channels" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="filter.status" placeholder="全部状态" clearable class="filter-item">
          <!-- DEAD 排最前：那才是需要人管的那些。FAILED 还会自己重试。 -->
          <el-option label="已放弃（需处理）" value="DEAD" />
          <el-option label="待投递" value="PENDING" />
          <el-option label="失败（会重试）" value="FAILED" />
          <el-option label="发送中" value="SENDING" />
          <el-option label="成功" value="SUCCESS" />
          <!-- 放最后：停用渠道时批量产生的，不是需要人管的东西 -->
          <el-option label="已取消（渠道停用）" value="CANCELLED" />
        </el-select>
        <div class="filter-actions">
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button @click="loadData" :loading="loading">刷新</el-button>
        </div>
      </div>

      <el-table :data="deliveries" stripe style="width: 100%" v-loading="loading">
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip v-if="isPaused(row)" placement="top" :content="PAUSED_HINT">
              <el-tag type="info" size="small">已暂停</el-tag>
            </el-tooltip>
            <el-tag v-else :type="statusType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="channelName" label="渠道" width="140" show-overflow-tooltip />
        <el-table-column label="短信" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-mono">{{ row.sender || '-' }}</span>
            <!-- 预览是打过码的：这条记录本身就不存渲染后的正文（存了等于把验证码写两遍、第二遍还没 TTL） -->
            <span class="preview">{{ row.contentPreview || '' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="尝试" width="70" align="center" prop="attempts" />
        <el-table-column label="下次重试" width="150">
          <template #default="{ row }">
            <!--
              渠道停用时不显示时间：那个时刻停在过去，显示出来会被读成「马上要重试了」，
              而实际上它不会再发 —— 重新启用渠道才会。
            -->
            <span
              v-if="!isPaused(row) && (row.status === 'PENDING' || row.status === 'FAILED')"
              class="time-text"
            >
              {{ formatTime(row.nextRetryAt) }}
            </span>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <!--
          **成功的一律不显示**，不管库里有没有值。成功的结果按定义就没有「失败原因」，
          而曾经有个 bug 就是给成功记录写了一句「HTTP 200：{...}」—— 状态是绿的、
          旁边却挂着一句报错，现场据此以为转发一直在失败。
          后端已经修了，这里再挡一道：显示层不该因为一条脏数据就误导人。
        -->
        <el-table-column label="失败原因" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.lastError && row.status !== 'SUCCESS'" class="error-text">
              {{ row.lastError }}
            </span>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <!--
          与另外两张转发页保持同一套按钮样式（link 型、居中）。

          渠道停用时**禁掉重投**：重投会把它变回待投递，而调度器会滤掉停用渠道 ——
          于是它又回到「待投递 + 下次重试 13:51」那个谁也没法处理的状态。
          后端也挡了一道，这里挡是为了省掉一次没意义的往返。
          （禁用按钮收不到鼠标事件，所以外面套一层 span 让 tooltip 能弹出来。）
        -->
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-tooltip :disabled="!isPaused(row)" :content="RETRY_BLOCKED_HINT" placement="top">
              <span>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :disabled="retryBlocked(row) || retryingId === row.id"
                  @click="handleRetry(row)"
                >
                  重投
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无投递记录" :image-size="80" />
        </template>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next, sizes"
          :page-sizes="[20, 50, 100]"
          @current-change="loadData"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { getChannelList, getDeliveryList, retryDelivery } from '../api/notify'
import type { NotifyChannel, NotifyDelivery } from '../types'

const deliveries = ref<NotifyDelivery[]>([])
const channels = ref<NotifyChannel[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const retryingId = ref<number | null>(null)

const filter = reactive({
  channelId: undefined as number | undefined,
  status: undefined as string | undefined,
})

function formatTime(t: string | null) {
  return t ? dayjs(t).format('MM-DD HH:mm:ss') : '-'
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'DEAD':
      return 'danger'
    case 'FAILED':
      return 'warning'
    case 'SENDING':
      return 'primary'
    default:
      // PENDING 与 CANCELLED 都是灰的：前者还排着队，后者已经不用管了
      return 'info'
  }
}

/**
 * 终态：这条记录已经有了结论，不会再自己变化。
 *
 * 判断「还会不会继续投递」必须先排除它们 —— 否则已放弃、已取消的记录在渠道停用时
 * 会被显示成「已暂停」，而提示里写着「重新启用该渠道后会继续」。**那是假话**：
 * 它们不会再发了。
 */
const TERMINAL_STATUSES = new Set(['SUCCESS', 'DEAD', 'CANCELLED'])

/**
 * 这条记录还会不会**自己**发出去。
 *
 * 只对非终态成立：渠道停用后它们停在待投递/失败，`nextRetryAt` 也停在过去 ——
 * 只看状态会读成「马上要重试了」，实际不会再动，要重新启用渠道才会。
 */
function isPaused(row: NotifyDelivery): boolean {
  return !TERMINAL_STATUSES.has(row.status) && !row.channelEnabled
}

/**
 * 重投按钮能不能点。
 *
 * 与 {@link isPaused} **不是一回事**：已经放弃/取消的记录虽然不会再自己发，
 * 但渠道只要启用着就允许手动重投（那正是「重投」这个按钮的用途）。
 * 而渠道没启用的记录点了也是白点 —— 后端会拒，按钮先挡住省一次往返。
 */
function retryBlocked(row: NotifyDelivery): boolean {
  return row.status === 'SUCCESS' || !row.channelEnabled
}

const PAUSED_HINT = '所属渠道已停用，这条不会再投递。重新启用该渠道后会继续。'
const RETRY_BLOCKED_HINT = '所属渠道已停用。先在「转发渠道」页启用它，再回来重投。'

function statusLabel(status: string): string {
  switch (status) {
    case 'SUCCESS':
      return '成功'
    case 'DEAD':
      return '已放弃'
    case 'FAILED':
      return '失败'
    case 'SENDING':
      return '发送中'
    case 'CANCELLED':
      return '已取消'
    default:
      return '待投递'
  }
}

async function loadData() {
  loading.value = true
  try {
    const res = await getDeliveryList({
      page: page.value,
      pageSize: pageSize.value,
      channelId: filter.channelId,
      status: filter.status,
    })
    deliveries.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  channels.value = await getChannelList()
  await loadData()
})

function handleSearch() {
  page.value = 1
  loadData()
}

function handleReset() {
  filter.channelId = undefined
  filter.status = undefined
  page.value = 1
  loadData()
}

function handleSizeChange() {
  page.value = 1
  loadData()
}

async function handleRetry(row: NotifyDelivery) {
  retryingId.value = row.id
  try {
    await retryDelivery(row.id)
    ElMessage.success('已重新排入投递队列')
    await loadData()
  } catch {
    // 拦截器已提示
  } finally {
    retryingId.value = null
  }
}
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
  width: 180px;
}
.filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
.pagination-wrap {
  margin-top: var(--section-gap);
  display: flex;
  justify-content: flex-end;
}
.preview {
  margin-left: 8px;
  font-size: 13px;
  color: var(--color-text-secondary);
}
.cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.error-text {
  color: var(--color-danger);
  font-size: 12px;
}
.time-text {
  font-size: 13px;
  color: var(--color-text-secondary);
}
.no-code {
  color: var(--color-text-placeholder, #c0c4cc);
}
</style>
