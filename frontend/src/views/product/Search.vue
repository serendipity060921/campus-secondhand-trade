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
import { buildClassIndex, classTintVar, shelfCode } from '@/utils/catalogue'
import CatalogueCard from '@/components/catalogue/CatalogueCard.vue'
import StateError from '@/components/states/StateError.vue'
import SkeletonList from '@/components/states/SkeletonList.vue'

const route = useRoute()
const router = useRouter()

/** 分类索引：给搜索结果卡派生索书号与分类浅底色（与首页目录卡同一套规则） */
const classIndex = computed(() => buildClassIndex(categories.value))
function codeOf(item) {
  return shelfCode(item, classIndex.value.byId).code
}
function tintOf(item) {
  return classTintVar(shelfCode(item, classIndex.value.byId).index)
}

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
    <!-- 页面主标题：视觉上由结果统计行承担，这里补一个屏幕阅读器可读的 h1（此前全页无 h1） -->
    <h1 class="sr-only">搜索结果</h1>
    <StateError
      v-if="loadError && !loading"
      title="加载失败，请稍后重试"
      detail="网络可能不稳定，或服务正在重启"
      retry-text="重新加载"
      @retry="loadProducts"
    />
    <div v-else class="result-area">
      <!-- v0.16：加载态改为版式对齐的骨架屏，避免结果出来时的布局跳动 -->
      <SkeletonList v-if="loading" :count="8" :columns="4" />

      <el-empty v-else-if="products.length === 0" description="没有找到符合条件的商品，换个关键词试试～">
        <el-button type="primary" @click="router.push('/home')">返回首页</el-button>
      </el-empty>

      <div v-else class="result-grid">
        <CatalogueCard
          v-for="item in products"
          :key="item.id"
          :item="item"
          :code="codeOf(item)"
          :tint-var="tintOf(item)"
        />
      </div>

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

/* 搜索结果与首页目录卡同一套卡片与栅格 */
.result-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--ct-space-5);
}

@media (max-width: 1100px) {
  .result-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 600px) {
  .result-grid {
    grid-template-columns: 1fr;
  }
}

.result-area {
  min-height: 300px;
}












.pagination {
  display: flex;
  justify-content: center;
  margin: 8px 0 24px;
}
</style>
