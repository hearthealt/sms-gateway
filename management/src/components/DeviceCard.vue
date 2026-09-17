<template>
  <el-card class="device-card" :body-style="{ padding: '16px' }" shadow="hover">
    <div class="card-header">
      <div class="device-id">
        <status-badge :status="device.status" />
        <span class="device-id-text">{{ device.deviceId }}</span>
      </div>
    </div>
    <div class="card-body">
      <div class="info-row">
        <el-icon class="info-icon"><Iphone /></el-icon>
        <span>{{ device.phone || '-' }}</span>
      </div>
      <div class="info-row">
        <el-icon class="info-icon"><Timer /></el-icon>
        <span>{{ heartbeatAgo }}</span>
      </div>
      <div class="info-row" v-if="device.battery !== undefined">
        <el-icon class="info-icon"><Charging /></el-icon>
        <span :class="batteryClass">{{ device.battery }}%</span>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'
import StatusBadge from './StatusBadge.vue'
import type { Device } from '../types'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

const props = defineProps<{
  device: Device
}>()

// 注意：变量名不能叫 relativeTime，否则会遮蔽上面 dayjs 的插件导入
const heartbeatAgo = computed(() =>
  props.device.lastHeartbeat ? dayjs(props.device.lastHeartbeat).fromNow() : '-',
)

const batteryClass = computed(() => {
  const b = props.device.battery
  if (b == null) return ''
  if (b > 50) return 'battery-high'
  if (b > 20) return 'battery-mid'
  return 'battery-low'
})
</script>

<style scoped>
.device-card {
  cursor: pointer;
  transition: var(--transition-base);
  border: 1px solid var(--color-border-light);
  border-radius: var(--border-radius-base);
}
.device-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-hover);
}
.card-header {
  margin-bottom: 12px;
}
.device-id {
  display: flex;
  align-items: center;
  gap: 8px;
}
.device-id-text {
  font-weight: 600;
  font-size: 14px;
  color: var(--color-text-primary);
}
.card-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.info-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--color-text-regular);
}
.info-icon {
  font-size: 15px;
  color: var(--color-text-secondary);
}

.battery-high { color: var(--color-success); font-weight: 500; }
.battery-mid { color: var(--color-warning); font-weight: 500; }
.battery-low { color: var(--color-danger); font-weight: 500; }
</style>
