<script setup>
/**
 * 商品发布页（v0.05，需要登录）
 *
 * 流程：填写表单 → 选择图片（立即上传到 /api/product/upload 拿到 url 并预览）
 *      → 提交 /api/product/publish（图片 url 一并提交，后端写入 product_image 表）
 *
 * 图片说明：不传图片也能发布，前端列表/详情会自动按分类显示 demo-images 下的占位图。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCategoryList, publishProduct, uploadProductImages } from '@/api/product'
import { CONDITION_OPTIONS, demoImage } from '@/utils/product'

const router = useRouter()

const formRef = ref()
const submitting = ref(false)
const uploading = ref(false)
/** 图片上传失败：toast 会消失，用这个标记驱动一条持续可见的失败说明 */
const uploadError = ref(false)
const fileList = ref([])
/** 上传成功后拿到的图片地址（提交时一并发送） */
const uploadedUrls = ref([])
const categories = ref([])

const form = reactive({
  title: '',
  categoryId: null,
  price: null,
  originalPrice: null,
  conditionLevel: 2,
  campus: '东校区',
  tradePlace: '',
  description: ''
})

const rules = {
  title: [
    { required: true, message: '请输入商品名称', trigger: 'blur' },
    { min: 2, max: 100, message: '商品名称长度为 2~100 个字符', trigger: 'blur' }
  ],
  categoryId: [{ required: true, message: '请选择商品分类', trigger: 'change' }],
  price: [
    { required: true, message: '请输入售价', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value === null || value === undefined || value === '') return callback(new Error('请输入售价'))
        if (Number(value) <= 0) return callback(new Error('售价必须大于 0'))
        if (!/^\d{1,8}(\.\d{1,2})?$/.test(String(value))) return callback(new Error('售价最多 8 位整数、2 位小数'))
        callback()
      },
      trigger: 'blur'
    }
  ],
  conditionLevel: [{ required: true, message: '请选择成色', trigger: 'change' }],
  description: [{ max: 2000, message: '商品描述不能超过 2000 个字符', trigger: 'blur' }]
}

/** 分类下拉：一级分类分组 */
const categoryGroups = computed(() => {
  const tops = categories.value.filter((c) => !c.parentId || c.parentId === 0)
  return tops.map((top) => ({
    label: top.name,
    options: [
      { id: top.id, name: `${top.name}（一级分类）` },
      ...categories.value.filter((c) => c.parentId === top.id)
    ]
  }))
})

/** 当前选中分类名（用于占位图预览） */
const selectedCategoryName = computed(() => {
  const found = categories.value.find((c) => c.id === form.categoryId)
  return found ? found.name : ''
})

/** 未上传图片时展示的占位图预览 */
const placeholderPreview = computed(() => demoImage(selectedCategoryName.value))

async function loadCategories() {
  const res = await getCategoryList()
  categories.value = res.data || []
}

/* ---------------- 图片上传 ---------------- */

function beforeUpload(file) {
  const isImage = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp'].includes(file.type)
  if (!isImage) {
    ElMessage.error('只支持 jpg / png / gif / webp / bmp 格式的图片')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片大小不能超过 5MB')
    return false
  }
  return true
}

/** 自定义上传：走项目统一的 axios 实例（自动带 Token） */
async function handleUpload(options) {
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', options.file)
    const res = await uploadProductImages(formData)
    // 返回 Promise，Element Plus 会自动调用 onSuccess
    return res.data[0]
  } finally {
    uploading.value = false
  }
}

function onUploadSuccess(response, uploadFile) {
  // 用服务器返回的地址作为缩略图
  uploadFile.url = response.url
  uploadedUrls.value.push(response.url)
  uploadError.value = false
  ElMessage.success('图片上传成功')
}

function onUploadError() {
  // toast 会消失，因此额外留一个持续可见的失败说明（模板里的 role="alert"）
  uploadError.value = true
  ElMessage.error('图片上传失败，请重试')
}

function onUploadRemove(file) {
  const url = file.url || (file.response && file.response.url)
  uploadedUrls.value = uploadedUrls.value.filter((u) => u !== url)
}

function onExceed() {
  ElMessage.warning('最多上传 9 张图片')
}

/* ---------------- 提交 ---------------- */

