<script setup>
/**
 * 前台基础布局：顶部导航 + 内容区 + 页脚
 *
 * v0.14：登录后建立 WebSocket 实时连接，用于①消息角标②新私信提醒；
 *        退出登录时断开连接并清空实时状态。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { useChatStore } from '@/store/chat'
import { APP_VERSION, APP_VERSION_LABEL } from '@/utils/version'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

// v0.14：登录状态下初始化实时连接（含页面刷新：从 localStorage 恢复登录态后重连）
onMounted(() => {
  if (userStore.isLogin) {
    chatStore.init()
  }
})

/* ---------------- v0.09 顶部全局搜索 ---------------- */
const searchKeyword = ref('')

/* ---------------- v0.16 窄屏导航抽屉 ---------------- */
const drawerVisible = ref(false)
const navItems = [
  { path: '/home', label: '首页' },
  { path: '/product/publish', label: '发布商品' },
  { path: '/product/mine', label: '我的商品' },
  { path: '/messages', label: '消息' },
  { path: '/orders/bought', label: '订单' },
  { path: '/profile', label: '个人中心' }
]

/** 抽屉里搜索：先关抽屉再跳转，避免跳转后抽屉还盖在页面上 */
function handleDrawerSearch() {
  drawerVisible.value = false
  handleSearch()
}

// 进入 /search 页时回显关键词
watch(
  () => route.query.keyword,
  (v) => {
    searchKeyword.value = v ? String(v) : ''
  },
  { immediate: true }
)

/** 回车/点搜索按钮：跳到商品搜索结果页 */
function handleSearch() {
  const keyword = searchKeyword.value.trim()
  router.push({ path: '/search', query: keyword ? { keyword } : {} })
}

/** 当前高亮菜单：详情页 /product/3 也要高亮到「首页」 */
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/product/publish')) return '/product/publish'
  if (path.startsWith('/product/mine')) return '/product/mine'
  if (path.startsWith('/product')) return '/home'
  if (path.startsWith('/profile')) return '/profile'
  if (path.startsWith('/messages') || path.startsWith('/chat')) return '/messages'
  if (path.startsWith('/orders')) return '/orders/bought'
  if (path.startsWith('/favorites')) return '/favorites'
  return path
})

function goLogin() {
  router.push('/login')
}

async function handleLogout() {
  // v0.14：先断开实时连接再退出，避免退出后仍收到推送
  chatStore.reset()
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/home')
}
</script>

<template>
  <el-container class="layout">
    <!-- 顶部导航 -->
    <el-header class="layout-header">
      <div class="page-container header-inner">
        <div class="logo" @click="router.push('/home')">
          <span class="logo-icon">♻</span>
          <span class="logo-text">校园二手交易平台</span>
          <el-tag size="small" type="success" effect="plain">{{ APP_VERSION }}</el-tag>
        </div>

        <!-- v0.16：窄屏导航开关（768px 以下显示；导航收进抽屉，避免整站横向溢出） -->
        <button class="nav-toggle" type="button" aria-label="打开导航菜单" @click="drawerVisible = true">
          ☰
        </button>

        <el-menu :default-active="activeMenu" mode="horizontal" class="nav-menu" router :ellipsis="false">
          <el-menu-item index="/home">首页</el-menu-item>
          <el-menu-item index="/product/publish">发布商品</el-menu-item>
          <el-menu-item index="/product/mine">我的商品</el-menu-item>
          <el-menu-item index="/messages">
            消息
            <!-- v0.14：未读私信角标（WebSocket 实时更新） -->
            <el-badge v-if="chatStore.unreadTotal > 0" :value="chatStore.unreadTotal"
                      :max="99" class="msg-badge" />
          </el-menu-item>
          <el-menu-item index="/orders/bought">订单</el-menu-item>
          <el-menu-item index="/profile">个人中心</el-menu-item>
        </el-menu>

        <div class="header-right">
          <!-- v0.09 顶部搜索框：首页顶部即可直接搜索商品，回车跳到搜索结果页 -->
          <el-input
            v-model="searchKeyword"
            class="header-search"
            placeholder="搜索商品名称"
            clearable
            maxlength="50"
            @keyup.enter="handleSearch"
          >
            <template #append>
              <el-button @click="handleSearch">搜索</el-button>
            </template>
          </el-input>

          <template v-if="userStore.isLogin">
            <el-dropdown>
              <span class="user-name">
                <el-avatar :size="28">{{ userStore.nickname.slice(0, 1) }}</el-avatar>
                <span class="ml-8">{{ userStore.nickname }}</span>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="router.push('/profile')">个人中心</el-dropdown-item>
                  <el-dropdown-item @click="router.push('/orders/bought')">我买到的</el-dropdown-item>
                  <el-dropdown-item @click="router.push('/orders/sold')">我卖出的</el-dropdown-item>
                  <el-dropdown-item @click="router.push('/favorites')">我的收藏</el-dropdown-item>
                  <el-dropdown-item @click="router.push('/product/mine')">我的商品</el-dropdown-item>
                  <!-- v0.13：管理员才显示后台入口 -->
                  <el-dropdown-item v-if="userStore.isAdmin" divided
                                    @click="router.push('/admin/dashboard')">
                    🛠 管理后台
                  </el-dropdown-item>
                  <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <el-button v-else type="primary" @click="goLogin">登录 / 注册</el-button>
        </div>
      </div>
    </el-header>

    <!-- v0.16：窄屏导航抽屉（768px 以下由 ☰ 打开；桌面端不显示） -->
    <el-drawer v-model="drawerVisible" direction="ltr" size="78%" :with-header="false">
      <nav class="drawer-nav" aria-label="主导航">
        <router-link v-for="item in navItems" :key="item.path" :to="item.path" class="drawer-link"
                     :class="{ active: activeMenu === item.path }" @click="drawerVisible = false">
          <span>{{ item.label }}</span>
          <el-badge v-if="item.path === '/messages' && chatStore.unreadTotal > 0"
                    :value="chatStore.unreadTotal" :max="99" />
        </router-link>
      </nav>
      <el-input v-model="searchKeyword" class="drawer-search" placeholder="搜索商品名称" clearable
                maxlength="50" @keyup.enter="handleDrawerSearch">
        <template #append>
          <el-button @click="handleDrawerSearch">搜索</el-button>
        </template>
      </el-input>
    </el-drawer>

    <!-- 内容区 -->
    <el-main class="layout-main">
      <div class="page-container">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </div>
    </el-main>

    <!-- 页脚 -->
    <el-footer class="layout-footer">
      <div class="page-container">
        <p>校园二手交易平台 · 毕业设计项目 · 版本 {{ APP_VERSION }}（{{ APP_VERSION_LABEL.replace(APP_VERSION + ' ', '') }}）</p>
        <p class="text-muted">
          技术栈：Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis + WebSocket + Vue 3 + Vite + Element Plus ·
          <el-link type="info" :underline="false" @click="router.push('/dev/health')">连通性自检</el-link>
        </p>
      </div>
    </el-footer>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100%;
}

