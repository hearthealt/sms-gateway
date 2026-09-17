/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

// element-plus 的语言包是 .mjs，没有随包提供类型声明
declare module 'element-plus/dist/locale/zh-cn.mjs'