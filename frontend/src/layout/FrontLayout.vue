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
