<script setup>
/**
 * 商品列表页（首页）· v0.16 按选定稿 A 重构
 *
 * 构图（与 docs/ui-comps/comp-a-home-list.png 一致，区域见 .impeccable/build/spec.json）：
 *   顶栏（FrontLayout） → 分类书标色带（8 段） → 主区：三列目录卡 + 右栏（统计与推荐）
 *
 * 相较 v0.15 的变化：
 *   · 卡片改为独立的 CatalogueCard：整张卡是 router-link（键盘可达）、图 alt 取标题、
 *     **校区上卡**（接口早已返回 campus 但此前未渲染）、4:3 图、分类号 chip
 *   · 筛选栏并入色带：分类点色带、关键词走顶栏搜索（跳 /search）、排序留在目录头
 *   · 加载/错误/空三态接入共享状态组件（此前只有转圈遮罩与默认插图）
 *   · 页面加 h1 与 nav 地标（此前 7/7 页面无 h1）
 *
 * 保留：分页、排序、`/home?keyword=&categoryId=` 深链、推荐模块。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getCategoryList, getProductList } from '@/api/product'
import { buildClassIndex, classTintVar, shelfCode, UNKNOWN_CODE } from '@/utils/catalogue'
import CategoryBand from '@/components/catalogue/CategoryBand.vue'
import CatalogueCard from '@/components/catalogue/CatalogueCard.vue'
import RecommendPanel from '@/components/RecommendPanel.vue'
import SkeletonList from '@/components/states/SkeletonList.vue'
import StateEmpty from '@/components/states/StateEmpty.vue'
import StateError from '@/components/states/StateError.vue'

const route = useRoute()

const loading = ref(false)
const loadError = ref(false)
const products = ref([])
const categories = ref([])
const total = ref(0)

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

/** 分类索引：子分类 id → 顶层分类（含索书号代号与令牌序号） */
const classIndex = computed(() => buildClassIndex(categories.value))
const topCategories = computed(() => classIndex.value.tops)

/**
 * 色带与统计只展示**八个已知分类**。
 * 演示库存在一个 parentId=0 的测试分类「孤儿分类」，它不在分类号映射表里；
 * 把它放进色带会得到一段 ZZ 书标，既不是设计的一部分，也会让色带变成 9 段。
 * 落在未知分类下的商品仍会在卡片上得到 ZZ 书标 —— 数据异常要看得见，但不冒充已知分类。
 */
const bandCategories = computed(() => topCategories.value.filter((t) => t.code !== UNKNOWN_CODE))

function codeOf(item) {
  return shelfCode(item, classIndex.value.byId).code
}

function tintOf(item) {
  const { index } = shelfCode(item, classIndex.value.byId)
  return classTintVar(index)
}

async function loadCategories() {
  try {
    const res = await getCategoryList()
    categories.value = res.data || []
  } catch (e) {
    /* 分类失败不阻断商品列表；拦截器已提示 */
  }
}

async function loadProducts() {
  loading.value = true
  loadError.value = false
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
    loadError.value = true
  } finally {
    loading.value = false
  }
}

