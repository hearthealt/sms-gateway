<template>
  <div class="dashboard" v-loading="loading" element-loading-text="加载中...">
    <!-- ═══ KPI 指标卡片 ═══ -->
    <el-row :gutter="20" class="kpi-row">
      <el-col :xs="24" :sm="12" :lg="6">
        <div class="kpi-card" @click="$router.push('/devices')">
          <div class="kpi-head">
            <span class="kpi-icon kpi-icon--success"><el-icon :size="20"><Monitor /></el-icon></span>
            <span class="kpi-label">在线设备</span>
          </div>
          <div class="kpi-value">{{ loading ? '—' : stats.onlineDevices }}</div>
          <div class="kpi-meta">
            <span v-if="!loading">在线率 {{ onlineRate }}%</span>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <div class="kpi-card" @click="$router.push('/devices')">
          <div class="kpi-head">
            <span class="kpi-icon kpi-icon--danger"><el-icon :size="20"><WarningFilled /></el-icon></span>
            <span class="kpi-label">离线设备</span>
          </div>
          <!-- 全页唯一一个上语义色的数字：有设备离线才是要看的问题，为 0 时保持中性 -->
          <div class="kpi-value" :class="{ 'is-danger': !loading && stats.offlineDevices > 0 }">
            {{ loading ? '—' : stats.offlineDevices }}
          </div>
          <div class="kpi-meta">
            <span v-if="!loading">离线率 {{ offlineRate }}%</span>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <div class="kpi-card" @click="$router.push('/sms')">
          <div class="kpi-head">
            <span class="kpi-icon kpi-icon--primary"><el-icon :size="20"><ChatDotSquare /></el-icon></span>
            <span class="kpi-label">今日短信</span>
          </div>
          <div class="kpi-value">{{ loading ? '—' : stats.todaySms }}</div>
          <div class="kpi-meta">
            <span v-if="!loading">较昨日</span>
            <span v-if="!loading" class="kpi-delta" :class="deltaClass(smsDayOverDay)">{{ formatDelta(smsDayOverDay) }}</span>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <div class="kpi-card" @click="$router.push('/sms')">
          <div class="kpi-head">
            <span class="kpi-icon kpi-icon--primary"><el-icon :size="20"><Key /></el-icon></span>
            <span class="kpi-label">今日验证码</span>
          </div>
          <div class="kpi-value">{{ loading ? '—' : stats.todayCodes }}</div>
          <div class="kpi-meta">
            <span v-if="!loading">较昨日</span>
            <span v-if="!loading" class="kpi-delta" :class="deltaClass(codeDayOverDay)">{{ formatDelta(codeDayOverDay) }}</span>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ═══ 图表区 + 最近设备 ═══ -->
    <el-row :gutter="20" class="middle-row">
      <!-- 设备状态环形图 -->
      <el-col :xs="24" :lg="8">
        <div class="chart-card">
          <div class="chart-card-header">
            <h3>设备状态分布</h3>
          </div>
          <div class="chart-body donut-body">
            <div class="donut-wrap" v-if="loading || stats.totalDevices > 0">
              <svg viewBox="0 0 120 120" class="donut-svg">
                <circle cx="60" cy="60" r="50" fill="none" stroke="#ebeef5" stroke-width="12" />
                <circle
                  cx="60" cy="60" r="50" fill="none"
                  stroke="#409eff" stroke-width="12" stroke-linecap="round"
                  :stroke-dasharray="donutDasharray"
                  stroke-dashoffset="0"
                  transform="rotate(-90 60 60)"
                  class="donut-segment"
                />
                <circle
                  cx="60" cy="60" r="50" fill="none"
                  stroke="#f56c6c" stroke-width="12" stroke-linecap="round"
                  :stroke-dasharray="donutDasharrayOffline"
                  :stroke-dashoffset="donutOffsetOffline"
                  transform="rotate(-90 60 60)"
                  class="donut-segment"
                />
              </svg>
              <div class="donut-center">
                <span class="donut-total">{{ loading ? '—' : stats.totalDevices }}</span>
                <span class="donut-label">总计设备</span>
              </div>
            </div>
            <div class="donut-legend" v-if="loading || stats.totalDevices > 0">
              <div class="legend-item">
                <span class="legend-dot dot-blue"></span>
                <span class="legend-text">在线</span>
                <span class="legend-value">{{ stats.onlineDevices }}</span>
              </div>
              <div class="legend-item">
                <span class="legend-dot dot-red"></span>
                <span class="legend-text">离线</span>
                <span class="legend-value">{{ stats.offlineDevices }}</span>
              </div>
            </div>
            <!-- v-else 接在 .donut-legend 上：环和它的图例一起消失，换成空态 -->
            <el-empty v-else description="暂无设备" :image-size="60" />
          </div>
        </div>
      </el-col>

      <!-- 短信趋势柱状图 (纯CSS) -->
      <el-col :xs="24" :lg="16">
        <div class="chart-card">
          <div class="chart-card-header">
            <h3>近7日短信趋势</h3>
            <el-button text type="primary" size="small" @click="$router.push('/sms')">
              详情 <el-icon><ArrowRight /></el-icon>
            </el-button>
          </div>
          <div class="chart-body bar-chart-body">
            <div class="bar-chart" v-if="loading || weeklyData.length">
              <div class="bar-item" v-for="(item, idx) in weeklyData" :key="idx">
                <div class="bar-label">{{ item.day }}</div>
                <div class="bar-track">
                  <div
                    class="bar-fill"
                    :style="{ height: item.percent + '%', '--bar-color': item.color }"
                  ></div>
                </div>
                <div class="bar-value">{{ item.value }}</div>
              </div>
            </div>
            <div class="bar-summary" v-if="!loading && weeklyData.length">
              <div class="summary-item">
                <span class="summary-num">{{ weekTotal.toLocaleString() }}</span>
                <span class="summary-label">本周总计</span>
              </div>
              <div class="summary-item">
                <span class="summary-num">{{ dailyAvg.toLocaleString() }}</span>
                <span class="summary-label">日均</span>
              </div>
              <div class="summary-item">
                <span class="summary-num" :class="{ up: weekOverWeek !== null && weekOverWeek >= 0 }">
                  {{ formatTrend(weekOverWeek) }}
                </span>
                <span class="summary-label">环比上周</span>
              </div>
            </div>
            <!-- v-else-if 接在 .bar-summary 上；加载中两个都不满足，不会闪空态 -->
            <el-empty v-else-if="!loading" description="暂无短信数据" :image-size="60" />
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ═══ 最近设备列表 ═══ -->
    <div class="recent-section">
      <div class="section-header">
        <div class="section-header-left">
          <h3>最近活跃设备</h3>
          <el-tag size="small" type="info" effect="plain">{{ recentDevices.length }} 台</el-tag>
        </div>
        <el-button text type="primary" @click="$router.push('/devices')">
          查看全部 <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
      <div class="device-grid" v-if="loading || recentDevices.length">
        <div
          v-for="device in recentDevices"
          :key="device.id"
          class="device-mini-card"
          @click="goToDetail(device)"
        >
          <!--
            状态只由 StatusBadge 表达一次。原先这里另有一个彩色圆点（配 tooltip）重复表达同一状态，
            而且两者对「已禁用」的取色还相反：StatusBadge 给红、圆点给灰，同一张卡上自相矛盾。
          -->
          <div class="dmc-top">
            <el-tooltip :content="device.deviceId" placement="top">
              <span class="dmc-id">{{ device.deviceId }}</span>
            </el-tooltip>
            <status-badge :status="device.status" />
          </div>
          <div class="dmc-info">
            <div class="dmc-row">
              <el-icon :size="14"><Iphone /></el-icon>
              <span>{{ device.phone || '-' }}</span>
            </div>
            <div class="dmc-row">
              <el-icon :size="14"><Timer /></el-icon>
              <span>{{ formatTime(device.lastHeartbeat) }}</span>
            </div>
          </div>
          <div class="dmc-footer">
            <div class="dmc-tag">{{ device.network || '-' }}</div>
            <div
              v-if="device.battery !== null"
              class="dmc-battery"
              :class="batteryClass(device.battery)"
            >
              <el-icon :size="12" v-if="device.charging"><Lightning /></el-icon>
              {{ device.battery }}%
            </div>
            <div v-else class="dmc-battery">-</div>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无设备" :image-size="80" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'
