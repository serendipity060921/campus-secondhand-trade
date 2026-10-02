import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/auth'
import FrontLayout from '@/layout/FrontLayout.vue'

/**
 * 路由表（脚手架阶段只有登录页与首页）
 *
 * meta.requiresAuth = true 表示需要登录后才能访问，
 * meta.title 用于设置浏览器标题。
 */
const routes = [
  {
    path: '/',
    component: FrontLayout,
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'Home',
        component: () => import('@/views/Home.vue'),
        meta: { title: '首页' }
      },
      {
        // 需要登录的页面（meta.requiresAuth = true，由下面的路由守卫拦截）
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/Profile.vue'),
        meta: { title: '个人中心', requiresAuth: true }
      }
    ]
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/** 全局前置守卫：登录校验（脚手架阶段仅打通流程，登录接口待实现） */
router.beforeEach((to, from, next) => {
  document.title = to.meta?.title ? `${to.meta.title} - 校园二手交易平台` : '校园二手交易平台'

  const token = getToken()
  if (to.meta?.requiresAuth && !token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  next()
})

export default router
