import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/auth'
import FrontLayout from '@/layout/FrontLayout.vue'

/**
 * 路由表
 *
 * meta.requiresAuth = true 表示需要登录后才能访问，
 * meta.title 用于设置浏览器标题。
 *
 * v0.05 起首页改为商品列表；脚手架 v0.03 的连通性看板移到 /dev/health。
 * 注意：/product/publish、/product/mine 必须写在 /product/:id 之前，
 *      否则会被动态路由抢先匹配。
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
        component: () => import('@/views/product/List.vue'),
        meta: { title: '首页' }
      },
      {
        // v0.09 商品搜索/筛选结果页（公开，关键词与分类同步在 URL 上）
        path: 'search',
        name: 'ProductSearch',
        component: () => import('@/views/product/Search.vue'),
        meta: { title: '搜索商品' }
      },
      {
        path: 'product/publish',
        name: 'ProductPublish',
        component: () => import('@/views/product/Publish.vue'),
        meta: { title: '发布商品', requiresAuth: true }
      },
      {
        path: 'product/mine',
        name: 'MyProducts',
        component: () => import('@/views/product/MyProducts.vue'),
        meta: { title: '我的商品', requiresAuth: true }
      },
      {
        path: 'product/:id',
        name: 'ProductDetail',
        component: () => import('@/views/product/Detail.vue'),
        meta: { title: '商品详情' }
      },
      {
        // 需要登录的页面（meta.requiresAuth = true，由下面的路由守卫拦截）
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/Profile.vue'),
        meta: { title: '个人中心', requiresAuth: true }
      },
      {
        // v0.06 我的收藏（个人中心入口）
        path: 'favorites',
        name: 'MyFavorites',
        component: () => import('@/views/user/MyFavorites.vue'),
        meta: { title: '我的收藏', requiresAuth: true }
      },
      {
        // v0.09 编辑个人资料（含头像上传）
        path: 'profile/edit',
        name: 'ProfileEdit',
        component: () => import('@/views/user/ProfileEdit.vue'),
        meta: { title: '编辑资料', requiresAuth: true }
      },
      {
        // v0.07 消息会话列表
        path: 'messages',
        name: 'ConversationList',
        component: () => import('@/views/message/ConversationList.vue'),
        meta: { title: '我的消息', requiresAuth: true }
      },
      {
        // v0.07 聊天窗口（:userId 为聊天对象ID，可带 ?productId= 关联商品）
        path: 'chat/:userId',
        name: 'Chat',
        component: () => import('@/views/message/Chat.vue'),
        meta: { title: '聊天', requiresAuth: true }
      },
      {
        // v0.08 我买到的订单
        path: 'orders/bought',
        name: 'OrderBought',
        component: () => import('@/views/order/OrderList.vue'),
        props: { role: 'buy' },
        meta: { title: '我买到的', requiresAuth: true }
      },
      {
        // v0.08 我卖出的订单
        path: 'orders/sold',
        name: 'OrderSold',
        component: () => import('@/views/order/OrderList.vue'),
        props: { role: 'sell' },
        meta: { title: '我卖出的', requiresAuth: true }
      },
      {
        // v0.08 订单详情（必须放在 orders/bought、orders/sold 之后）
        path: 'orders/:id',
        name: 'OrderDetail',
        component: () => import('@/views/order/Detail.vue'),
        meta: { title: '订单详情', requiresAuth: true }
      },
      {
        // 脚手架自带的连通性自检看板（v0.03）
        path: 'dev/health',
        name: 'DevHealth',
        component: () => import('@/views/Home.vue'),
        meta: { title: '连通性自检' }
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
