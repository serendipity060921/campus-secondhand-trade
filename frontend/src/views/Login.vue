<script setup>
/**
 * 登录页（脚手架阶段：表单与样式就绪，登录接口待后端 v0.04 实现）
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getHealth } from '@/api/common'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const backendOk = ref(null)
const backendInfo = ref('')

const form = reactive({
  username: 'admin',
  password: '123456'
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ]
}

/** 检测后端连通性（脚手架阶段的核心验证入口） */
async function checkBackend() {
  try {
    const res = await getHealth()
    backendOk.value = true
    backendInfo.value = `后端连通正常：${res.data.application} ${res.data.version}（${res.data.profile} / JDK ${res.data.javaVersion}）`
    ElMessage.success('后端连通正常')
  } catch (e) {
    backendOk.value = false
    backendInfo.value = '后端未连通，请先启动 Spring Boot 服务（默认 http://localhost:8080）'
  }
}

async function handleLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    // 后端登录接口属于下一个里程碑，这里先给出明确提示，避免误以为登录失败
    await userStore.login({ username: form.username, password: form.password })
    ElMessage.success('登录成功')
    router.push(route.query.redirect || '/home')
  } catch (e) {
    ElMessage.warning('登录接口尚未实现（当前里程碑为脚手架 v0.03），后端接口完成后即可直接使用')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <template #header>
        <div class="card-header">
          <span class="title">校园二手交易平台</span>
          <el-tag size="small" type="success" effect="plain">v0.03</el-tag>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名 / 学号" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">登 录</el-button>
        </el-form-item>
      </el-form>

      <el-divider>脚手架连通性自检</el-divider>

      <div class="check-area">
        <el-button size="small" @click="checkBackend">检测后端接口</el-button>
        <el-tag v-if="backendOk === true" type="success" size="small">已连通</el-tag>
        <el-tag v-else-if="backendOk === false" type="danger" size="small">未连通</el-tag>
        <p v-if="backendInfo" class="check-info" :class="backendOk ? 'text-success' : 'text-danger'">
          {{ backendInfo }}
        </p>
      </div>

      <p class="tip text-muted">
        提示：登录 / 注册属于下一里程碑（v0.04）。当前可先访问
        <el-link type="primary" @click="router.push('/home')">首页</el-link>
        查看前后端与数据库的连通情况。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e0f2ff 0%, #f5f7fa 100%);
}

.login-card {
  width: 420px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: #409eff;
}

.login-btn {
  width: 100%;
}

.check-area {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.check-info {
  width: 100%;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.tip {
  margin-top: 16px;
  font-size: 12px;
  line-height: 1.8;
}
</style>
