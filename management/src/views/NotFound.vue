<template>
  <div class="not-found">
    <el-result icon="warning" title="404" sub-title="页面不存在或已被移除，请检查地址是否输错">
      <template #extra>
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  // 交给路由守卫决定落点：已登录回仪表盘，未登录会被拦到登录页
  router.push('/')
}
</script>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--color-bg);
}
</style>
