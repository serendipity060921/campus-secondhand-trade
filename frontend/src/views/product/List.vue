<script setup>
/**
 * 商品列表页（v0.05，作为首页）
 *
 * 功能：关键词搜索 + 分类筛选 + 排序 + 分页；卡片展示图片、名称、价格、发布时间。
 * 图片：优先用商品真实封面；没有封面（或加载失败）时按分类显示统一的占位图。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCategoryList, getProductList } from '@/api/product'
import { demoImage, formatPrice, resolveImageUrl } from '@/utils/product'
// v0.11 推荐模块：首页"猜你喜欢"面板（自拉数据，不影响本页既有逻辑）
import RecommendPanel from '@/components/RecommendPanel.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const products = ref([])
const categories = ref([])
const total = ref(0)
/** 记录加载失败的图片，失败时回退到占位图 */
const failedImages = reactive(new Set())

const query = reactive({
  page: 1,
  size: 12,
  keyword: '',
  categoryId: null,
  sort: 'new'
})

const sortOptions = [
  { value: 'new', label: '最新发布' },
  { value: 'priceAsc', label: '价格从低到高' },
  { value: 'priceDesc', label: '价格从高到低' },
  { value: 'hot', label: '最多浏览' }
]

/** 分类下拉：按一级分类分组 */
const categoryGroups = computed(() => {
  const tops = categories.value.filter((c) => !c.parentId || c.parentId === 0)
  return tops.map((top) => ({
    label: top.name,
    options: [
      { id: top.id, name: `${top.name}（全部）` },
      ...categories.value.filter((c) => c.parentId === top.id)
    ]
  }))
})

function imageOf(product) {
  if (failedImages.has(product.id)) {
    return demoImage(product.categoryName)
  }
  return product.coverImage ? resolveImageUrl(product.coverImage) : demoImage(product.categoryName)
}

function onImageError(product) {
  failedImages.add(product.id)
}

async function loadCategories() {
  try {
    const res = await getCategoryList()
    categories.value = res.data || []
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function loadProducts() {
  loading.value = true
  try {
    const res = await getProductList({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      categoryId: query.categoryId || undefined,
      sort: query.sort
    })
    products.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    products.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadProducts()
}

function handleReset() {
  query.keyword = ''
  query.categoryId = null
  query.sort = 'new'
  query.page = 1
  loadProducts()
}

function handlePageChange(page) {
  query.page = page
  loadProducts()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function goDetail(id) {
  router.push(`/product/${id}`)
}

/** 发布时间只显示到分钟 */
function shortTime(time) {
  return time ? String(time).slice(0, 16) : ''
}

onMounted(() => {
  // v0.09：支持 /home?keyword=xxx&categoryId=1 深度链接，首页可直接按关键词/分类筛选
  if (route.query.keyword) {
    query.keyword = String(route.query.keyword)
  }
  if (route.query.categoryId) {
    query.categoryId = Number(route.query.categoryId)
  }
  loadCategories()
  loadProducts()
})
</script>

<template>
  <div>
    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索教材、数码、生活用品…"
          clearable
          class="keyword"
          @keyup.enter="handleSearch"
        />

        <el-select v-model="query.categoryId" placeholder="全部分类" clearable class="category" @change="handleSearch">
          <el-option-group v-for="group in categoryGroups" :key="group.label" :label="group.label">
            <el-option v-for="item in group.options" :key="item.id" :label="item.name" :value="item.id" />
          </el-option-group>
        </el-select>

        <el-select v-model="query.sort" class="sort" @change="handleSearch">
          <el-option v-for="item in sortOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button @click="handleReset">重置</el-button>

        <el-button type="success" plain class="publish-btn" @click="router.push('/product/publish')">
          + 发布闲置
        </el-button>
      </div>
    </el-card>

    <!-- 商品卡片列表 -->
    <div v-loading="loading" class="product-area">
      <el-empty v-if="!loading && products.length === 0" description="暂时没有符合条件的商品，换个关键词试试～" />

      <el-row v-else :gutter="16">
        <el-col v-for="item in products" :key="item.id" :xs="12" :sm="8" :md="6" :lg="6">
          <el-card class="product-card" shadow="hover" :body-style="{ padding: '0' }" @click="goDetail(item.id)">
            <div class="cover">
              <img :src="imageOf(item)" alt="商品图片" @error="onImageError(item)" />
              <el-tag v-if="item.conditionLevel" class="condition" size="small" effect="dark" type="info">
                {{ ['', '全新', '几乎全新', '轻微使用', '明显使用'][item.conditionLevel] }}
              </el-tag>
            </div>
            <div class="info">
              <div class="title" :title="item.title">{{ item.title }}</div>
              <div class="price-row">
                <span class="price">{{ formatPrice(item.price) }}</span>
                <span v-if="item.originalPrice" class="origin">{{ formatPrice(item.originalPrice) }}</span>
              </div>
              <div class="meta">
                <el-tag size="small" effect="plain">{{ item.categoryName || '未分类' }}</el-tag>
                <span class="time">{{ shortTime(item.createTime) }}</span>
              </div>
              <div class="meta second">
                <span class="seller">卖家：{{ item.sellerNickname || '匿名' }}</span>
                <span class="views">{{ item.viewCount || 0 }} 次浏览</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <div v-if="total > 0" class="pagination">
        <el-pagination
          background
          layout="total, prev, pager, next, jumper"
          :total="total"
          :current-page="query.page"
          :page-size="query.size"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <!-- v0.11 推荐模块：猜你喜欢（登录后个性化，未登录热门冷启动） -->
    <RecommendPanel mode="personal" title="猜你喜欢" :size="8" />
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.keyword {
  width: 260px;
}

.category {
  width: 220px;
}

.sort {
  width: 160px;
}

.publish-btn {
  margin-left: auto;
}

.product-area {
  min-height: 300px;
}

.product-card {
  margin-bottom: 16px;
  cursor: pointer;
  transition: transform 0.15s ease;
}

.product-card:hover {
  transform: translateY(-3px);
}

.cover {
  position: relative;
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

.condition {
  position: absolute;
  top: 8px;
  left: 8px;
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
}

.meta.second {
  margin-top: 4px;
}

.pagination {
  display: flex;
  justify-content: center;
  margin: 8px 0 24px;
}
</style>