import StatusBadge from '../components/StatusBadge.vue'
import { getDeviceStats, getDeviceList } from '../api/device'
import { getDailyStats } from '../api/sms'
import { useAdminEvents } from '../composables/useAdminEvents'
import type { Stats, Device, DailyStat } from '../types'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

const router = useRouter()

// ── Data ──
const stats = ref<Stats>({
  onlineDevices: 0,
  offlineDevices: 0,
  totalDevices: 0,
  todaySms: 0,
  todayCodes: 0,
})

const recentDevices = ref<Device[]>([])
// 取 14 天：最后 7 天画图，再往前 7 天用于算环比
const dailyStats = ref<DailyStat[]>([])

/*
 * 初始为 true：stats 的初值是全 0、recentDevices/dailyStats 是空数组，
 * 不区分「在飞」和「真的没有」，慢网络下整页显示「在线设备 0 / 在线率 0%」，
 * 看着像这台机器上一台设备都没有。加载中一律显示 — 而不是 0。
 */
const loading = ref(true)

/*
 * 在线/离线率。原先这两条比例在模板里各写了一遍三元表达式、
 * 环形图的 dasharray 里又各算一次，同一份算法散在四处，改一处漏三处。
 */
const onlineRate = computed(() =>
  stats.value.totalDevices > 0 ? Math.round((stats.value.onlineDevices / stats.value.totalDevices) * 100) : 0,
)
const offlineRate = computed(() =>
  stats.value.totalDevices > 0 ? Math.round((stats.value.offlineDevices / stats.value.totalDevices) * 100) : 0,
)

