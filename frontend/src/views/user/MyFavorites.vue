<script setup>
/**
 * 我的收藏页（v0.06，需要登录）
 *
 * 数据来源：GET /api/favorite/list（只返回上架商品，favorite + product 关联分页）
 * 功能：分页展示收藏商品、点击卡片进详情、直接取消收藏。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyFavorites, operateFavorite } from '@/api/favorite'
import { demoImage, formatPrice, resolveImageUrl } from '@/utils/product'
import StateError from '@/components/states/StateError.vue'
import SkeletonList from '@/components/states/SkeletonList.vue'

const router = useRouter()

const loading = ref(false)
const loadError = ref(false)
const favorites = ref([])
const total = ref(0)
const failedImages = reactive(new Set())

const query = reactive({ page: 1, size: 12 })

function imageOf(item) {
  if (failedImages.has(item.productId)) {
    return demoImage(item.categoryName)
  }
  return item.coverImage ? resolveImageUrl(item.coverImage) : demoImage(item.categoryName)
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const res = await getMyFavorites({ page: query.page, size: query.size })
    favorites.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    favorites.value = []
    total.value = 0
    loadError.value = true
    } finally {
    loading.value = false
  }
}

async function cancelFavorite(item) {
  try {
    await ElMessageBox.confirm(`确定取消收藏「${item.title}」吗？`, '取消收藏', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    const res = await operateFavorite({ productId: item.productId, type: 2 })
    ElMessage.success(res.message || '已取消收藏')
    // 取消后如果当前页空了，自动回退一页
    if (favorites.value.length === 1 && query.page > 1) {
      query.page -= 1
    }
    load()
  } catch (e) {
    /* 错误提示由拦截器统一处理 */
  }
}

function goDetail(productId) {
  router.push(`/product/${productId}`)
}

function handlePageChange(page) {
  query.page = page
  load()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(load)
</script>

<template>
  <StateError
    v-if="loadError && !loading"
    title="加载失败，请稍后重试"
    detail="网络可能不稳定，或服务正在重启"
    retry-text="重新加载"
    @retry="load"
  />
  <div v-else>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <h1 class="card-title">我的收藏</h1>
          <span class="text-muted">共 {{ total }} 件（仅显示当前在售商品）</span>
        </div>
      </template>

      <!-- v0.16：加载态由转圈遮罩改为版式对齐的骨架屏（媒体块 + 三行信息，与卡片同形） -->
      <SkeletonList v-if="loading" :count="8" :columns="4" />

      <el-empty v-else-if="favorites.length === 0" description="还没有收藏任何商品，去首页逛逛吧～">
        <el-button type="primary" @click="router.push('/home')">去逛商品</el-button>
      </el-empty>

      <el-row v-else :gutter="16">
        <el-col v-for="item in favorites" :key="item.favoriteId" :xs="12" :sm="8" :md="6">
          <el-card
            class="fav-card"
            shadow="hover"
            :body-style="{ padding: '0' }"
            role="link"
            tabindex="0"
            :aria-label="`${item.title}，${formatPrice(item.price)}`"
            @click="goDetail(item.productId)"
            @keydown.enter.prevent="goDetail(item.productId)"
            @keydown.space.prevent="goDetail(item.productId)"
          >
            <div class="cover">
              <img :src="imageOf(item)" :alt="item.title || '商品图片'"
                   @error="failedImages.add(item.productId)" />
            </div>
            <div class="info">
              <div class="title" :title="item.title">{{ item.title }}</div>
              <div class="price-row">
                <span class="price">{{ formatPrice(item.price) }}</span>
                <span v-if="item.originalPrice" class="origin">{{ formatPrice(item.originalPrice) }}</span>
              </div>
              <div class="meta">
                <el-tag size="small" effect="plain">{{ item.categoryName || '未分类' }}</el-tag>
                <span class="seller">卖家：{{ item.sellerNickname || '匿名' }}</span>
              </div>
              <div class="meta">
                <span class="text-muted">收藏于 {{ item.favoriteTime }}</span>
              </div>
              <el-button
                class="cancel-btn"
                size="small"
                type="warning"
                plain
                @click.stop="cancelFavorite(item)"
              >
                取消收藏
              </el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <div v-if="total > query.size" class="pagination">
        <el-pagination
          background
          layout="total, prev, pager, next, jumper"
          :total="total"
          :current-page="query.page"
          :page-size="query.size"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.fav-card {
  margin-bottom: 16px;
  cursor: pointer;
  transition: transform 0.15s ease;
}

.fav-card:hover {
  transform: translateY(-3px);
}

.cover {
  width: 100%;
  aspect-ratio: 1 / 1;
  background: var(--ct-bg-subtle);
  overflow: hidden;
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.info {
  padding: 10px 12px 14px;
}

.title {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  height: 40px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.price-row {
  margin-top: 6px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.price {
  color: var(--ct-price);
  font-size: 18px;
  font-weight: 700;
}

.origin {
  color: var(--ct-text-muted);
  font-size: 12px;
  text-decoration: line-through;
}

.meta {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ct-text-muted);
  gap: 6px;
}

.cancel-btn {
  margin-top: 10px;
  width: 100%;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

/* 卡片头里的页级主标题（原为 <b>，现为 h1）：保持与原先一致的视觉重量 */
.card-title {
  margin: 0;
  font-size: var(--ct-text-md);
  font-weight: var(--ct-weight-semibold);
}
</style>