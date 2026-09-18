<template>
  <el-container class="app-wrapper">
    <!-- Sidebar -->
    <el-aside class="app-sidebar" :class="{ collapsed: isCollapsed }">
      <!-- Logo -->
      <div class="sidebar-logo" @click="$router.push('/dashboard')">
        <div class="logo-icon">
          <svg viewBox="0 0 32 32" width="28" height="28" fill="none">
            <rect width="32" height="32" rx="8" fill="url(#logoGradient)" />
            <path d="M8 16L14 22L24 10" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
            <defs><linearGradient id="logoGradient" x1="0" y1="0" x2="32" y2="32"><stop stop-color="#409eff"/><stop offset="1" stop-color="#36d399"/></linearGradient></defs>
          </svg>
        </div>
        <transition name="fade">
          <span v-show="!isCollapsed" class="logo-text">SMS Gateway</span>
        </transition>
      </div>

      <!-- Navigation -->
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapsed"
        :background-color="sidebarBg"
        :text-color="sidebarText"
        :active-text-color="sidebarActive"
        :router="false"
        @select="handleMenuSelect"
        class="sidebar-menu"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon class="menu-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>

      <!-- Collapse toggle -->
      <div class="sidebar-collapse" @click="isCollapsed = !isCollapsed">
        <el-icon :size="18"><component :is="isCollapsed ? 'Expand' : 'Fold'" /></el-icon>
      </div>
    </el-aside>

    <!-- Right area -->
    <el-container class="main-wrapper">
      <!-- Header -->
      <el-header class="app-header">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="currentTitle">{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="user-profile">
              <el-avatar :size="28" color="#409eff">{{ username.charAt(0).toUpperCase() }}</el-avatar>
              <span class="username">{{ username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- Main content -->
      <el-main class="app-main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script lang="ts">
import { defineComponent, computed, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Odometer, Monitor, ChatDotSquare, Setting, Document, Key, Expand, Fold, ArrowDown } from '@element-plus/icons-vue'
import { logout } from '../api/auth'
import { clearSession, getUsername } from '../utils/auth'

export default defineComponent({
  name: 'AppLayout',
  components: { Odometer, Monitor, ChatDotSquare, Setting, Document, Key, Expand, Fold, ArrowDown },
  setup() {
    const router = useRouter()
    const route = useRoute()
    const isCollapsed = ref(false)

    const username = computed(() => getUsername() || 'admin')
    const sidebarBg = '#1d2b3a'
    const sidebarText = '#bfcbd9'
    const sidebarActive = '#409eff'

    // 面包屑第二段直接读路由的 meta.title（定义见 router/index.ts）。
    // 原先这里另有一张按 route.name 手写的映射表，加路由时忘了补就静默少一段面包屑 ——
    // 「接口文档」页就是这么只剩「首页」的。标题跟着路由定义走，就没有第二处要同步。
    const currentTitle = computed(() => (route.meta.title as string) || '')

    const menuItems = [
      { path: '/dashboard', icon: 'Odometer', label: '仪表盘' },
      { path: '/devices', icon: 'Monitor', label: '设备管理' },
      { path: '/sms', icon: 'ChatDotSquare', label: '短信记录' },
      { path: '/rules', icon: 'Setting', label: '规则管理' },
      { path: '/apikeys', icon: 'Key', label: 'API 密钥' },
      { path: '/api-docs', icon: 'Document', label: '接口文档' },
    ]

    const activeMenu = computed(() => {
      const path = route.path
      if (path.startsWith('/devices')) return '/devices'
      return path
    })

    function handleMenuSelect(index: string) {
      router.push(index)
    }

    function handleUserCommand(command: string) {
      if (command === 'logout') {
        // 通知后端作废该 token；失败也照常清理本地状态并跳转，
        // 否则用户会卡在退不出去的界面上。
        logout().catch(() => {}).finally(() => {
          clearSession()
          router.push('/login')
        })
      }
    }

    return {
      isCollapsed, username,
      sidebarBg, sidebarText, sidebarActive,
      menuItems, activeMenu, currentTitle,
      handleMenuSelect, handleUserCommand,
    }
  },
})
</script>

<style scoped>
.app-wrapper {
  height: 100vh;
  overflow: hidden;
}

/* ── Sidebar ── */
.app-sidebar {
  width: var(--sidebar-width);
  background: var(--sidebar-bg);
  transition: width var(--transition-base);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  position: relative;
  z-index: 100;
  box-shadow: 2px 0 8px rgba(0,0,0,0.06);
}
.app-sidebar.collapsed {
  width: var(--sidebar-width-collapsed);
}

.sidebar-logo {
  height: var(--header-height);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
  cursor: pointer;
  flex-shrink: 0;
}
.logo-icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.logo-text {
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.sidebar-menu {
  flex: 1;
  border-right: none !important;
  overflow-y: auto;
  overflow-x: hidden;
  padding-top: 4px;
}
.sidebar-menu .el-menu-item {
  margin: 2px 8px;
  border-radius: 6px;
  transition: var(--transition-fast);
}
.sidebar-menu .el-menu-item:hover {
  background: rgba(255,255,255,0.08) !important;
}
.sidebar-menu .el-menu-item.is-active {
  background: rgba(64,158,255,0.2) !important;
}
.menu-icon {
  margin-right: 8px;
  font-size: 18px;
}

.sidebar-collapse {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--sidebar-text);
  cursor: pointer;
  border-top: 1px solid rgba(255,255,255,0.06);
  transition: var(--transition-fast);
  flex-shrink: 0;
}
.sidebar-collapse:hover {
  color: #fff;
  background: rgba(255,255,255,0.05);
}

/* ── Main ── */
.main-wrapper {
  flex-direction: column;
  overflow: hidden;
}

.app-header {
  height: var(--header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: var(--color-white);
  border-bottom: 1px solid var(--color-border-light);
  box-shadow: var(--shadow-base);
  z-index: 10;
  flex-shrink: 0;
}
.header-left {
  display: flex;
  align-items: center;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.user-profile {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--border-radius-small);
  transition: var(--transition-fast);
}
.user-profile:hover {
  background: var(--color-bg);
}
.username {
  font-size: 14px;
  color: var(--color-text-regular);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-main {
  flex: 1;
  background: var(--color-bg);
  overflow-y: auto;
  overflow-x: hidden;
  padding: 20px 28px;
}

/* Transition */
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.fade-enter-from { opacity: 0; transform: translateY(8px); }
.fade-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
