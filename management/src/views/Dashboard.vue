<template>
  <div class="dashboard">
    <!-- ═══ KPI 指标卡片 ═══ -->
    <el-row :gutter="20">
      <el-col :xs="24" :sm="12" :lg="6">
        <div class="kpi-card kpi-blue" @click="$router.push('/devices')">
          <div class="kpi-bg-shape"></div>
          <div class="kpi-inner">
            <div class="kpi-top">
              <div class="kpi-icon-wrap">
                <el-icon :size="24"><Monitor /></el-icon>
              </div>
              <span class="kpi-badge">实时</span>
            </div>
            <div class="kpi-value">{{ stats.onlineDevices }}</div>
            <div class="kpi-label">在线设备</div>
            <div class="kpi-footer">
              <span>在线率 {{ stats.totalDevices > 0 ? Math.round(stats.onlineDevices / stats.totalDevices * 100) : 0 }}%</span>
            </div>
          </div>
          <div class="kpi-progress">
            <div class="kpi-progress-bar" :style="{ width: stats.totalDevices > 0 ? (stats.onlineDevices / stats.totalDevices * 100) + '%' : '0%' }"></div>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <div class="kpi-card kpi-orange" @click="$router.push('/devices')">
          <div class="kpi-bg-shape"></div>
          <div class="kpi-inner">
            <div class="kpi-top">
              <div class="kpi-icon-wrap">
                <el-icon :size="24"><WarningFilled /></el-icon>
              </div>
              <span class="kpi-badge">需关注</span>
            </div>
            <div class="kpi-value">{{ stats.offlineDevices }}</div>
            <div class="kpi-label">离线设备</div>
            <div class="kpi-footer">
              <span>离线率 {{ stats.totalDevices > 0 ? Math.round(stats.offlineDevices / stats.totalDevices * 100) : 0 }}%</span>
            </div>
          </div>
          <div class="kpi-progress">
            <div class="kpi-progress-bar" :style="{ width: stats.totalDevices > 0 ? (stats.offlineDevices / stats.totalDevices * 100) + '%' : '0%' }"></div>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <div class="kpi-card kpi-green" @click="$router.push('/sms')">
          <div class="kpi-bg-shape"></div>
          <div class="kpi-inner">
            <div class="kpi-top">
              <div class="kpi-icon-wrap">
                <el-icon :size="24"><ChatDotSquare /></el-icon>
              </div>
              <span class="kpi-badge">今日</span>
            </div>
            <div class="kpi-value">{{ stats.todaySms }}</div>
            <div class="kpi-label">今日短信</div>
            <div class="kpi-footer">
              <span>较昨日 {{ formatDelta(smsDayOverDay) }}</span>
            </div>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <div class="kpi-card kpi-purple" @click="$router.push('/sms')">
          <div class="kpi-bg-shape"></div>
          <div class="kpi-inner">
            <div class="kpi-top">
              <div class="kpi-icon-wrap">
                <el-icon :size="24"><Key /></el-icon>
              </div>
              <span class="kpi-badge">今日</span>
            </div>
            <div class="kpi-value">{{ stats.todayCodes }}</div>
            <div class="kpi-label">今日验证码</div>
            <div class="kpi-footer">
              <span>较昨日 {{ formatDelta(codeDayOverDay) }}</span>
            </div>
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
          <div class="chart-body">
            <div class="donut-wrap">
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
                <span class="donut-total">{{ stats.totalDevices }}</span>
                <span class="donut-label">总计设备</span>
              </div>
            </div>
            <div class="donut-legend">
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
            <div class="bar-chart">
              <div class="bar-item" v-for="(item, idx) in weeklyData" :key="idx">
                <div class="bar-label">{{ item.day }}</div>
                <div class="bar-track">
                  <div
                    class="bar-fill"
                    :style="{ height: item.percent + '%', '--bar-color': item.color }"
                  >
                    <span class="bar-tooltip">{{ item.value }}</span>
                  </div>
                </div>
                <div class="bar-value">{{ item.value }}</div>
              </div>
            </div>
            <div class="bar-summary">
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
      <div class="device-grid">
        <div
          v-for="device in recentDevices"
          :key="device.id"
          class="device-mini-card"
          @click="goToDetail(device)"
        >
          <div class="dmc-top">
            <status-badge :status="device.status" />
            <span class="dmc-id">{{ device.deviceId }}</span>
            <el-tooltip :content="device.status === 'online' ? '在线' : '离线'" placement="top">
              <span class="dmc-dot" :class="device.status === 'online' ? 'dot-online' : 'dot-offline'"></span>
            </el-tooltip>
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
    color: '#409eff',
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

