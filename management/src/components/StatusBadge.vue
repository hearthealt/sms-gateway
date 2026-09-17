<template>
  <span class="status-badge" :class="colorClass">
    <span class="status-dot" :class="{ pulse: isOnline }" :style="{ backgroundColor: dotColor }"></span>
    <span class="status-text">{{ displayText }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
}>()

const statusMap: Record<string, { color: string; text: string }> = {
  online: { color: '#67C23A', text: '在线' },
  offline: { color: '#F56C6C', text: '离线' },
  ACTIVE: { color: '#67C23A', text: '活跃' },
  INACTIVE: { color: '#E6A23C', text: '未激活' },
  DISABLED: { color: '#F56C6C', text: '已禁用' },
  enabled: { color: '#67C23A', text: '已启用' },
  disabled: { color: '#F56C6C', text: '已禁用' },
  RECEIVED: { color: '#409EFF', text: '已接收' },
  DUPLICATE: { color: '#E6A23C', text: '重复' },
  PROCESSED: { color: '#67C23A', text: '已处理' },
  collect: { color: '#409EFF', text: '采集' },
  ignore: { color: '#909399', text: '忽略' },
}

const info = computed(() => statusMap[props.status] || { color: '#909399', text: props.status })
const dotColor = computed(() => info.value.color)
const displayText = computed(() => info.value.text)
const colorClass = computed(() => `badge-${props.status.toLowerCase()}`)
const isOnline = computed(() => ['online', 'ACTIVE', 'enabled', 'RECEIVED', 'PROCESSED'].includes(props.status))
</script>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  transition: background-color 0.3s ease;
}

.status-dot.pulse {
  animation: statusPulse 2s ease-in-out infinite;
}

@keyframes statusPulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(103, 194, 58, 0.4); }
  50% { box-shadow: 0 0 0 6px rgba(103, 194, 58, 0); }
}

.status-text {
  color: var(--color-text-regular);
}
</style>
