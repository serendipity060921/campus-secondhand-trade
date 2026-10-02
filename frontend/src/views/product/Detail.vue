<script setup>
/**
 * 商品详情页（v0.05）
 *
 * 展示：商品大图（可放大预览）、描述、价格、分类、发布者信息、浏览量等。
 * 图片：优先 product_image 中的图片；没有则用封面；都没有则按分类显示占位图。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProductDetail } from '@/api/product'
import { conditionLabel, formatPrice, PRODUCT_STATUS, resolveDetailImages } from '@/utils/product'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const notFound = ref(false)
const product = ref(null)

const images = computed(() => (product.value ? resolveDetailImages(product.value) : []))
const statusInfo = computed(() => PRODUCT_STATUS[product.value?.status] || { label: '未知', type: 'info' })

async function loadDetail() {
  loading.value = true
  notFound.value = false
  try {
    const res = await getProductDetail(route.params.id)
    product.value = res.data
  } catch (e) {
    // 后端返回 3001「商品不存在或已被删除」，这里展示空状态
    notFound.value = true
    product.value = null
  } finally {
    loading.value = false
  }
}

onMounted(loadDetail)
</script>

<template>
  <div v-loading="loading">
    <el-result
      v-if="notFound"
      icon="warning"
      title="商品不存在或已被删除"
      sub-title="它可能已被卖家删除，或者链接不正确"
    >
      <template #extra>
        <el-button type="primary" @click="router.push('/home')">返回首页</el-button>
      </template>
    </el-result>

    <template v-else-if="product">
      <el-page-header class="page-header" @back="router.back()">
        <template #content>
          <span class="header-title">商品详情</span>
        </template>
      </el-page-header>

      <el-card shadow="never">
        <el-row :gutter="24">
          <!-- 左侧图片 -->
          <el-col :xs="24" :sm="10">
            <el-image
              v-if="images.length"
              class="main-image"
              :src="images[0]"
              :preview-src-list="images"
              fit="cover"
              :initial-index="0"
              preview-teleported
            />
            <div v-if="images.length > 1" class="thumbs">
              <el-image
                v-for="(img, index) in images"
                :key="index"
                class="thumb"
                :src="img"
                fit="cover"
                :preview-src-list="images"
                :initial-index="index"
                preview-teleported
              />
            </div>
          </el-col>

          <!-- 右侧信息 -->
          <el-col :xs="24" :sm="14">
            <h2 class="title">{{ product.title }}</h2>

            <div class="price-box">
              <span class="price">{{ formatPrice(product.price) }}</span>
              <span v-if="product.originalPrice" class="origin">原价 {{ formatPrice(product.originalPrice) }}</span>
              <el-tag :type="statusInfo.type" size="small" effect="dark">{{ statusInfo.label }}</el-tag>
            </div>

            <div class="tags">
              <el-tag effect="plain">分类：{{ product.categoryName || '未分类' }}</el-tag>
              <el-tag effect="plain" type="warning">成色：{{ conditionLabel(product.conditionLevel) }}</el-tag>
              <el-tag v-if="product.campus" effect="plain" type="info">校区：{{ product.campus }}</el-tag>
            </div>

            <el-descriptions class="mt-16" :column="1" border size="small">
              <el-descriptions-item label="交易地点">{{ product.tradePlace || '面议' }}</el-descriptions-item>
              <el-descriptions-item label="发布时间">{{ product.createTime }}</el-descriptions-item>
              <el-descriptions-item label="浏览量">{{ product.viewCount }} 次</el-descriptions-item>
            </el-descriptions>

            <!-- 发布者信息 -->
            <el-card class="seller-card" shadow="never">
              <div class="seller">
                <el-avatar :size="46">{{ (product.sellerNickname || '匿').slice(0, 1) }}</el-avatar>
                <div class="seller-info">
                  <div class="seller-name">{{ product.sellerNickname || '匿名用户' }}</div>
                  <div class="seller-meta">
                    <span>信用分：{{ product.sellerCreditScore ?? '-' }}</span>
                    <span v-if="product.sellerCampus"> · {{ product.sellerCampus }}</span>
                  </div>
                </div>
              </div>
            </el-card>

            <div class="actions">
              <el-tooltip content="留言/私信功能将在 v0.06 里程碑开放" placement="top">
                <span><el-button disabled>联系卖家</el-button></span>
              </el-tooltip>
              <el-tooltip content="下单功能将在订单模块里程碑开放" placement="top">
                <span><el-button type="danger" disabled>立即购买</el-button></span>
              </el-tooltip>
              <el-button text type="primary" @click="router.push('/home')">← 返回商品列表</el-button>
            </div>
          </el-col>
        </el-row>

        <!-- 商品描述 -->
        <el-divider content-position="left">商品描述</el-divider>
        <p class="description">{{ product.description || '卖家很懒，没有填写描述。' }}</p>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 16px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.main-image {
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: 8px;
  background: #f7f9fc;
}

.thumbs {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.thumb {
  width: 62px;
  height: 62px;
  border-radius: 6px;
  border: 1px solid #ebeef5;
  cursor: pointer;
}

.title {
  margin: 0 0 12px;
  font-size: 20px;
  line-height: 1.4;
}

.price-box {
  display: flex;
  align-items: baseline;
  gap: 12px;
  background: #fff6f6;
  padding: 12px 16px;
  border-radius: 8px;
}

.price {
  color: #f56c6c;
  font-size: 30px;
  font-weight: 700;
}

.origin {
  color: #a8abb2;
  font-size: 13px;
  text-decoration: line-through;
}

.tags {
  margin-top: 14px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.mt-16 {
  margin-top: 16px;
}

.seller-card {
  margin-top: 16px;
  background: #f7f9fc;
}

.seller {
  display: flex;
  align-items: center;
  gap: 12px;
}

.seller-name {
  font-weight: 600;
}

.seller-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.actions {
  margin-top: 18px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.description {
  margin: 0;
  line-height: 1.9;
  white-space: pre-wrap;
  color: #303133;
}
</style>
