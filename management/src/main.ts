import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import {
  ArrowDown, ArrowRight, ChatDotSquare, CircleCheck, Connection, CopyDocument, Document,
  Fold, Hide, Iphone, Key, Lightning, List, Loading, Lock, Menu, Monitor, Odometer,
  Plus, Promotion, Refresh, RefreshRight, Search, Setting, Share, Tickets, Timer,
  Tools, User, View, WarningFilled,
} from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)

/*
 * 只注册实际用到的图标，而不是全量 Object.entries(icons) —— 那样会把
 * @element-plus/icons-vue 全部 293 个图标的渲染函数都打进包里。
 *
 * 代价：新用一个图标时得回来补一行。之所以不能改成「在各组件里按需 import」，
 * 是因为 Layout.vue 的菜单靠 <component :is="item.icon" /> 配 'Odometer' 这类
 * 字符串名在跑，字符串解析依赖全局注册。真要彻底去掉这份清单，
 * 得先把 menuItems 里的 icon 从字符串改成组件引用。
 *
 * ⚠️ **漏补的后果不是报错，是那个位置空着**：Vue 解析不到组件名时会把它当原生标签
 * （渲染成 <connection> 之类），控制台只有一条 dev 警告，而**生产构建里连警告都没有**。
 * 这份清单曾经就漏了 Connection / Share / Tickets / Tools —— 于是「采集转发」整组
 * 四个菜单项全都是一小块空白，谁也没发现少了什么。
 */
const icons = {
  ArrowDown, ArrowRight, ChatDotSquare, CircleCheck, Connection, CopyDocument, Document,
  Fold, Hide, Iphone, Key, Lightning, List, Loading, Lock, Menu, Monitor, Odometer,
  Plus, Promotion, Refresh, RefreshRight, Search, Setting, Share, Tickets, Timer,
  Tools, User, View, WarningFilled,
}
for (const [name, component] of Object.entries(icons)) {
  app.component(name, component)
}

app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
