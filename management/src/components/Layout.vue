<template>
  <el-container class="app-wrapper">
    <!-- 抽屉遮罩：只在小屏出现。position: fixed 不进 flex 流，不影响两栏布局 -->
    <div v-if="isMobile && drawerOpen" class="sidebar-scrim" @click="drawerOpen = false" />

    <!-- Sidebar -->
    <el-aside class="app-sidebar" :class="{ collapsed: collapsed, drawer: isMobile, open: drawerOpen }">
      <!-- Logo -->
      <div class="sidebar-logo" @click="$router.push('/dashboard')">
        <div class="logo-icon">
          <svg viewBox="0 0 32 32" width="28" height="28" fill="none">
            <rect width="32" height="32" rx="8" fill="url(#logoGradient)" />
            <path d="M8 16L14 22L24 10" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
            <defs><linearGradient id="logoGradient" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="32" y2="32"><stop stop-color="#409eff"/><stop offset="1" stop-color="#36d399"/></linearGradient></defs>
          </svg>
        </div>
        <!--
          这里不做 v-show + 淡出：那样文字会在折叠的第一帧整块消失，
          而侧边栏宽度还在滑 —— 两者不同步正是「僵硬」的来源之一。
          改成保留自然宽度，交给 .sidebar-logo 的 overflow + flex 收缩去裁切，
          文字是被宽度动画本身推出去的，全程同一次布局。
          用 collapsed 而不是 isCollapsed：抽屉形态下侧栏是满宽的，文字必须显示。
        -->
        <span class="logo-text">SMS Gateway</span>
      </div>

      <!-- Navigation -->
      <!--
        配色不再走 :background-color / :text-color / :active-text-color 这三个 prop，
        改为在 .sidebar-menu 上用 EP 的 --el-menu-* 变量（见样式块）。
        prop 传的是 JS 常量，和 :root 里的 --sidebar-* 令牌各存一份真值；
        CSS 变量这条路让 --sidebar-active 真正被用起来，也不再需要 JS 侧的副本。
      -->
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
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
        <!--
          一个图标做 180° 翻转，而不是在 Expand / Fold 之间换组件 ——
          换组件是瞬时替换、没有中间帧，翻转才有补间。
          Fold 是「«」，转 180° 就是 Expand 的「»」。
        -->
        <el-icon :size="18" class="collapse-icon" :class="{ flipped: collapsed }"><Fold /></el-icon>
      </div>
    </el-aside>

    <!-- Right area -->
    <el-container class="main-wrapper">
      <!-- Header -->
      <el-header class="app-header">
        <div class="header-left">
          <!-- 小屏的侧边栏入口；桌面端由侧边栏本身承担，不需要这个按钮 -->
          <el-icon v-if="isMobile" class="menu-toggle" @click="drawerOpen = true"><Menu /></el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="currentTitle">{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <!--
            快速连接放在全站 header，而不是设备列表页的筛选栏里。

            它跟「现在在哪一页」无关：新手机到场时，你可能正在仪表盘上看数据、
            在短信页查记录 —— 而这是个随时可能要用、且要立刻用上的动作
            （人站在你旁边等着接进来），不该先导航到设备页才点得到。
          -->
          <el-button class="header-action" type="primary" plain @click="openQuickConnect">
            <el-icon><Connection /></el-icon>
            <span class="header-action-text">快速连接</span>
          </el-button>

          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="user-profile">
              <el-avatar :size="28" color="var(--color-primary)">{{ username.charAt(0).toUpperCase() }}</el-avatar>
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

      <!--
        全站唯一的滚动容器。页面级要挂 @scroll 监听或 position: sticky 的，
        都得认这个 scroller（ApiDocs.vue 的滚动高亮就是这么找它的 —— 见那里
        closest('[data-scroll-root]')）。给它加 overflow: hidden 会让这两类行为一起静默失效。
      -->
      <el-main class="app-main" data-scroll-root>
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>

  <!--
    「快速连接」弹窗挂在 Layout 而不是某一页里：按钮在 header 上，全局可达。
    必须绑 ref，否则 quickConnectDialog.value 永远是 undefined，点按钮会静默无反应。
  -->
  <QuickConnectDialog ref="quickConnectDialog" />
</template>

<script lang="ts">
import { defineComponent, computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
// Expand 已不再需要：折叠按钮改成单个 Fold 图标翻转，见模板里的说明
import { Odometer, Monitor, ChatDotSquare, Setting, Document, Key, Fold, ArrowDown, Menu, Connection } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import QuickConnectDialog from './QuickConnectDialog.vue'
import { logout } from '../api/auth'
import { clearSession, getUsername } from '../utils/auth'
import { useIsMobile, useIsNarrow } from '../composables/useMediaQuery'

export default defineComponent({
  name: 'AppLayout',
  components: {
    Odometer, Monitor, ChatDotSquare, Setting, Document, Key, Fold, ArrowDown, Menu,
    Connection, QuickConnectDialog,
  },
  setup() {
    const router = useRouter()
    const route = useRoute()

    const isMobile = useIsMobile()
    const isNarrow = useIsNarrow()
    // 窄屏默认就收起：首帧即按最终布局渲染，避免先展开再收回的跳动
    const isCollapsed = ref(isNarrow.value)

    /*
     * 跨越 1200px 时跟随断点收放侧边栏。这会覆盖用户在同断点内的手动切换 ——
     * 内部管理后台，可接受；换来的是窗口拉窄后不必手动再点一次。
     */
    watch(isNarrow, (narrow) => { isCollapsed.value = narrow })

    /*
     * 抽屉模式下菜单必须展开显示文字（抽屉里放图标条没有意义），
     * 所以这里把「是否折叠」和用户的手动选择分开：手机上恒为 false。
     */
    const collapsed = computed(() => !isMobile.value && isCollapsed.value)

    const drawerOpen = ref(false)

    // 点完菜单就收起抽屉，否则面板会一直盖着刚打开的内容
    watch(() => route.path, () => { drawerOpen.value = false })

    const username = computed(() => getUsername() || 'admin')

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

    const quickConnectDialog = ref<InstanceType<typeof QuickConnectDialog>>()

    function openQuickConnect() {
      // 不用 `?.` 静默吞掉：模板里漏挂 <QuickConnectDialog ref="..."> 时 ref 永远是
      // undefined，表现就是「点按钮没反应」—— 没有报错也没有日志，现场只会反复点。
      // 与设备列表页打开恢复码弹窗那边的写法一致。
      if (!quickConnectDialog.value) {
        ElMessage.error('快速连接弹窗未挂载（Layout 模板里缺少 <QuickConnectDialog ref="quickConnectDialog" />）')
        return
      }
      quickConnectDialog.value.open()
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

    // Esc 关抽屉。挂 window 而不是抽屉元素 —— 抽屉默认没有焦点，元素上的 keydown 收不到。
    function handleKeydown(e: KeyboardEvent) {
      if (e.key === 'Escape') drawerOpen.value = false
    }
    onMounted(() => window.addEventListener('keydown', handleKeydown))
    onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))

    return {
      collapsed, isCollapsed, isMobile, drawerOpen, username,
      menuItems, activeMenu, currentTitle,
      handleMenuSelect, handleUserCommand,
      quickConnectDialog, openQuickConnect,
    }
  },
})
</script>

<style scoped>
.app-wrapper {
  height: 100vh;
  /* 移动端地址栏收放时 100vh 会比可视区高，底部被切掉一截；dvh 跟着可视区走。
     不支持的浏览器忽略这一行，退回上面的 100vh。 */
  height: 100dvh;
  overflow: hidden;
}

/* ── Sidebar ── */
.app-sidebar {
  width: var(--sidebar-width);
  background: var(--sidebar-bg);
  /*
   * 只过渡 width，不用 var(--transition-base)（= all 0.25s ease）：
   * all 会把背景、边框色等无关属性一并卷进过渡，是卡顿的常见来源；
   * ease 前段太急后段太拖，横向位移用标准曲线更贴合。
   * 0.28s 比原来的 0.25s 略长，收尾不显得「弹」。
   */
  transition: width 0.28s var(--ease-standard);
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
  /* 折叠时由这里把 logo 文字裁掉，而不是靠 v-show 整块消失 */
  overflow: hidden;
}
.logo-icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;   /* 图标不参与收缩，否则会被压扁 */
}
.logo-text {
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
  flex-shrink: 1;
  min-width: 0;     /* 少了它，flex 子项不肯收缩到内容宽度以下 */
  overflow: hidden;
}
/* 折叠态：28px 的 logo 落在 64−2×18 的内容框正中，此时文字已被裁成 0 宽 */
.app-sidebar.collapsed .sidebar-logo {
  padding: 0 18px;
}

