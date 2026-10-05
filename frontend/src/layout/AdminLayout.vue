<script setup>
/**
 * 管理后台布局（v0.13）
 *
 * 结构与前台 FrontLayout 平行：左侧菜单 + 顶部信息栏 + 内容区。
 * 入口由路由守卫保证（requiresAuth + requiresAdmin），接口侧还有 @LoginRequired(admin=true) 兜底。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDashboard } from '@/api/admin'
import { useUserStore } from '@/store/user'
import { onMounted, ref } from 'vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 待办数量（用于菜单红点） */
const pending = ref({ audit: 0, report: 0 })

const activeMenu = computed(() => route.path)

const avatarText = computed(() => {
  const nick = userStore.nickname || '管理员'
  return nick.slice(0, 1)
})

async function loadPending() {
  try {
    const res = await getDashboard()
    pending.value = {
      audit: res.data.overview.pendingAuditCount || 0,
      report: res.data.overview.pendingReportCount || 0
    }
  } catch (e) {
    /* 拦截器已提示 */
  }
}

function handleLogout() {
  ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
    .then(async () => {
      await userStore.logout()
      ElMessage.success('已退出登录')
      router.push('/login')
    })
    .catch(() => {})
}

onMounted(loadPending)
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="210px" class="aside">
      <!-- logo 用 router-link：此前是带 @click 的 div，键盘和读屏都用不了 -->
      <router-link class="logo" to="/home" aria-label="回到前台首页">
        <span class="logo-icon">♻</span>
        <span class="logo-text">校园二手 · 管理后台</span>
      </router-link>
      <!-- 侧栏导航有 nav 地标：读屏可一键跳到导航区（前台顶栏同样处理） -->
      <nav aria-label="后台导航">
      <el-menu :default-active="activeMenu" router class="menu" background-color="var(--ct-bg-surface)"
               text-color="var(--ct-text-muted)" active-text-color="var(--ct-text-primary)">
        <el-menu-item index="/admin/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>数据看板</span>
        </el-menu-item>
        <el-menu-item index="/admin/products">
          <el-icon><Goods /></el-icon>
          <span>商品管理</span>
          <el-badge v-if="pending.audit" :value="pending.audit" class="menu-badge" />
        </el-menu-item>
        <el-menu-item index="/admin/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/reports">
          <el-icon><Warning /></el-icon>
          <span>举报处理</span>
          <el-badge v-if="pending.report" :value="pending.report" class="menu-badge" />
        </el-menu-item>
      </el-menu>
      </nav>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <span class="page-title">{{ route.meta?.title || '管理后台' }}</span>
        </div>
        <div class="header-right">
          <el-button text type="primary" @click="router.push('/home')">← 返回前台</el-button>
          <el-avatar :size="30">{{ avatarText }}</el-avatar>
          <span class="nickname">{{ userStore.nickname }}</span>
          <el-tag size="small" type="danger">管理员</el-tag>
          <el-button size="small" @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
}

.aside {
  background: var(--ct-bg-surface);
  overflow-x: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  color: var(--ct-text-inverse);
  cursor: pointer;
  border-bottom: 1px solid var(--ct-border);
}

.logo-icon {
  font-size: 20px;
}

.logo-text {
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
}

.menu {
  border-right: none;
}

.menu-badge {
  margin-left: 6px;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--ct-bg-surface);
  border-bottom: 1px solid var(--ct-paper-200);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ct-text-primary);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.nickname {
  font-size: 13px;
  color: var(--ct-text-primary);
}

.main {
  background: var(--ct-bg-canvas);
  padding: 16px;
}
</style>