// ── 环形图参数 ──
const circumference = 2 * Math.PI * 50 // ~314.16
const donutDasharray = computed(() => {
  const ratio = stats.value.totalDevices > 0 ? stats.value.onlineDevices / stats.value.totalDevices : 0
  return `${ratio * circumference} ${circumference - ratio * circumference}`
})
const donutDasharrayOffline = computed(() => {
  const ratio = stats.value.totalDevices > 0 ? stats.value.offlineDevices / stats.value.totalDevices : 0
  return `${ratio * circumference} ${circumference - ratio * circumference}`
})
const donutOffsetOffline = computed(() => {
  const ratio = stats.value.totalDevices > 0 ? stats.value.onlineDevices / stats.value.totalDevices : 0
  return -ratio * circumference
})

// ── 柱状图数据（近 7 天，来自接口） ──
const chartDays = computed(() => dailyStats.value.slice(-7))

const weeklyData = computed(() => {
  const days = chartDays.value
  const max = Math.max(1, ...days.map((d) => d.value))
  return days.map((d) => ({
    day: dayjs(d.day).format('MM-DD'),
    value: d.value,
    percent: Math.round((d.value / max) * 100),
    // 内联到 --bar-color 上，var() 在内联样式里正常解析
    color: 'var(--color-primary)',
  }))
})

const weekTotal = computed(() => chartDays.value.reduce((sum, d) => sum + d.value, 0))

const dailyAvg = computed(() =>
  chartDays.value.length ? Math.round(weekTotal.value / chartDays.value.length) : 0,
)

/** 环比上周。数据不足 14 天或上周为 0 时返回 null（前端显示 '-'，不编造数字）。 */
const weekOverWeek = computed(() => {
  const all = dailyStats.value
  if (all.length < 14) return null
  const thisWeek = all.slice(-7).reduce((sum, d) => sum + d.value, 0)
  const prevWeek = all.slice(-14, -7).reduce((sum, d) => sum + d.value, 0)
  return prevWeek === 0 ? null : ((thisWeek - prevWeek) / prevWeek) * 100
})

const smsDayOverDay = computed(() => dayOverDay((d) => d.value))
const codeDayOverDay = computed(() => dayOverDay((d) => d.codes))

function dayOverDay(pick: (d: DailyStat) => number): number | null {
  const all = dailyStats.value
  if (all.length < 2) return null
  const today = pick(all[all.length - 1])
  const yesterday = pick(all[all.length - 2])
  return yesterday === 0 ? null : ((today - yesterday) / yesterday) * 100
}

