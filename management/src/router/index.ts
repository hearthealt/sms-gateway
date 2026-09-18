import { createRouter, createWebHistory } from 'vue-router'

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
          meta: { title: '规则管理' },
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
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth !== false && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router