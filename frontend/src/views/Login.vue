<script setup>
/**
 * 登录页（v0.04：对接后端 POST /api/user/login）
 *
 * 流程：表单校验 → 调用登录接口 → 保存 Token 与用户信息（Pinia + localStorage）
 *      → 跳转到 redirect 参数指定的页面或首页
 */
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { loginUser } from '@/api/user'
import { useUserStore } from '@/store/user'
import { getHealth } from '@/api/common'
import { APP_VERSION } from '@/utils/version'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
/** 各字段当前是否校验失败：用于 aria-invalid，让读屏知道哪个字段不合格 */
const invalid = reactive({})

/** el-form 的 validate 事件：记录每个字段的校验结果 */
function onValidate(prop, isValid) {
  invalid[prop] = !isValid
}
const showCheck = ref(false)
const backendInfo = ref('')
const backendOk = ref(null)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为 6~20 位', trigger: 'blur' }
  ]
}

onMounted(() => {
  // 从注册页跳转过来时自动填充用户名
  if (route.query.username) {
    form.username = String(route.query.username)
  }
})

/** 登录 */
async function handleLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await loginUser({ username: form.username, password: form.password })
    const { token, userInfo } = res.data
    // 复用脚手架 Pinia 中已有的登录态保存方法（Token 同步写入 localStorage）
    userStore.setLoginState(token, userInfo)
    ElMessage.success(`欢迎回来，${userInfo.nickname || userInfo.username}`)
    router.push(route.query.redirect ? String(route.query.redirect) : '/home')
  } catch (e) {
    // 具体错误提示（账号不存在 2002 / 密码错误 2003 等）已由 axios 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

function goRegister() {
  router.push('/register')
}

/** 后端连通性自检（保留脚手架原有的自检能力） */
async function checkBackend() {
  try {
    const res = await getHealth()
    backendOk.value = true
    backendInfo.value = `后端连通正常：${res.data.application} ${res.data.version}（JDK ${res.data.javaVersion}）`
  } catch (e) {
    backendOk.value = false
    backendInfo.value = '后端未连通，请先启动 Spring Boot 服务（默认 http://localhost:8080）'
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <template #header>
        <div class="card-header">
          <h1 class="title">校园二手交易平台</h1>
          <el-tag size="small" type="success" effect="plain">{{ APP_VERSION }}</el-tag>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin"
               @validate="onValidate">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" aria-label="用户名"
                    aria-describedby="login-username-error" :aria-invalid="invalid.username === true" clearable />
          <!-- 自定义错误渲染：Element Plus 的报错元素没有 id，输入框无法与之关联，
               读屏只会念"编辑框"而说不出为什么不合格。这里给它一个 id 并保持 role="alert"。 -->
          <template #error="{ error }">
            <div id="login-username-error" class="field-error" role="alert">{{ error }}</div>
          </template>
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" aria-label="密码"
                    aria-describedby="login-password-error" :aria-invalid="invalid.password === true"
                    show-password clearable />
          <template #error="{ error }">
            <div id="login-password-error" class="field-error" role="alert">{{ error }}</div>
          </template>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="submit-btn" :loading="loading" @click="handleLogin">登 录</el-button>
        </el-form-item>
      </el-form>

      <div class="bottom-row">
        <span class="text-muted">还没有账号？</span>
        <el-link type="primary" :underline="false" @click="goRegister">立即注册</el-link>
        <el-link type="info" :underline="false" class="check-link" @click="showCheck = !showCheck">
          连通性自检
        </el-link>
      </div>

      <div v-if="showCheck" class="check-area">
        <el-button size="small" @click="checkBackend">检测后端接口</el-button>
        <el-tag v-if="backendOk === true" type="success" size="small">已连通</el-tag>
        <el-tag v-else-if="backendOk === false" type="danger" size="small">未连通</el-tag>
        <p v-if="backendInfo" class="check-info" :class="backendOk ? 'text-success' : 'text-danger'">
          {{ backendInfo }}
        </p>
      </div>

      <p class="tip text-muted">
        测试账号：管理员 <b>admin / 123456</b>（注册的新用户密码需同时包含字母和数字）
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
  background: var(--ct-bg-canvas);
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
  color: var(--ct-text-primary);
}

.submit-btn {
  width: 100%;
}

.bottom-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.check-link {
  margin-left: auto;
}

.check-area {
  margin-top: 12px;
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
  margin: var(--ct-space-4) 0 0;
  font-size: var(--ct-text-xs);
  line-height: 1.8;
}

/* 字段级错误（自定义渲染，带 id 供输入框 aria-describedby 关联） */
.field-error {
  margin-top: var(--ct-space-1);
  font-size: var(--ct-text-xs);
  line-height: 1.5;
  color: var(--ct-cat-6-bar);
}
</style>