async function handleSubmit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    const res = await publishProduct({
      title: form.title,
      description: form.description,
      categoryId: form.categoryId,
      price: form.price,
      originalPrice: form.originalPrice || null,
      conditionLevel: form.conditionLevel,
      campus: form.campus,
      tradePlace: form.tradePlace,
      imageUrls: uploadedUrls.value
    })
    ElMessage.success('发布成功！')
    router.push(`/product/${res.data.id}`)
  } catch (e) {
    /* 错误提示由 axios 拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

function handleReset() {
  formRef.value.resetFields()
  fileList.value = []
  uploadedUrls.value = []
}

onMounted(loadCategories)
</script>

<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <h1 class="form-title">发布闲置商品</h1>
          <el-tag size="small" type="warning" effect="plain">卖家：当前登录用户</el-tag>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" class="publish-form">
        <p class="section-title">基本信息</p>

        <el-form-item label="商品名称" prop="title">
          <el-input v-model="form.title" maxlength="100" show-word-limit aria-label="商品名称" placeholder="例如：《数据结构》教材 九成新" />
        </el-form-item>

        <el-form-item label="商品分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="请选择分类" aria-label="商品分类" class="w-320">
            <el-option-group v-for="group in categoryGroups" :key="group.label" :label="group.label">
              <el-option v-for="item in group.options" :key="item.id" :label="item.name" :value="item.id" />
            </el-option-group>
          </el-select>
        </el-form-item>

        <p class="section-title">价格与成色</p>

        <el-form-item label="售价(元)" prop="price">
          <el-input-number v-model="form.price" :min="0.01" :max="99999999" :precision="2" :step="1" aria-label="售价（元）" />
          <span class="tip-text">原价（选填）：</span>
          <el-input-number v-model="form.originalPrice" :min="0" :max="99999999" :precision="2" :step="1" aria-label="原价（选填）" />
        </el-form-item>

        <el-form-item label="成色" prop="conditionLevel">
          <el-radio-group v-model="form.conditionLevel">
            <el-radio-button v-for="item in CONDITION_OPTIONS" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <p class="section-title">交易信息</p>

        <el-form-item label="交易校区">
          <el-input v-model="form.campus" class="w-320" placeholder="例如：东校区" aria-label="交易校区" />
        </el-form-item>

        <el-form-item label="交易地点">
          <el-input v-model="form.tradePlace" class="w-480" placeholder="例如：东校区图书馆门口" aria-label="交易地点" />
        </el-form-item>

        <p class="section-title">图片与描述</p>

        <el-form-item label="商品图片">
          <div class="upload-area">
            <el-upload
              v-model:file-list="fileList"
              list-type="picture-card"
              accept="image/*"
              :limit="9"
              :http-request="handleUpload"
              :before-upload="beforeUpload"
              :on-success="onUploadSuccess"
              :on-error="onUploadError"
              :on-remove="onUploadRemove"
              :on-exceed="onExceed"
            >
              <div class="upload-plus">+</div>
            </el-upload>
            <div class="upload-tip">
              <p>支持 jpg/png/gif/webp/bmp，单张 ≤ 5MB，最多 9 张，第一张作为封面。</p>
              <!-- 上传失败时留痕并可重试：toast 会消失，这里给一条持续可见的说明 -->
              <p v-if="uploadError" class="upload-error" role="alert">
                图片上传失败，请重新选择图片再试一次（已填写的其他内容不会丢失）。
              </p>
              <p v-if="uploadedUrls.length === 0" class="text-muted">
                未上传图片时会自动使用分类占位图：
              </p>
              <img v-if="uploadedUrls.length === 0" class="placeholder-preview" :src="placeholderPreview" alt="占位图" />
            </div>
          </div>
        </el-form-item>

        <el-form-item label="商品描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            aria-label="商品描述"
            placeholder="说明成色、入手渠道、瑕疵、交易方式等"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">立即发布</el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button text type="primary" @click="router.push('/product/mine')">查看我的商品</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 页面主标题（此前是 <b>，全页无 h1） */
.form-title {
  margin: 0;
  font-size: var(--ct-text-lg);
  font-weight: var(--ct-weight-semibold);
}

/* 分组小标题：把 8 个字段分成四段，降低表单的认知负担 */
.section-title {
  margin: var(--ct-space-5) 0 var(--ct-space-4);
  padding-bottom: var(--ct-space-2);
  border-bottom: var(--ct-hairline) solid var(--ct-border);
  font-size: var(--ct-text-sm);
  font-weight: var(--ct-weight-semibold);
  color: var(--ct-text-primary);
}

.section-title:first-of-type {
  margin-top: 0;
}

.publish-form {
  max-width: 900px;
}

.w-320 {
  width: 320px;
}

.w-480 {
  width: 480px;
}

.tip-text {
  margin: 0 var(--ct-space-3) 0 var(--ct-space-4);
  color: var(--ct-text-muted);
  font-size: var(--ct-text-sm);
}

.upload-area {
  display: flex;
  gap: var(--ct-space-5);
  align-items: flex-start;
  flex-wrap: wrap;
}

.upload-plus {
  font-size: var(--ct-text-2xl);
  color: var(--ct-text-muted);
  line-height: 1;
}

.upload-tip {
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
  line-height: 1.8;
  max-width: 320px;
}

.upload-error {
  margin: var(--ct-space-2) 0 0;
  color: var(--ct-cat-6-bar);
  font-weight: var(--ct-weight-medium);
}

.placeholder-preview {
  width: 96px;
  height: 96px;
  border-radius: var(--ct-radius-sm);
  border: var(--ct-hairline) dashed var(--ct-border);
  margin-top: var(--ct-space-1);
}
</style>
