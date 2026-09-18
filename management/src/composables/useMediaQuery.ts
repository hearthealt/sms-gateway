import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 响应式地判断一条媒体查询当前是否命中。
 *
 * 放在 composables/ 而不是 utils/：utils/auth.ts、utils/clipboard.ts 都是纯函数，
 * 把依赖 Vue 生命周期的东西混进去，后来人会以为 utils 里的都能随便在模块顶层调用。
 *
 * 立即求值一次再挂监听，是为了避免首帧先按桌面布局渲染、挂载后才切到移动端的那一下闪动。
 * 本项目是纯 SPA（无 SSR），此处直接读 window 是安全的。
 */
export function useMediaQuery(query: string): Ref<boolean> {
  const mql = window.matchMedia(query)
  const matches = ref(mql.matches)

  function update(e: MediaQueryListEvent) {
    matches.value = e.matches
  }

  onMounted(() => mql.addEventListener('change', update))
  onBeforeUnmount(() => mql.removeEventListener('change', update))

  return matches
}

/** 手机 / 平板竖屏：侧边栏改抽屉，主区留白收紧。 */
export function useIsMobile(): Ref<boolean> {
  return useMediaQuery('(max-width: 768px)')
}

/** 窄屏：侧边栏自动收成图标条。与 App.vue 里收窄主区留白的是同一条线。 */
export function useIsNarrow(): Ref<boolean> {
  return useMediaQuery('(max-width: 1200px)')
}
