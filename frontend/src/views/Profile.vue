<script setup>
/**
 * 个人中心（v0.04）
 *
 * 该页面在路由中标记了 meta.requiresAuth，并且调用的后端接口标注了 @LoginRequired，
 * 双重验证"未登录不能访问、Token 无效自动跳登录"：
 *   ① 路由守卫：本地无 Token → 直接跳登录页
 *   ② 后端拦截器：Token 缺失/过期/无效 → 返回 401 → axios 响应拦截器跳登录页
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getUserInfo } from '@/api/user'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const userInfo = ref(null)

async function loadUserInfo() {
  loading.value = true
  try {
    const res = await getUserInfo()
    userInfo.value = res.data
  } catch (e) {
    // 401 已由 axios 拦截器处理（清 Token + 跳登录页）
  } finally {
    loading.value = false
  }
}

async function handleLogout() {
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

onMounted(loadUserInfo)
</script>

<template>
  <div v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <b>个人中心</b>
          <el-tag size="small" type="warning" effect="plain">该接口需要 Token（@LoginRequired）</el-tag>
        </div>
      </template>

      <el-descriptions v-if="userInfo" :column="2" border>
        <el-descriptions-item label="用户ID">{{ userInfo.id }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ userInfo.username }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ userInfo.nickname }}</el-descriptions-item>
        <el-descriptions-item label="角色">
          <el-tag size="small" :type="userInfo.role === 1 ? 'danger' : 'success'">
            {{ userInfo.role === 1 ? '管理员' : '普通学生' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="信用分">{{ userInfo.creditScore }}</el-descriptions-item>
        <el-descriptions-item label="校区">{{ userInfo.campus || '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="最后登录">{{ userInfo.lastLoginTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ userInfo.createTime || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-empty v-else description="未获取到用户信息" :image-size="80" />

      <div class="actions">
        <el-button type="primary" @click="loadUserInfo">刷新（带 Token 重新请求）</el-button>
        <el-button @click="handleLogout">退出登录</el-button>
      </div>

      <!-- v0.06 快捷入口 -->
      <el-divider content-position="left">快捷入口</el-divider>
      <div class="quick-links">
        <el-button type="primary" plain @click="router.push('/profile/edit')">编辑资料 / 上传头像</el-button>
        <el-button plain type="warning" @click="router.push('/favorites')">★ 我的收藏</el-button>
        <el-button plain type="primary" @click="router.push('/product/mine')">我的商品</el-button>
        <el-button plain type="danger" @click="router.push('/orders/bought')">我买到的</el-button>
        <el-button plain type="success" @click="router.push('/orders/sold')">我卖出的</el-button>
        <el-button plain @click="router.push('/messages')">我的消息</el-button>
        <el-button plain type="success" @click="router.push('/product/publish')">发布商品</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="mt-16">
      <template #header><b>Token 自测说明</b></template>
      <el-alert type="info" :closable="false" show-icon>
        <p>1. 正常登录后本页可看到用户信息，说明 Token 校验通过；</p>
        <p>2. 在浏览器控制台执行 <code>localStorage.removeItem('campus_trade_token')</code> 后点「刷新」，会被拦截并自动跳转登录页；</p>
        <p>3. 在控制台执行 <code>localStorage.setItem('campus_trade_token','abc.def.ghi')</code> 后点「刷新」，后端返回 401「Token 无效」，同样跳转登录页。</p>
      </el-alert>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.actions {
  margin-top: 20px;
}

.quick-links {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

p {
  margin: 4px 0;
  line-height: 1.7;
}
</style>