function goToDetail(device: Device) {
  router.push(`/devices/${device.deviceId}`)
}

onMounted(async () => {
  try {
    const [statsData, deviceData, dailyData] = await Promise.all([
      getDeviceStats(),
      getDeviceList({ page: 1, pageSize: 8 }),
      getDailyStats(14),
    ])
    stats.value = statsData
    recentDevices.value = deviceData.records
    dailyStats.value = dailyData
  } catch (e) {
    console.error('Failed to load dashboard data', e)
  }
})
</script>

<style scoped>
.dashboard {
  width: 100%;
}

/* ════════════════ KPI 卡片 ════════════════ */
.kpi-card {
  position: relative;
  border-radius: 14px;
  padding: 20px 24px 0;
  margin-bottom: 20px;
  cursor: pointer;
  overflow: hidden;
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
  min-height: 148px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.kpi-card:hover {
  transform: translateY(-6px) scale(1.01);
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.15);
}

/* 每种颜色变体 */
.kpi-blue {
  background: linear-gradient(135deg, #2193b0 0%, #6dd5ed 100%);
  color: #fff;
}
.kpi-orange {
  background: linear-gradient(135deg, #f12711 0%, #f5af19 100%);
  color: #fff;
}
.kpi-green {
  background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%);
  color: #fff;
}
.kpi-purple {
  background: linear-gradient(135deg, #7f00ff 0%, #e100ff 100%);
  color: #fff;
}

.kpi-bg-shape {
  position: absolute;
  top: -40px;
  right: -40px;
  width: 160px;
  height: 160px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 50%;
  pointer-events: none;
}
.kpi-bg-shape::after {
  content: '';
  position: absolute;
  bottom: -20px;
  left: -20px;
  width: 100px;
  height: 100px;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 50%;
}

.kpi-inner {
  position: relative;
  z-index: 1;
}

.kpi-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.kpi-icon-wrap {
  width: 40px;
  height: 40px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(4px);
}
.kpi-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 10px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 20px;
  backdrop-filter: blur(4px);
  letter-spacing: 0.3px;
}

.kpi-value {
  font-size: 36px;
  font-weight: 800;
  line-height: 1;
  margin-bottom: 4px;
  letter-spacing: -1px;
}

.kpi-label {
  font-size: 14px;
  opacity: 0.85;
  font-weight: 500;
}

.kpi-footer {
  margin-top: 12px;
  font-size: 12px;
  opacity: 0.7;
  padding-bottom: 16px;
}

/* 底部进度条（仅部分卡片） */
.kpi-progress {
  height: 3px;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 0 0 14px 14px;
  overflow: hidden;
  margin: 0 -24px;
}
.kpi-progress-bar {
  height: 100%;
  background: rgba(255, 255, 255, 0.5);
  border-radius: 0 0 14px 0;
  transition: width 0.8s cubic-bezier(0.4, 0, 0.2, 1);
}

/* ════════════════ 图表卡片 ════════════════ */
.middle-row {
  margin-bottom: 24px;
}

.chart-card {
  background: #fff;
  border-radius: 14px;
  padding: 20px 24px;
  margin-bottom: 20px;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.04);
  border: 1px solid #ebeef5;
  transition: box-shadow 0.3s ease;
}
.chart-card:hover {
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
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
  color: #303133;
  margin: 0;
}

