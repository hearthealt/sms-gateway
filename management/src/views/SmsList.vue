<template>
  <div class="sms-list">
    <el-card shadow="never">
      <!-- Filter Bar -->
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

      <!--
        列序按「这条短信是什么」排：时间 → 收发号码 → 正文 → 验证码，
        设备引用（36 字符 UUID，且只显示前 8 位）挪到靠后。
        改前它紧跟展开箭头，还是唯一的 min-width 列，把正文挤到只剩 170px；
        另有一列 70px 的「ID」是自增主键，列表里没人按它找东西，已并入展开行。

        列宽合计 1032px，1366 窗口可用宽约 1042px，刚好不横向滚动（改前 1156px，会滚）。
      -->
      <el-table
        :data="records"
        stripe
        style="width: 100%"
        v-loading="loading"
        :row-key="(row: any) => row.id"
      >
        <el-table-column type="expand" width="40">
          <template #default="{ row }">
            <div class="expand-content">
              <p class="expand-label">完整内容</p>
              <p class="expand-text">{{ row.content }}</p>
              <!-- 右侧「更新时间」列显示的是**最后一次**到达；首次到达只在这里能看到 -->
              <p v-if="row.duplicateCount > 0" class="expand-meta">
                首次到达 {{ formatTime(row.receiveTime) }}，之后又收到过
                {{ row.duplicateCount }} 次
              </p>
              <p class="expand-meta">记录 ID：{{ row.id }}</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="150" show-overflow-tooltip />
        <el-table-column prop="sender" label="发送号码" width="160" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
        <el-table-column label="验证码" width="96">
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
        <el-table-column label="设备" width="100">
          <template #default="{ row }">
            <el-tooltip v-if="row.deviceId" :content="row.deviceId" placement="top">
              <el-link
                type="primary"
                underline="never"
                @click.stop="$router.push(`/devices/${encodeURIComponent(row.deviceId)}`)"
              >
                {{ shortDeviceId(row.deviceId) }}
              </el-link>
            </el-tooltip>
            <span v-else class="no-code">-</span>
          </template>
        </el-table-column>
        <!--
          列名不能叫「采集」：这一格装的是**服务端对这条短信的判定**，而不只是
          「采没采」—— 命中 ignore 规则是「已忽略」，同一内容反复到达是「重复 N 次」，
          正常收下则什么都不标。叫「采集」的话，「已忽略」和「重复 N 次」两个回复
          都答不上「采集了没」这个问题。
          「判定」这个词是跟着 SmsRecord.status 的注释来的（那个字段写的正是
          「服务端判定」，只有 RECEIVED / IGNORED 两种，重复看 duplicateCount）。
        -->
        <el-table-column label="判定" width="150" align="center">
          <template #default="{ row }">
            <div class="verdict-cell">
              <el-tag v-if="row.status === 'IGNORED'" type="danger" size="small" effect="plain">已忽略</el-tag>
              <!-- 重复到达不单开一行，计数就记在这一格上；具体时刻看右侧「更新时间」列。
                   展开行里还有一句完整的（首次是什么时候、之后又来过几次）。

                   和上面那个 tag 是**并列**的，不是 `v-else-if`：命中 ignore 规则的短信
                   照样会被重复投递（同一个码连来几次），挤掉的话那次数就再也看不到了 ——
                   而「同样的内容又来了几次」正是判断「被重放 / 双卡各收了一遍」的唯一线索。

                   也刻意不判 `status === 'DUPLICATE'`：库里的 status 只可能是 RECEIVED 或
                   IGNORED（去重是撞 uk_device_source_hash 之后更新原行的 duplicate_count，
                   不新插行），那个分支永远命中不了。 -->
              <el-tag v-if="row.duplicateCount > 0" type="warning" size="small" effect="plain">
                重复 {{ row.duplicateCount }} 次
              </el-tag>
              <!-- 一个标签都没有时才用「-」占位。不能写成某个标签的 v-else：
                   那样「已收下但重复过 2 次」会显示成「- 重复 2 次」，那个横杠
                   读起来像是「状态未知」。 -->
              <span v-if="row.status !== 'IGNORED' && row.duplicateCount === 0" class="no-code">-</span>
            </div>
          </template>
        </el-table-column>
        <!-- 放在最后：它是「这条记录什么时候动过」，不是阅读这条短信的入口。
             首次到达时间见展开行。 -->
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无短信记录" :image-size="80" />
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
import { useRoute } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { getSmsList } from '../api/sms'
import { copyText } from '../utils/clipboard'
import { shortDeviceId } from '../utils/device'
import { useAdminEvents } from '../composables/useAdminEvents'
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

/**
 * 服务端有新短信、或某条的重复计数变化时推过来，收到就重新拉**当前这一页** ——
 * 不轮询。翻到第几页、筛了什么条件都原样保留，不会把用户从正在看的位置踢走。
 */
useAdminEvents((event) => {
  if (event === 'sms' || event === 'hello') loadData()
})
</script>

<style scoped>
/*
 * 筛选栏和表格同处一张卡，中间用一条细分隔线分区（与 DeviceList.vue 一致）。
 * 圆角与边框由 App.vue 的 .el-card 全局规则给，这里不重复声明。
 */

/*
 * 筛选栏一行放下全部条件。
 * 用 flex + 固定宽度代替 el-form inline：inline 表单靠 inline-block 折行，
 * 断点不可控，容易出现「第二行只剩时间选择和按钮」的散乱布局。
 * 空间不足时按钮靠右收尾（margin-left: auto），换行也显得是有意为之。
 *
 * 宽度预算：窗口宽 − 侧边栏 − 2×var(--main-padding-x) − 2×卡片内边距 = 可用宽。
 * 侧边栏 220px（≤1200px 自动收起为 64px），--main-padding-x 28px（≤1200px 收为 20px）。
 * 控件合计约 636px，加动作区（开关 + 两个按钮）约 310px，总计约 946px。
 * 因此 ≥1366 窗口（可用约 1050）能一行放下；更窄时侧边栏收起还能再让出 156px。
 * 日期选择器用 format="MM-DD HH:mm" 压缩显示，否则两个完整日期时间要 340px+。
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

.pagination-wrap {
  margin-top: var(--section-gap);
  display: flex;
  justify-content: flex-end;
}

/* 表头配色、行高、斑马纹统一在 App.vue 的 .el-table 里定义，此处不再覆盖 */

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
/* 记录 ID 从主表挪到了这里：需要时展开仍看得到，主表就空出一列给正文 */
.expand-meta {
  margin: 10px 0 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--color-text-placeholder);
}

.time-text { font-size: 13px; color: var(--color-text-secondary); }
.no-code { color: var(--color-text-placeholder); }

/* 「判定」标签与「重复 N 次」并排：两者是并列的信息（一条短信可以既被忽略又被重复投递），
   挤在一行放不下时换行，而不是把后面那个顶掉。 */
.verdict-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  justify-content: center;
}

/*
 * 窄屏：筛选控件各占一行，动作区（开关 + 按钮）自成一行。
 * !important 在这里是必需的：上面的 .filter-date 已经用 !important 压过
 * el-date-editor 的自带宽度，同优先级下后面的规则赢不了它。
 * 与 DeviceList.vue 保持一致。
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