function formatDelta(value: number | null): string {
  if (value === null) return '-'
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)}%`
}

/** 涨跌方向对应的样式类。无数据或零变化不上色 —— 0% 既不是好消息也不是坏消息。 */
function deltaClass(value: number | null): string {
  if (value === null || value === 0) return ''
  return value > 0 ? 'up' : 'down'
}

function formatTrend(value: number | null): string {
  if (value === null) return '-'
  return `${value >= 0 ? '↑' : '↓'} ${Math.abs(value).toFixed(1)}%`
}

// ── Helpers ──
function formatTime(t: string | null) {
  return t ? dayjs(t).format('MM-DD HH:mm') : '-'
}

function batteryClass(b: number) {
  if (b > 50) return 'battery-high'
  if (b > 20) return 'battery-mid'
  return 'battery-low'
}

/*
 * 原先这里还有 statusText / statusDotClass，服务于设备卡上那个重复的彩色圆点。
 * 圆点已删除，设备状态现在只由 StatusBadge 一处表达 —— 它同样认后端的三态
 * （online / offline / DISABLED），所以不再需要这里的第二份映射。
 */

function goToDetail(device: Device) {
  router.push(`/devices/${encodeURIComponent(device.deviceId)}`)
}

async function loadAll() {
  // 三个请求互不依赖，用 allSettled：任一失败不该把另外两个的数据一起丢掉 ——
  // 用 Promise.all 时一个接口出错，整页 KPI 全显示 0，看着像真的没有设备。
  // 失败提示由 http 拦截器统一给出。
  try {
    const [statsRes, devicesRes, dailyRes] = await Promise.allSettled([
      getDeviceStats(),
      getDeviceList({ page: 1, pageSize: 8 }),
      getDailyStats(14),
    ])
    if (statsRes.status === 'fulfilled') stats.value = statsRes.value
    if (devicesRes.status === 'fulfilled') recentDevices.value = devicesRes.value.records
    if (dailyRes.status === 'fulfilled') dailyStats.value = dailyRes.value
  } finally {
    // allSettled 本身不会 reject，这里兜的是「构造请求数组时就抛」（比如 api 模块被改坏）。
    // 遮罩摘不掉的话，「正在加载」和「加载失败」在界面上长得一模一样 —— 都是转圈。
    loading.value = false
  }
}

onMounted(loadAll)

/**
 * 有设备上下线、或来了新短信时刷新 KPI —— 不轮询。
 * 仪表盘两样都显示（在线数 / 今日短信量），所以两个事件都认。
 */
useAdminEvents((event) => {
  if (event === 'devices' || event === 'sms' || event === 'hello') loadAll()
})
</script>

<style scoped>
.dashboard {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--section-gap);
}

/*
 * el-row 的 :gutter 只给列加左右内边距，换行之后行与行之间本来没有间距 ——
 * 原先靠 .kpi-card 的 margin-bottom 兼职补上，可单行时那份 margin 也白留着，
 * 再和 .middle-row 的 margin-bottom 一叠，图表行到「最近活跃设备」就成了 44px。
 * 改用容器自己的 row-gap：单行时不多出空白，多行时也不必再依赖子元素的 margin。
 */
.dashboard .el-row {
  row-gap: var(--section-gap);
}

/* ════════════════ KPI 卡片 ════════════════ */
/*
 * 白底卡 + 语义色图标块，和全站其它卡片同构。
 *
 * 改前是四张全饱和渐变（蓝 / 橙红 / 绿 / 紫→品红），彼此之间没有色彩逻辑，
 * 紫色那张更和整个后台的蓝绿色调无关；四张卡一起抢注意力，是这一页显得乱的主因。
 * 现在彩色只出现在两处：图标块，以及唯一一个带语义的数字（离线设备数）。
 *
 * 四张卡结构完全一致（图标+标题 / 数字 / 一行 meta），高度天然相等。
 * 改前不是：只有前两张有底部进度条、后两张没有，看着像做漏了 ——
 * 而那两条进度条显示的百分比，与紧挨着的「在线率 80%」本来就是同一份信息，
 * 属于重复表达，所以直接删掉，底部不齐的问题也就不存在了。
 */
.kpi-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;              /* 撑满 el-col，四张卡等高 */
  padding: var(--card-padding);
  background: var(--color-white);
  border: 1px solid var(--color-border-light);
  border-radius: var(--border-radius-base);
  cursor: pointer;
  transition: box-shadow 0.25s ease, transform 0.25s ease;
}
.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}

.kpi-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.kpi-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--border-radius-small);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.kpi-icon--success { background: var(--color-success-bg); color: var(--color-success); }
.kpi-icon--danger { background: var(--color-danger-bg); color: var(--color-danger); }
.kpi-icon--primary { background: var(--color-primary-bg); color: var(--color-primary); }

.kpi-label {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.kpi-value {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.5px;
  color: var(--color-text-primary);
  /* 等宽数字：四张卡并排，某张数字多一位时不会带着整排左右跳 */
  font-variant-numeric: tabular-nums;
}
/* 唯一上语义色的数字。为 0 时保持中性 —— 没有离线设备是好消息，不该标红 */
.kpi-value.is-danger {
  color: var(--color-danger);
}

.kpi-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-text-secondary);
  min-height: 18px;   /* 加载中不出文字时也占位，卡片高度不跳 */
}
.kpi-delta { font-weight: 600; }
.kpi-delta.up { color: var(--color-success); }
.kpi-delta.down { color: var(--color-danger); }

/* ════════════════ 图表卡片 ════════════════ */
/*
 * 两张图表卡必须等高：8:16 分栏下，环形图那张的内容天然比柱状图短一截，
 * 改前两卡底部是错开的。做法是让 el-col 变成 flex 容器、卡片 flex: 1 撑满，
 * 卡片内部再纵向分栏，由 .chart-body 吃掉剩余高度。
 *
 * 静止态一律「1px 浅边框、无阴影」，与五个列表页的 el-card shadow="never" 拉齐；
 * 阴影只留给悬停。行间距由 .dashboard 的 gap / row-gap 统一给，卡片自己不再带 margin。
 */
.middle-row > .el-col {
  display: flex;
}

.chart-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--color-white);
  border-radius: var(--border-radius-base);
  /* 跟 el-card 的 --card-padding 对齐（本文件是普通 div，吃不到那个令牌） */
  padding: 24px;
  border: 1px solid var(--color-border-light);
  transition: box-shadow 0.3s ease;
}
.chart-card:hover {
  box-shadow: var(--shadow-hover);
}

.chart-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.chart-card-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-primary);
  margin: 0;
}

/* ── 环形图 ── */
.chart-body {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 32px;
}

/*
 * 环形图这半边改成纵向：卡片只占 8 列，环 + 图例并排会挤；
 * 而且卡片高度被柱状图那张撑高之后，纵向排布才填得满、不会中间空一块。
 */
.donut-body {
  flex-direction: column;
  justify-content: center;
  gap: 20px;
}
.donut-wrap {
  position: relative;
  width: 140px;
  height: 140px;
  flex-shrink: 0;
}
.donut-svg {
  width: 100%;
  height: 100%;
  transform: rotate(0deg);
}
.donut-segment {
  transition: stroke-dasharray 0.8s ease, stroke-dashoffset 0.8s ease;
}
.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.donut-total {
  font-size: 28px;
  font-weight: 800;
  color: var(--color-text-primary);
  line-height: 1;
}
.donut-label {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-top: 2px;
}
/* 图例横排在环下方，一行两个（在线 / 离线） */
.donut-legend {
  display: flex;
  gap: 32px;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot-blue { background: var(--color-primary); }
.dot-red { background: var(--color-danger); }
.legend-text {
  color: var(--color-text-regular);
}
.legend-value {
  font-weight: 600;
  color: var(--color-text-primary);
}

/* ── 柱状图 ── */
.bar-chart-body {
  flex-direction: column;
  gap: 20px;
}

/*
 * 每根柱子的阅读顺序从上到下是「数值 → 柱高 → 日期」。
 * 改前用 order 把数值夹在柱子和日期中间（柱 / 数值 / 日期），
 * 数值和它所属的柱子被日期隔开，一眼看不出谁对应谁。
 */
.bar-chart {
  flex: 1;
  min-height: 160px;
  display: flex;
  align-items: flex-end;
  gap: 16px;
  padding: 0 8px;
}
.bar-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  height: 100%;
}
.bar-value {
  order: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-text-primary);
}
.bar-track {
  order: 1;
  flex: 1;
  width: 100%;
  max-width: 40px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}
.bar-label {
  order: 2;
  font-size: 11px;
  color: var(--color-text-secondary);
}
.bar-fill {
  width: 100%;
  /* 底角收得更小，柱子落在地平线上才不显飘 */
  border-radius: var(--border-radius-small) var(--border-radius-small) 2px 2px;
  background: var(--bar-color, var(--color-primary));
  min-height: 8px;
  transition: height 0.6s cubic-bezier(0.4, 0, 0.2, 1);
}

/* 柱状图底部汇总 */
.bar-summary {
  display: flex;
  gap: 40px;
  padding: 16px 0 0;
  border-top: 1px solid var(--color-border-light);
}
.summary-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.summary-num {
  font-size: 18px;
  font-weight: 700;
  color: var(--color-text-primary);
}
.summary-num.up {
  color: var(--color-success);
}
.summary-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

/* ════════════════ 最近设备 ════════════════ */
.recent-section {
  background: var(--color-white);
  border-radius: var(--border-radius-base);
  /* 同 .chart-card，与全站卡片内边距对齐 */
  padding: 24px;
  border: 1px solid var(--color-border-light);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.section-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.section-header-left h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-primary);
  margin: 0;
}

/*
 * 固定列数，不用 auto-fill：改前是 repeat(auto-fill, minmax(240px, 1fr))，
 * 1920 宽下会自动排到 6 列，8 台设备变成 6 + 2，最后一行右边空一大片。
 * 首页固定取 8 台、最多 4 列 → 任何宽度都是排满的整行（断点见文件末尾）。
 * 用 minmax(0, 1fr) 而不是 1fr：UUID 这种长内容会把 1fr 的列撑破。
 */
.device-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.device-mini-card {
  background: var(--color-bg);
  border-radius: var(--border-radius-base);
  padding: 16px;
  cursor: pointer;
  transition: all 0.25s ease;
  border: 1px solid transparent;
}
.device-mini-card:hover {
  background: var(--color-white);
  border-color: var(--color-border);
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}

/*
 * 设备 ID 与状态各占一端。ID 是 36 字符的 UUID，占不下就截断，
 * 完整的值通过 el-tooltip 悬停查看（见模板）。
 * 这里原先还有一个同义的彩色圆点，且两者对「已禁用」取色相反（StatusBadge 红、圆点灰），
 * 同一张卡上自相矛盾 —— 已删除，状态只由 StatusBadge 表达一次。
 */
.dmc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.dmc-id {
  min-width: 0;             /* 少了这个，flex 子项不肯收缩，省略号永远不出现 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.dmc-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}
.dmc-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--color-text-regular);
}
.dmc-row .el-icon {
  color: var(--color-text-secondary);
}

.dmc-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 10px;
  border-top: 1px solid var(--color-border-light);
}
.dmc-tag {
  font-size: 12px;
  padding: 2px 8px;
  /* 原先的 #1890ff / #e6f7ff 是 Ant Design 蓝，不在本项目的调色板里 */
  background: var(--color-primary-bg);
  color: var(--color-primary);
  border-radius: var(--border-radius-small);
  font-weight: 500;
}
.dmc-battery {
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 3px;
}
.battery-high { color: var(--color-success); }
.battery-mid { color: var(--color-warning); }
.battery-low { color: var(--color-danger); }

/* ═══ 响应式微调 ═══ */

/* 设备网格按断点降列，保证每一行都是排满的（8 台 → 4/3/2/1 列） */
@media (max-width: 1400px) {
  .device-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 1100px) {
  .device-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 768px) {
  .kpi-value { font-size: 28px; }
  .donut-wrap { width: 120px; height: 120px; }
  .bar-chart { min-height: 120px; gap: 8px; }
  .bar-summary { gap: 24px; }
  .device-grid { grid-template-columns: minmax(0, 1fr); }
}

@media (min-width: 1600px) {
  .bar-chart { min-height: 200px; }
}
</style>