.sidebar-menu {
  flex: 1;
  border-right: none !important;
  overflow-y: auto;
  overflow-x: hidden;
  padding-top: 4px;
  /*
   * EP 的 el-menu 从这几个变量取配色。.sidebar-menu 就是 el-menu 的根元素，
   * 所以 scoped 属性直接落在它上面，无需 :deep()；而
   * .sidebar-menu[data-v-x] (0,2,0) 也压得过 EP 自己的 .el-menu (0,1,0)。
   */
  --el-menu-bg-color: var(--sidebar-bg);
  --el-menu-text-color: var(--sidebar-text);
  --el-menu-active-color: var(--sidebar-active);
}
.sidebar-menu .el-menu-item {
  margin: 2px 8px;
  border-radius: var(--border-radius-small);
  /*
   * 图标与文字的间隔用 gap，不用 .menu-icon 的 margin-right ——
   * margin 在折叠那一刻会被瞬时撤掉，图标会突然跳 8px；gap 是固定值，不参与切换。
   */
  gap: 8px;
  /*
   * 展开态左右各留 8px，让选中态的圆角背景不贴边。
   * 但这 8px 会让折叠后的图标偏离中线：折叠时菜单宽 64px
   * （EP: --el-menu-icon-width 24 + --el-menu-base-level-padding 20×2），
   * 菜单项被 margin 挤成 48px，而 EP 给菜单项的 padding 是 0 20px、图标 24px，
   * 图标中心就落到 8 + 20 + 12 = 40px，比正中的 32px 偏右 8px。
   * 所以折叠时要把左右 margin 收成 0；并且让它跟着宽度一起过渡，
   * 不能在折叠的第一帧直接跳过去（那又是一处「僵硬」）。
   */
  transition: margin 0.28s var(--ease-standard),
              background-color 0.15s ease,
              color 0.15s ease;
}
.app-sidebar.collapsed .sidebar-menu .el-menu-item {
  margin-left: 0;
  margin-right: 0;
}
.sidebar-menu .el-menu-item:hover {
  background: rgba(255,255,255,0.08) !important;
}
.sidebar-menu .el-menu-item.is-active {
  background: rgba(64,158,255,0.2) !important;
}
.menu-icon {
  font-size: 18px;
}

