<script setup>
/**
 * 商品搜索 / 筛选结果页（v0.09）
 *
 * 路由：/search?keyword=教材&categoryId=1&sort=new&page=1
 *
 * 说明：
 *   ① 调用 GET /api/product/search（按商品名称模糊查询 + 分类筛选 + 分页）；
 *   ② 关键词、分类、排序、页码都同步到 URL，刷新/分享链接后结果一致；
 *   ③ 首页顶部搜索框与商品列表页的分类下拉都跳到这里。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSearchCategories, searchProducts } from '@/api/search'
import { demoImage, formatPrice, resolveImageUrl } from '@/utils/product'
import StateError from '@/components/states/StateError.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const loadError = ref(false)
const products = ref([])
const categories = ref([])
const total = ref(0)
const failedImages = reactive(new Set())

const query = reactive({
  keyword: '',
  categoryId: null,
  sort: 'new',
  page: 1,
  size: 12
})

const sortOptions = [
  { value: 'new', label: '最新发布' },
  { value: 'priceAsc', label: '价格从低到高' },
  { value: 'priceDesc', label: '价格从高到低' },
  { value: 'hot', label: '最多浏览' }
]

/** 分类下拉：按一级分类分组（与发布页一致） */
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

const currentCategoryName = computed(() => {
  const found = categories.value.find((c) => c.id === query.categoryId)
  return found ? found.name : ''
})

function imageOf(product) {
  if (failedImages.has(product.id)) {
    return demoImage(product.categoryName)
  }
  return product.coverImage ? resolveImageUrl(product.coverImage) : demoImage(product.categoryName)
}

async function loadCategories() {
  try {
    const res = await getSearchCategories()
    categories.value = res.data || []
  } catch (e) {
    /* 忽略 */
  }
}

async function loadProducts() {
  loading.value = true
  loadError.value = false
  try {
    const res = await searchProducts({
      keyword: query.keyword || undefined,
      categoryId: query.categoryId || undefined,
      sort: query.sort,
      page: query.page,
      size: query.size
    })
    products.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    products.value = []
    total.value = 0
    loadError.value = true
    } finally {
    loading.value = false
  }
}

/** 组装要写回 URL 的查询条件 */
function buildQuery() {
  const q = {}
  if (query.keyword) q.keyword = query.keyword
  if (query.categoryId) q.categoryId = query.categoryId
  if (query.sort && query.sort !== 'new') q.sort = query.sort
  if (query.page > 1) q.page = query.page
  return q
}

/**
 * v0.10 缺陷修复（BUG-03）：原来 handleSearch 里既 router.replace 又手动 loadProducts，
 * 会导致 URL 变化时重复请求两次。现在统一为：
 *   URL 有变化 → 交给 watch(route.query) 加载；URL 没变化 → 直接重新查询。
 */
function applyAndReload() {
  const next = buildQuery()
  if (JSON.stringify(next) === JSON.stringify(route.query)) {
    loadProducts()
  } else {
    router.replace({ path: '/search', query: next })
  }
}

function handleSearch() {
  query.page = 1
  applyAndReload()
}

function handleReset() {
  query.keyword = ''
  query.categoryId = null
  query.sort = 'new'
  query.page = 1
  applyAndReload()
}

function handlePageChange(page) {
  query.page = page
  applyAndReload()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/** 从 URL 读取查询条件（进入页面或 URL 变化时） */
function applyUrlQuery() {
  query.keyword = route.query.keyword ? String(route.query.keyword) : ''
  query.categoryId = route.query.categoryId ? Number(route.query.categoryId) : null
  query.sort = route.query.sort ? String(route.query.sort) : 'new'
  query.page = route.query.page ? Number(route.query.page) : 1
}

watch(
  () => route.query,
  () => {
    applyUrlQuery()
    loadProducts()
  }
)

onMounted(() => {
  applyUrlQuery()
  loadCategories()
  loadProducts()
})
</script>

<template>
  <div>
    <!-- 搜索栏 -->
    <el-card shadow="never" class="search-card">
      <div class="search-bar">
        <el-input
          v-model="query.keyword"
          size="large"
          placeholder="搜索商品名称，例如：教材 / 键盘 / 台灯"
          clearable
          maxlength="50"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" size="large" @click="handleSearch">搜索</el-button>
      </div>

      <div class="filter-bar">
        <el-select
          v-model="query.categoryId"
          placeholder="全部分类"
          clearable
          class="category"
          @change="handleSearch"
        >
          <el-option-group v-for="group in categoryGroups" :key="group.label" :label="group.label">
            <el-option v-for="item in group.options" :key="item.id" :label="item.name" :value="item.id" />
          </el-option-group>
        </el-select>

        <el-select v-model="query.sort" class="sort" @change="handleSearch">
          <el-option v-for="item in sortOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-button @click="handleReset">重置条件</el-button>

        <span class="text-muted result-tip">
          共 <b>{{ total }}</b> 件商品
          <template v-if="query.keyword">，关键词「{{ query.keyword }}」</template>
          <template v-if="currentCategoryName">，分类「{{ currentCategoryName }}」</template>
        </span>
      </div>
    </el-card>

    <!-- 结果列表 -->
    <StateError
      v-if="loadError && !loading"
      title="加载失败，请稍后重试"
      detail="网络可能不稳定，或服务正在重启"
      retry-text="重新加载"
      @retry="loadProducts"
    />
    <div v-else v-loading="loading" class="result-area">
      <el-empty v-if="!loading && products.length === 0" description="没有找到符合条件的商品，换个关键词试试～">
        <el-button type="primary" @click="router.push('/home')">返回首页</el-button>
      </el-empty>

      <el-row v-else :gutter="16">
        <el-col v-for="item in products" :key="item.id" :xs="12" :sm="8" :md="6">
          <el-card class="product-card" shadow="hover" :body-style="{ padding: '0' }" @click="router.push(`/product/${item.id}`)">
            <div class="cover">
              <img :src="imageOf(item)" alt="商品图片" @error="failedImages.add(item.id)" />
            </div>
            <div class="info">
              <div class="title" :title="item.title">{{ item.title }}</div>
              <div class="price-row">
                <span class="price">{{ formatPrice(item.price) }}</span>
                <span v-if="item.originalPrice" class="origin">{{ formatPrice(item.originalPrice) }}</span>
              </div>
              <div class="meta">
                <el-tag size="small" effect="plain">{{ item.categoryName || '未分类' }}</el-tag>
                <span class="time">{{ item.createTime ? String(item.createTime).slice(0, 16) : '' }}</span>
              </div>
              <div class="meta second">
                <span>卖家：{{ item.sellerNickname || '匿名' }}</span>
                <span>{{ item.viewCount || 0 }} 次浏览</span>
              </div>
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
    </div>
  </div>
</template>

<style scoped>
.search-card {
  margin-bottom: 16px;
}

.search-bar {
  display: flex;
  gap: 10px;
}

.filter-bar {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.category {
  width: 220px;
}

.sort {
  width: 150px;
}

.result-tip {
  margin-left: auto;
  font-size: 13px;
}

.result-area {
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
