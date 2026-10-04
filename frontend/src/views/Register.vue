<script setup>
/**
 * 注册页（v0.04：对接后端 POST /api/user/register）
 *
 * 前端校验与后端 @Valid 校验保持一致：
 *   用户名 4~20 位字母/数字/下划线；密码 6~20 位且同时包含字母和数字；昵称 2~20 位
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { registerUser } from '@/api/user'
import { APP_VERSION } from '@/utils/version'

const router = useRouter()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: ''
})

/** 确认密码一致性校验 */
function validateConfirm(rule, value, callback) {
  if (!value) {
    callback(new Error('请再次输入密码'))
  } else if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 20, message: '用户名长度为 4~20 个字符', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]+$/, message: '用户名只能包含字母、数字和下划线', trigger: 'blur' }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 20, message: '昵称长度为 2~20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为 6~20 个字符', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d@$!%*#?&_.-]+$/,
      message: '密码必须同时包含字母和数字',
      trigger: 'blur'
    }
  ],
  confirmPassword: [{ required: true, validator: validateConfirm, trigger: 'blur' }]
}

async function handleRegister() {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await registerUser({
      username: form.username,
      password: form.password,
      nickname: form.nickname
    })
    ElMessage.success(`注册成功，用户ID：${res.data.id}，请登录`)
    // 跳回登录页并带上用户名，方便直接登录
    router.push({ path: '/login', query: { username: form.username } })
  } catch (e) {
    // 用户名已存在（2001）等错误已由 axios 拦截器统一提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <el-card class="register-card" shadow="always">
      <template #header>
        <div class="card-header">
          <span class="title">注册新账号</span>
          <el-tag size="small" type="success" effect="plain">{{ APP_VERSION }}</el-tag>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleRegister">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="4~20 位字母、数字或下划线" clearable />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="展示给其他同学的名字" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="6~20 位，需包含字母和数字" show-password />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入密码" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="submit-btn" :loading="loading" @click="handleRegister">注 册</el-button>
        </el-form-item>
      </el-form>

      <div class="bottom-row">
        <span class="text-muted">已有账号？</span>
        <el-link type="primary" :underline="false" @click="router.push('/login')">返回登录</el-link>
      </div>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="密码由 BCrypt 加密后存入数据库，服务端不保存明文。"
      />
    </el-card>
  </div>
</template>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e0f2ff 0%, #f5f7fa 100%);
  padding: 24px 0;
}

.register-card {
  width: 440px;
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

.submit-btn {
  width: 100%;
}

.bottom-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.tip {
  margin-top: 16px;
}
</style>
