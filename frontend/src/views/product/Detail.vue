<script setup>
/**
 * 商品详情页（v0.05）
 *
 * 展示：商品大图（可放大预览）、描述、价格、分类、发布者信息、浏览量等。
 * 图片：优先 product_image 中的图片；没有则用封面；都没有则按分类显示占位图。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProductDetail } from '@/api/product'
import { checkFavorite, operateFavorite } from '@/api/favorite'
import { createOrder } from '@/api/order'
import { useUserStore } from '@/store/user'
import { conditionLabel, formatPrice, PRODUCT_STATUS, resolveDetailImages } from '@/utils/product'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const notFound = ref(false)
const product = ref(null)

/* ---------------- v0.06 收藏状态 ---------------- */
const favorited = ref(false)
const favoriteCount = ref(0)
const favoriteLoading = ref(false)

/* ---------------- v0.07 私聊卖家 ---------------- */

/** 是否是自己的商品（自己的商品不能私聊自己） */
const isSelfProduct = computed(() => !!product.value && product.value.sellerId === userStore.userInfo?.id)

/** 私聊卖家：未登录先去登录，登录后进入聊天窗口并带上当前商品 */
function goChatWithSeller() {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录后再私聊卖家')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  router.push({
    path: `/chat/${product.value.sellerId}`,
    query: { productId: product.value.id }
  })
}

/* ---------------- v0.08 下单 ---------------- */
const orderDialogVisible = ref(false)
const orderSubmitting = ref(false)
const orderForm = reactive({ tradePlace: '', buyerRemark: '' })

/** 打开下单确认框（先做前端预校验，后端还会再校验一次） */
function openOrderDialog() {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录后再下单')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (isSelfProduct.value) {
    ElMessage.warning('不能购买自己发布的商品')
    return
  }
  if (product.value.status !== 1) {
    ElMessage.warning('该商品已下架或已被预订，无法下单')
    return
  }
  orderForm.tradePlace = product.value.tradePlace || ''
  orderForm.buyerRemark = ''
  orderDialogVisible.value = true
}

/** 提交订单 → 跳到订单详情页 */
async function submitOrder() {
  orderSubmitting.value = true
  try {
    const res = await createOrder({
      productId: product.value.id,
      deliveryType: 1,
      tradePlace: orderForm.tradePlace,
      buyerRemark: orderForm.buyerRemark
    })
    ElMessage.success('下单成功，请与卖家约定面交时间')
    orderDialogVisible.value = false
    router.push(`/orders/${res.data.id}`)
  } catch (e) {
    /* 商品已下架(6001)/不能买自己的商品(6002) 等由拦截器统一提示 */
  } finally {
    orderSubmitting.value = false
  }
}

const images = computed(() => (product.value ? resolveDetailImages(product.value) : []))
const statusInfo = computed(() => PRODUCT_STATUS[product.value?.status] || { label: '未知', type: 'info' })

async function loadDetail() {
  loading.value = true
  notFound.value = false
  try {
    const res = await getProductDetail(route.params.id)
    product.value = res.data
    favoriteCount.value = res.data.favoriteCount || 0
    // 已登录才查询自己的收藏状态（未登录不请求，后端接口需要 Token）
    if (userStore.isLogin) {
      loadFavoriteState()
    }
  } catch (e) {
    // 后端返回 3001「商品不存在或已被删除」，这里展示空状态
    notFound.value = true
    product.value = null
  } finally {
    loading.value = false
  }
}

/* ---------------- v0.06 收藏 ---------------- */

/** 查询当前用户是否已收藏该商品 */
async function loadFavoriteState() {
  try {
    const res = await checkFavorite(route.params.id)
    favorited.value = res.data.favorited
    favoriteCount.value = res.data.favoriteCount
  } catch (e) {
    /* 未登录或接口异常时忽略，不影响详情展示 */
  }
}

/** 点击收藏按钮：未登录先去登录，已登录则切换收藏状态 */
async function toggleFavorite() {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录后再收藏')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  favoriteLoading.value = true
  try {
    // type：1 收藏，2 取消收藏
    const res = await operateFavorite({
      productId: Number(route.params.id),
      type: favorited.value ? 2 : 1
    })
    favorited.value = res.data.favorited
    favoriteCount.value = res.data.favoriteCount
    ElMessage.success(res.message || (favorited.value ? '收藏成功' : '已取消收藏'))
  } catch (e) {
    // 4001 重复收藏 / 4002 收藏自己的商品 等错误由 axios 拦截器统一提示
  } finally {
    favoriteLoading.value = false
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
              <el-descriptions-item label="收藏量">{{ favoriteCount }} 人收藏</el-descriptions-item>
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
              <!-- v0.06 收藏按钮：点击切换 收藏 / 已收藏 -->
              <el-button
                :type="favorited ? 'warning' : 'danger'"
                :plain="favorited"
                :loading="favoriteLoading"
                @click="toggleFavorite"
              >
                {{ favorited ? '★ 已收藏' : '☆ 收藏' }}（{{ favoriteCount }}）
              </el-button>

              <el-tooltip v-if="isSelfProduct" content="这是你自己发布的商品" placement="top">
                <span><el-button disabled>私聊卖家</el-button></span>
              </el-tooltip>
              <!-- v0.07 私聊卖家：进入聊天窗口并带上当前商品 -->
              <el-button v-else type="primary" @click="goChatWithSeller">私聊卖家</el-button>

              <!-- v0.08 下单：弹出确认框（交易地点 + 备注），创建订单后跳订单详情 -->
              <el-tooltip v-if="product.status !== 1" content="商品已下架或已被预订，无法下单" placement="top">
                <span><el-button type="danger" disabled>立即购买</el-button></span>
              </el-tooltip>
              <el-button v-else type="danger" @click="openOrderDialog">立即购买</el-button>
              <el-button text type="primary" @click="router.push('/home')">← 返回商品列表</el-button>
            </div>
          </el-col>
        </el-row>

        <!-- 商品描述 -->
        <el-divider content-position="left">商品描述</el-divider>
        <p class="description">{{ product.description || '卖家很懒，没有填写描述。' }}</p>
      </el-card>

      <!-- v0.08 下单确认框 -->
      <el-dialog v-model="orderDialogVisible" title="确认下单" width="480px">
        <el-form label-width="86px">
          <el-form-item label="商品">
            <span>{{ product.title }}</span>
          </el-form-item>
          <el-form-item label="成交金额">
            <span class="dialog-price">￥{{ Number(product.price).toFixed(2) }}</span>
          </el-form-item>
          <el-form-item label="交易地点">
            <el-input v-model="orderForm.tradePlace" maxlength="100" placeholder="例如：东校区图书馆门口" />
          </el-form-item>
          <el-form-item label="买家备注">
            <el-input
              v-model="orderForm.buyerRemark"
              type="textarea"
              :rows="3"
              maxlength="255"
              show-word-limit
              placeholder="例如：明天下午三点方便面交吗"
            />
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="下单后商品会被锁定为「交易中」，其他同学无法再购买；取消订单后商品自动重新上架。"
        />
        <template #footer>
          <el-button @click="orderDialogVisible = false">再想想</el-button>
          <el-button type="danger" :loading="orderSubmitting" @click="submitOrder">确认下单</el-button>
        </template>
      </el-dialog>
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

.dialog-price {
  color: #f56c6c;
  font-weight: 700;
  font-size: 16px;
}
</style>
