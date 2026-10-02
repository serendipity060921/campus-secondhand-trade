<script setup>
/**
 * 前台基础布局：顶部导航 + 内容区 + 页脚
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 当前高亮菜单：详情页 /product/3 也要高亮到「首页」 */
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/product/publish')) return '/product/publish'
  if (path.startsWith('/product/mine')) return '/product/mine'
  if (path.startsWith('/product')) return '/home'
  if (path.startsWith('/profile')) return '/profile'
  return path
})

function goLogin() {
  router.push('/login')
}

async function handleLogout() {
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
          <el-tag size="small" type="success" effect="plain">v0.05 商品模块</el-tag>
        </div>

        <el-menu :default-active="activeMenu" mode="horizontal" class="nav-menu" router :ellipsis="false">
          <el-menu-item index="/home">首页</el-menu-item>
          <el-menu-item index="/product/publish">发布商品</el-menu-item>
          <el-menu-item index="/product/mine">我的商品</el-menu-item>
          <el-menu-item index="/profile">个人中心</el-menu-item>
        </el-menu>

        <div class="header-right">
          <template v-if="userStore.isLogin">
            <el-dropdown>
              <span class="user-name">
                <el-avatar :size="28">{{ userStore.nickname.slice(0, 1) }}</el-avatar>
                <span class="ml-8">{{ userStore.nickname }}</span>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="router.push('/profile')">个人中心</el-dropdown-item>
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
        <p>校园二手交易平台 · 毕业设计项目 · 版本 v0.05（商品核心模块）</p>
        <p class="text-muted">
          技术栈：Spring Boot 3 + MyBatis-Plus + MySQL 8 + Vue 3 + Vite + Element Plus ·
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
  background: #fff;
  border-bottom: 1px solid #ebeef5;
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
  color: #409eff;
}

.nav-menu {
  flex: 1;
  border-bottom: none;
}

.header-right {
  display: flex;
  align-items: center;
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
  background: #fff;
  border-top: 1px solid #ebeef5;
  text-align: center;
  color: #606266;
  font-size: 13px;
  line-height: 1.8;
}
</style>