/* ── 环形图 ── */
.chart-body {
  display: flex;
  align-items: center;
  gap: 32px;
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
  color: #303133;
  line-height: 1;
}
.donut-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.donut-legend {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot-blue { background: #409eff; }
.dot-red { background: #f56c6c; }
.legend-text {
  color: #606266;
  flex: 1;
}
.legend-value {
  font-weight: 600;
  color: #303133;
}

/* ── 柱状图 ── */
.bar-chart-body {
  flex-direction: column;
  gap: 20px;
}

.bar-chart {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  height: 160px;
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
.bar-label {
  font-size: 11px;
  color: #909399;
  order: 2;
  margin-top: auto;
}
.bar-track {
  flex: 1;
  width: 100%;
  max-width: 40px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  order: 0;
}
.bar-fill {
  width: 100%;
  border-radius: 6px 6px 2px 2px;
  background: var(--bar-color, #409eff);
  min-height: 8px;
  transition: height 0.6s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  cursor: pointer;
}
.bar-fill:hover {
  opacity: 0.85;
}
.bar-tooltip {
  position: absolute;
  top: -22px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 10px;
  color: #606266;
  font-weight: 600;
  white-space: nowrap;
  opacity: 0;
  transition: opacity 0.2s ease;
}
.bar-fill:hover .bar-tooltip {
  opacity: 1;
}
.bar-value {
  font-size: 11px;
  font-weight: 600;
  color: #303133;
  order: 1;
}

/* 柱状图底部汇总 */
.bar-summary {
  display: flex;
  gap: 40px;
  padding: 16px 0 0;
  border-top: 1px solid #ebeef5;
}
.summary-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.summary-num {
  font-size: 18px;
  font-weight: 700;
  color: #303133;
}
.summary-num.up {
  color: #67c23a;
}
.summary-label {
  font-size: 12px;
  color: #909399;
}

/* ════════════════ 最近设备 ════════════════ */
.recent-section {
  background: #fff;
  border-radius: 14px;
  padding: 20px 24px;
  border: 1px solid #ebeef5;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.04);
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
  color: #303133;
  margin: 0;
}

.device-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.device-mini-card {
  background: #f8f9fb;
  border-radius: 12px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.25s ease;
  border: 1px solid transparent;
}
.device-mini-card:hover {
  background: #fff;
  border-color: #e4e7ed;
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}

.dmc-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.dmc-id {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
  flex: 1;
}
.dmc-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot-online {
  background: #67c23a;
  box-shadow: 0 0 0 2px rgba(103, 194, 58, 0.2);
}
.dot-offline {
  background: #f56c6c;
  box-shadow: 0 0 0 2px rgba(245, 108, 108, 0.2);
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
  color: #606266;
}
.dmc-row .el-icon {
  color: #909399;
}

.dmc-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 10px;
  border-top: 1px solid #ebeef5;
}
.dmc-tag {
  font-size: 12px;
  padding: 2px 8px;
  background: #e6f7ff;
  color: #1890ff;
  border-radius: 4px;
  font-weight: 500;
}
.dmc-battery {
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 3px;
}
.battery-high { color: #67c23a; }
.battery-mid { color: #e6a23c; }
.battery-low { color: #f56c6c; }

/* ═══ 响应式微调 ═══ */
@media (max-width: 768px) {
  .kpi-value { font-size: 28px; }
  .chart-body { flex-direction: column; gap: 20px; }
  .donut-wrap { width: 120px; height: 120px; }
  .bar-chart { height: 120px; gap: 8px; }
  .bar-summary { gap: 24px; }
  .device-grid { grid-template-columns: 1fr; }
}

@media (min-width: 1600px) {
  .bar-chart { height: 200px; }
  .kpi-card { min-height: 160px; }
  .kpi-value { font-size: 40px; }
}
</style>
