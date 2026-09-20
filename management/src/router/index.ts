import { createRouter, createWebHistory } from 'vue-router'
import { getValidToken } from '../utils/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/Login.vue'),
      meta: { requiresAuth: false },
    },
    {
      path: '/',
      component: () => import('../components/Layout.vue'),
      redirect: '/dashboard',
      meta: { requiresAuth: true },
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('../views/Dashboard.vue'),
          meta: { title: '仪表盘' },
        },
        {
          path: 'devices',
          name: 'DeviceList',
          component: () => import('../views/DeviceList.vue'),
          meta: { title: '设备管理' },
        },
        {
          path: 'devices/:deviceId',
          name: 'DeviceDetail',
          component: () => import('../views/DeviceDetail.vue'),
          meta: { title: '设备详情' },
        },
        {
          path: 'sms',
          name: 'SmsList',
          component: () => import('../views/SmsList.vue'),
          meta: { title: '短信记录' },
        },
        {
          path: 'rules',
          name: 'RuleManagement',
          component: () => import('../views/RuleManagement.vue'),
          // 叫「采集规则」而不是「规则管理」：与「转发规则」并排时，
          // 「规则管理 / 转发规则」分不清说的是哪个，而它们本来就是两套规则。
          // 页面内部的小标题也一直是「采集规则」，这里对齐。
          meta: { title: '采集规则' },
        },
        // 转发三个页面的顺序就是配置时的顺序：先建渠道（发到哪）→ 再建规则
        // （哪些短信发过去）→ 最后看投递记录（发出去了吗）。
        {
          path: 'notify/channels',
          name: 'NotifyChannel',
          component: () => import('../views/NotifyChannel.vue'),
          meta: { title: '转发渠道' },
        },
        {
          path: 'notify/routes',
          name: 'NotifyRoute',
          component: () => import('../views/NotifyRoute.vue'),
          meta: { title: '转发规则' },
        },
        {
          path: 'notify/deliveries',
          name: 'NotifyDelivery',
          component: () => import('../views/NotifyDelivery.vue'),
          meta: { title: '投递记录' },
        },
        {
          path: 'apikeys',
          name: 'ApiKeyManagement',
          component: () => import('../views/ApiKeyManagement.vue'),
          meta: { title: 'API 密钥' },
        },
        {
          path: 'api-docs',
          name: 'ApiDocs',
          component: () => import('../views/ApiDocs.vue'),
          meta: { title: '接口文档' },
        },
        // 放最后：配置项改得最少，而列表页天天要看
        {
          path: 'sysconfig',
          name: 'SysConfig',
          component: () => import('../views/SysConfig.vue'),
          meta: { title: '系统设置' },
        },
      ],
    },
    /*
     * 兜底路由：没有它，地址写错时没有任何 route 匹配，页面一片空白、
     * 只在控制台留一条警告，管理员看不出是自己路径写错了。
     * 不放进 Layout 的 children，是因为它不该套后台框架（侧边栏/面包屑）；
     * requiresAuth 显式给 false —— 路径本身就不存在，先弹登录页只会把问题引到别处。
     */
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('../views/NotFound.vue'),
      meta: { requiresAuth: false },
    },
  ],
})

router.beforeEach((to, _from, next) => {
  // 判「有没有」不够：服务端 TTL 一到，界面一切正常但点什么都失败
  const token = getValidToken()
  if (to.meta.requiresAuth !== false && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router