/** 色带筛选：再点已选中的分类即取消筛选（等价于旧的"重置"） */
function handleCategory(id) {
  query.categoryId = id
  query.page = 1
  loadProducts()
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

onMounted(() => {
  // v0.09：支持 /home?keyword=xxx&categoryId=1 深链
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
  <div class="catalogue-page">
    <!-- 分类书标色带：页面主色场，同时是分类筛选控件 -->
    <CategoryBand :tops="bandCategories" :active-id="query.categoryId" @select="handleCategory" />

    <div class="catalogue-body">
      <section class="catalogue-list" aria-labelledby="catalogue-heading">
        <div class="catalogue-head">
          <h1 id="catalogue-heading" class="catalogue-title">在售目录</h1>
          <div class="catalogue-tools">
            <span class="catalogue-stats">目录里 {{ total }} 张卡 · {{ bandCategories.length }} 类</span>
            <el-select v-model="query.sort" size="small" class="sort" @change="handleSearch">
              <el-option v-for="item in sortOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </div>
        </div>

        <!-- 加载态：骨架与最终版式对齐，避免数据到达时跳动 -->
        <SkeletonList v-if="loading" :count="9" :columns="3" />

        <StateError
          v-else-if="loadError"
          title="目录加载失败"
          detail="网络可能不稳定，或服务正在重启"
          retry-text="重新加载"
          @retry="loadProducts"
        />

        <template v-else>
          <StateEmpty
            v-if="products.length === 0"
            title="目录里暂时没有符合条件的商品"
            hint="换个分类看看，或者清空筛选条件重新浏览"
            action-text="看看全部商品"
            @action="handleReset"
          />

          <div v-else class="catalogue-grid">
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
        </template>
      </section>

      <aside class="catalogue-rail" aria-label="目录统计与推荐">
        <div class="rail-panel">
          <h2 class="rail-title">目录统计</h2>
          <p class="rail-line">目录里 {{ total }} 张卡 · {{ bandCategories.length }} 类</p>
          <ul class="rail-cats">
            <li v-for="top in bandCategories" :key="top.id">
              <span>{{ top.name }}</span>
              <span class="rail-code">{{ top.code }}</span>
            </li>
          </ul>
        </div>

        <!-- v0.11 推荐模块：登录后个性化，未登录热门冷启动；右栏用竖排小条目版式（选定稿 A） -->
        <RecommendPanel mode="personal" title="猜你喜欢" variant="rail" :size="3" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.catalogue-page {
  padding-top: var(--ct-space-4);
}

/* 主区：目录 9/12 + 右栏 3/12（对齐选定稿 A） */
.catalogue-body {
  display: grid;
  grid-template-columns: 9fr 3fr;
  gap: var(--ct-space-5);
  padding: var(--ct-space-5) 0 var(--ct-space-6);
}

.catalogue-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ct-space-4);
  margin-bottom: var(--ct-space-4);
}

.catalogue-title {
  font-size: var(--ct-text-lg);
  font-weight: var(--ct-weight-semibold);
  margin: 0;
}

.catalogue-tools {
  display: flex;
  align-items: center;
  gap: var(--ct-space-3);
}

.catalogue-stats {
  font-family: var(--ct-font-mono);
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
  font-variant-numeric: tabular-nums;
}

/* 三列目录卡，24px 间距（选定稿 A 的 3 列网格） */
.catalogue-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--ct-space-5);
}

.catalogue-rail {
  display: flex;
  flex-direction: column;
  gap: var(--ct-space-4);
}

.rail-panel {
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
  border-radius: var(--ct-radius-sm);
  padding: var(--ct-space-4);
}

.rail-title {
  font-size: var(--ct-text-sm);
  font-weight: var(--ct-weight-semibold);
  margin: 0 0 var(--ct-space-2);
}

.rail-line {
  margin: 0;
  font-family: var(--ct-font-mono);
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
  font-variant-numeric: tabular-nums;
}

.rail-cats {
  list-style: none;
  margin: var(--ct-space-3) 0 0;
  padding: 0;
  border-top: var(--ct-hairline) solid var(--ct-border);
}

.rail-cats li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ct-space-2);
  padding: var(--ct-space-1) 0;
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
  border-bottom: var(--ct-hairline) solid var(--ct-border);
}

.rail-code {
  font-family: var(--ct-font-mono);
  letter-spacing: 0.06em;
}

.pagination {
  display: flex;
  justify-content: center;
  margin: var(--ct-space-4) 0 var(--ct-space-5);
}

@media (max-width: 1100px) {
  .catalogue-body {
    grid-template-columns: 1fr;
  }

  .catalogue-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 600px) {
  .catalogue-grid {
    grid-template-columns: 1fr;
  }

  .catalogue-head {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--ct-space-2);
  }
}
</style>
