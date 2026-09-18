<template>
  <span class="status-badge">
    <span class="status-dot" :class="{ pulse: isOnline }" :style="{ '--dot-color': dotColor }"></span>
    <span class="status-text">{{ displayText }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
}>()

/*
 * 颜色一律指向 App.vue 的令牌，不再在 JS 里复制一份十六进制 ——
 * var() 在内联样式里是会解析的（浏览器按 :root 求值）。
 * 代价是写错令牌名时 var() 静默失败、圆点变透明，改这张表请到页面上看一眼。
 */
const statusMap: Record<string, { color: string; text: string }> = {
  online: { color: 'var(--color-success)', text: '在线' },
  offline: { color: 'var(--color-danger)', text: '离线' },
  ACTIVE: { color: 'var(--color-success)', text: '活跃' },
  INACTIVE: { color: 'var(--color-warning)', text: '未激活' },
  DISABLED: { color: 'var(--color-danger)', text: '已禁用' },
  enabled: { color: 'var(--color-success)', text: '已启用' },
  disabled: { color: 'var(--color-danger)', text: '已禁用' },
  RECEIVED: { color: 'var(--color-primary)', text: '已接收' },
  DUPLICATE: { color: 'var(--color-warning)', text: '重复' },
  PROCESSED: { color: 'var(--color-success)', text: '已处理' },
  collect: { color: 'var(--color-primary)', text: '采集' },
  ignore: { color: 'var(--color-info)', text: '忽略' },
}

const info = computed(() => statusMap[props.status] || { color: 'var(--color-info)', text: props.status })
const dotColor = computed(() => info.value.color)
const displayText = computed(() => info.value.text)
// 「还在动」的状态才呼吸：绿色的那几种，加上蓝色的已接收
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
  position: relative;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background-color: var(--dot-color, var(--color-info));
  transition: background-color 0.3s ease;
}

/*
 * 呼吸光环用圆点自己的颜色（--dot-color），不写死绿色 ——
 * 否则 RECEIVED 这类蓝色状态会闪一圈绿光。
 * 用伪元素渐隐来做「扩散后消失」，省得为了凑半透明再把 --dot-color 拆成 rgba。
 */
.status-dot.pulse::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  box-shadow: 0 0 0 0 var(--dot-color, var(--color-info));
  animation: statusPulse 2s ease-in-out infinite;
}

@keyframes statusPulse {
  0% { box-shadow: 0 0 0 0 var(--dot-color, var(--color-info)); opacity: 1; }
  100% { box-shadow: 0 0 0 6px var(--dot-color, var(--color-info)); opacity: 0; }
}

.status-text {
  color: var(--color-text-regular);
}
</style>