/*
 * ═══════════ 折叠时菜单文字的动画 ═══════════
 *
 * EP 的规则是（el-menu.css）：
 *   .el-menu--collapse > .el-menu-item > span {
 *     visibility: hidden; width: 0; height: 0; display: inline-block; overflow: hidden;
 *   }
 * 没有任何过渡 —— 侧边栏宽度在 0.28s 里平滑滑动，文字却在第一帧就凭空消失，
 * 两者完全脱节。这是「僵硬」最主要的来源。
 *
 * 这里让标签保留自然尺寸，只靠 flex-shrink 收缩 + overflow 裁切：
 * 文字是被宽度动画本身推出去的（右边沿被裁掉），全程同一次布局动画，
 * 天然与侧栏宽度同步，不需要额外 transition，也不会两个动画各走各的。
 *
 * width / height / visibility 三个都得覆盖：只改 width 的话，
 * height: 0 配 overflow: hidden 仍会把文字纵向裁没，等于白改。
 */
.sidebar-menu.el-menu--collapse .el-menu-item > span {
  width: auto;
  height: auto;
  visibility: visible;
}
.sidebar-menu .el-menu-item > .menu-icon {
  flex-shrink: 0;   /* 图标不参与收缩，否则会被压扁 */
}
.sidebar-menu .el-menu-item > span {
  flex-shrink: 1;
  min-width: 0;     /* 少了它，flex 子项不肯收缩到内容宽度以下 */
  overflow: hidden;
  white-space: nowrap;
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

/*
 * 折叠按钮的图标翻转：Fold 是「«」，转 180° 就是 Expand 的「»」。
 * 换组件（Expand ↔ Fold）是瞬时替换、中间没有帧，只有翻转才补得出来。
 * 时长与侧栏宽度一致，两者同起同落。
 */
.collapse-icon {
  transition: transform 0.28s var(--ease-standard);
}
.collapse-icon.flipped {
  transform: rotate(180deg);
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
  /* 与主区左右留白同源，面包屑和页面内容左右对齐 */
  padding: 0 var(--main-padding-x);
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

/*
 * 窄屏只留图标。顶栏这一行同时挤着面包屑、这个按钮和用户名，
 * 三者里只有这个按钮的图标本身就能表意（连接），文字最先该让位。
 * 768 与 useMediaQuery 的 isMobile 断点同源。
 */
@media (max-width: 768px) {
  .header-action-text {
    display: none;
  }
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
  padding: var(--main-padding-y) var(--main-padding-x);
}

/* ── 小屏抽屉 ── */
.menu-toggle {
  font-size: 20px;
  color: var(--color-text-regular);
  cursor: pointer;
  margin-right: 12px;
  flex-shrink: 0;
}
.menu-toggle:hover { color: var(--color-primary); }

/*
 * 抽屉形态完全由 .drawer 这个类驱动，不再叠一条 @media (max-width: 768px) ——
 * 断点只在 useIsMobile() 里定义一次，CSS 再写一遍就有两处会各自漂移。
 * z-index 要盖过 .app-sidebar 的 100 和 .app-header 的 10。
 * 抽屉纵向铺满整屏（含盖住 header），这是移动端侧栏的通行做法。
 */
.app-sidebar.drawer {
  position: fixed;
  inset: 0 auto 0 0;
  width: var(--sidebar-width);
  transform: translateX(-100%);
  /* 与桌面端折叠同一条曲线、同一时长，两种形态的手感一致 */
  transition: transform 0.28s var(--ease-standard);
  z-index: 1001;
}
.app-sidebar.drawer.open { transform: translateX(0); }
/* 抽屉形态下没有「折叠成图标条」这个概念，收起按钮一并隐去 */
.app-sidebar.drawer .sidebar-collapse { display: none; }

.sidebar-scrim {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 1000;
}

/* Transition */
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.fade-enter-from { opacity: 0; transform: translateY(8px); }
.fade-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
