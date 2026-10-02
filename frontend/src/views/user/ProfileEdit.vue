<script setup>
/**
 * 个人资料编辑页（v0.09，需要登录）
 *
 * 功能：头像上传（即时生效）+ 昵称/真实姓名/性别/学校/校区/手机号/邮箱 修改。
 * 说明：用户名与学号是账号凭证，只能查看不能修改。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProfile, updateProfile, uploadAvatar } from '@/api/profile'
import { useUserStore } from '@/store/user'
import { resolveImageUrl } from '@/utils/product'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const profile = ref(null)

const form = reactive({
  nickname: '',
  realName: '',
  gender: 0,
  school: '',
  campus: '',
  phone: '',
  email: ''
})

const rules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 20, message: '昵称长度为 2~20 个字符', trigger: 'blur' }
  ],
  realName: [{ max: 50, message: '真实姓名不能超过 50 个字符', trigger: 'blur' }],
  school: [{ max: 100, message: '学校名称不能超过 100 个字符', trigger: 'blur' }],
  campus: [{ max: 100, message: '校区名称不能超过 100 个字符', trigger: 'blur' }],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

const avatarUrl = computed(() => {
  const avatar = profile.value?.avatar
  return avatar ? resolveImageUrl(avatar) : ''
})

const avatarText = computed(() => (form.nickname || '匿').slice(0, 1))

async function loadProfile() {
  loading.value = true
  try {
    const res = await getProfile()
    profile.value = res.data
    form.nickname = res.data.nickname || ''
    form.realName = res.data.realName || ''
    form.gender = res.data.gender ?? 0
    form.school = res.data.school || ''
    form.campus = res.data.campus || ''
    form.phone = res.data.phone || ''
    form.email = res.data.email || ''
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

/** 头像上传前校验（后端还会再校验一次） */
function beforeAvatarUpload(file) {
  const isImage = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp'].includes(file.type)
  if (!isImage) {
    ElMessage.error('只支持 jpg / png / gif / webp / bmp 格式的图片')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('头像大小不能超过 5MB')
    return false
  }
  return true
}

/** 自定义上传：走统一 axios 实例（自动带 Token），成功后立即刷新本地用户态 */
async function handleAvatarUpload(options) {
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', options.file)
    const res = await uploadAvatar(formData)
    profile.value = res.data
    // 同步 Pinia / localStorage，顶部导航的头像与昵称立即更新
    userStore.setLoginState(userStore.token, res.data)
    ElMessage.success('头像上传成功')
    return res.data
  } finally {
    uploading.value = false
  }
}

async function handleSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    const res = await updateProfile({
      nickname: form.nickname,
      realName: form.realName,
      gender: form.gender,
      school: form.school,
      campus: form.campus,
      phone: form.phone,
      email: form.email
    })
    profile.value = res.data
    userStore.setLoginState(userStore.token, res.data)
    ElMessage.success('资料已保存')
    router.push('/profile')
  } catch (e) {
    /* 手机号被占用(2006) 等错误由拦截器提示 */
  } finally {
    saving.value = false
  }
}

onMounted(loadProfile)
</script>

<template>
  <div v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <b>编辑个人资料</b>
          <el-button text type="primary" @click="router.push('/profile')">← 返回个人中心</el-button>
        </div>
      </template>

      <el-row :gutter="32">
        <!-- 左：头像 -->
        <el-col :xs="24" :sm="8">
          <div class="avatar-box">
            <el-upload
              class="avatar-upload"
              :show-file-list="false"
              :before-upload="beforeAvatarUpload"
              :http-request="handleAvatarUpload"
              accept="image/*"
            >
              <el-avatar v-if="avatarUrl" :size="120" :src="avatarUrl" />
              <el-avatar v-else :size="120">{{ avatarText }}</el-avatar>
              <div class="avatar-tip">{{ uploading ? '上传中…' : '点击更换头像' }}</div>
            </el-upload>
            <p class="text-muted small">支持 jpg/png/gif/webp，≤ 5MB，上传后立即生效</p>
            <el-descriptions :column="1" border size="small" class="mt-16">
              <el-descriptions-item label="用户名">{{ profile?.username || '-' }}</el-descriptions-item>
              <el-descriptions-item label="学号">{{ profile?.studentNo || '未填写' }}</el-descriptions-item>
              <el-descriptions-item label="角色">
                <el-tag size="small" :type="profile?.role === 1 ? 'danger' : 'success'">
                  {{ profile?.role === 1 ? '管理员' : '普通学生' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="信用分">{{ profile?.creditScore ?? '-' }}</el-descriptions-item>
            </el-descriptions>
            <el-alert
              class="mt-16"
              type="info"
              :closable="false"
              show-icon
              title="用户名与学号是账号凭证，注册后不可修改"
            />
          </div>
        </el-col>

        <!-- 右：资料表单 -->
        <el-col :xs="24" :sm="16">
          <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="form.nickname" maxlength="20" show-word-limit placeholder="展示给其他同学的名字" />
            </el-form-item>
            <el-form-item label="真实姓名" prop="realName">
              <el-input v-model="form.realName" maxlength="50" placeholder="用于校内身份核对，选填" />
            </el-form-item>
            <el-form-item label="性别">
              <el-radio-group v-model="form.gender">
                <el-radio-button :value="0">保密</el-radio-button>
                <el-radio-button :value="1">男</el-radio-button>
                <el-radio-button :value="2">女</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="学校" prop="school">
              <el-input v-model="form.school" maxlength="100" placeholder="例如：示范大学" />
            </el-form-item>
            <el-form-item label="所在校区" prop="campus">
              <el-input v-model="form.campus" maxlength="100" placeholder="例如：东校区" />
            </el-form-item>
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" maxlength="11" placeholder="全平台唯一，用于接收交易通知" />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="form.email" maxlength="100" placeholder="例如：stu01@campus.edu" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="handleSave">保存修改</el-button>
              <el-button @click="loadProfile">重置</el-button>
            </el-form-item>
          </el-form>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.avatar-box {
  text-align: center;
}

.avatar-upload {
  display: inline-block;
  cursor: pointer;
}

.avatar-tip {
  margin-top: 8px;
  font-size: 13px;
  color: #409eff;
}

.small {
  font-size: 12px;
  margin: 8px 0 0;
}

.mt-16 {
  margin-top: 16px;
  text-align: left;
}
</style>