.layout-header {
  height: 64px;
  background: var(--ct-bg-surface);
  border-bottom: 1px solid var(--ct-paper-200);
  padding: 0;
  position: sticky;
  top: 0;
  z-index: 10;
}

.header-inner {
  display: flex;
  align-items: center;
  height: 64px;
  gap: 24px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  white-space: nowrap;
}

.logo-icon {
  font-size: 22px;
}

.logo-text {
  font-size: 18px;
  font-weight: 600;
  color: var(--ct-text-primary);
}

.nav-menu {
  flex: 1;
  border-bottom: none;
}

/* ---------- v0.16 窄屏导航 ----------
   768px 以下把主导航与搜索收进抽屉：原来 .header-inner 的固有宽度是 1094px
   （240px 搜索框 + 6 项横向菜单且不折叠），导致 390/768/1024 下分别恒定溢出
   704/326/70px，连"消息/订单/个人中心"入口都在屏外。 */
.nav-toggle {
  display: none;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  min-height: 44px;
  font-size: var(--ct-text-lg);
  color: var(--ct-text-primary);
  background: transparent;
  border: var(--ct-hairline) solid var(--ct-border);
  border-radius: var(--ct-radius-sm);
  cursor: pointer;
}

.drawer-nav {
  display: flex;
  flex-direction: column;
  margin-bottom: var(--ct-space-4);
}

.drawer-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 44px;                      /* 触控目标下限 */
  padding: 0 var(--ct-space-2);
  color: var(--ct-text-primary);
  border-bottom: var(--ct-hairline) solid var(--ct-border);
}

.drawer-link.active {
  background: var(--ct-bg-subtle);
  font-weight: var(--ct-weight-semibold);
}

.drawer-search {
  width: 100%;
}

/* 断点取 1100px：顶栏固有宽度是 1094px（240px 搜索框 + 6 项不折叠的横向菜单），
   所以 1024/1080 这类小屏笔记本也必须收进抽屉，否则仍会横向溢出（实测 1024 溢出 70px）。 */
@media (max-width: 1100px) {
  .header-inner {
    gap: var(--ct-space-2);
    padding-left: var(--ct-space-3);
    padding-right: var(--ct-space-3);
    min-width: 0;
  }

  .nav-menu,
  .header-search {
    display: none;
  }

  .nav-toggle {
    display: inline-flex;
  }

  .logo {
    flex: 1;
    min-width: 0;
  }

  .logo-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

/* v0.14：顶部导航的未读消息角标 */
.msg-badge {
  margin-left: 6px;
  margin-top: -2px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-search {
  width: 240px;
}

.user-name {
  display: flex;
  align-items: center;
  cursor: pointer;
  outline: none;
}

.ml-8 {
  margin-left: 8px;
}

.layout-main {
  padding: 24px 0;
  min-height: calc(100vh - 64px - 96px);
}

.layout-footer {
  height: auto;
  padding: 16px 0;
  background: var(--ct-bg-surface);
  border-top: 1px solid var(--ct-paper-200);
  text-align: center;
  color: var(--ct-text-primary);
  font-size: 13px;
  line-height: 1.8;
}
</style>
