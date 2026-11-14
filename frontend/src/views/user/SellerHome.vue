<script setup>
/**
 * 卖家主页（v0.19）
 *
 * 学生建议："点卖家头像可以看他以前卖出的商品，然后商品有评价" —— 这是二手交易的信任基础：
 * 买家在决定交易前，应能查看对方的历史成交与口碑。
 *
 * 数据来源（均为已有接口，未新增后端）：
 *   · GET /user/{id}/public             昵称 / 头像 / 信用分 / 校区 / 注册时间
 *   · GET /product/list?sellerId=&status=  该卖家的 在售(1) / 交易中(4) / 已售出(5)
 *   · GET /evaluation/received/{id}     该卖家收到的评价（含商品名与星级）
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/api/request'
import { getReceivedEvaluations } from '@/api/evaluation'
import { resolveImageUrl } from '@/utils/product'

const route = useRoute()
const router = useRouter()

const sellerId = computed(() => Number(route.params.id))
const seller = ref(null)
const loading = ref(true)
const activeTab = ref('onSale')

const onSale = ref([])
const trading = ref([])
const sold = ref([])
const evaluations = ref([])
const evalTotal = ref(0)

const stats = computed(() => ({
  onSale: onSale.value.length,
  sold: sold.value.length,
  reviews: evalTotal.value,
}))
const avgRating = computed(() => {
  if (!evaluations.value.length) return null
  const sum = evaluations.value.reduce((s, e) => s + (e.rating || 0), 0)
  return (sum / evaluations.value.length).toFixed(1)
})
const goodRate = computed(() => {
  if (!evaluations.value.length) return null
  const good = evaluations.value.filter((e) => e.rating >= 4).length
  return Math.round((good / evaluations.value.length) * 100)
})
const avatarUrl = computed(() => (seller.value?.avatar ? resolveImageUrl(seller.value.avatar) : ''))
const avatarText = computed(() => (seller.value?.nickname || '卖').slice(0, 1))

async function loadProducts() {
  const [a, b, c] = await Promise.all([
    request({ url: '/product/list', method: 'get', params: { sellerId: sellerId.value, status: 1, size: 20 } }),
    request({ url: '/product/list', method: 'get', params: { sellerId: sellerId.value, status: 4, size: 20 } }),
    request({ url: '/product/list', method: 'get', params: { sellerId: sellerId.value, status: 5, size: 20 } }),
  ])
  onSale.value = a?.data?.records || []
  trading.value = b?.data?.records || []
  sold.value = c?.data?.records || []
}

async function loadAll() {
  loading.value = true
  try {
    const info = await request({ url: `/user/${sellerId.value}/public`, method: 'get' })
    seller.value = info?.data || null
    await loadProducts()
    const ev = await getReceivedEvaluations(sellerId.value, { page: 1, size: 20 })
    evaluations.value = ev?.data?.records || []
    evalTotal.value = ev?.data?.total || 0
  } catch (e) {
    ElMessage.error('卖家信息加载失败')
  } finally {
    loading.value = false
  }
}

function openProduct(id) {
  router.push(`/product/${id}`)
}

watch(() => route.params.id, () => {
  if (route.params.id) loadAll()
})
onMounted(loadAll)
</script>

<template>
  <div v-loading="loading" class="seller-home">
    <!-- 头部：身份与信誉 -->
    <el-card shadow="never" class="head">
      <div class="head-inner">
        <el-avatar :size="72" :src="avatarUrl || undefined">{{ avatarText }}</el-avatar>
        <div class="ident">
          <div class="name-row">
            <h2>{{ seller?.nickname || '卖家' }}</h2>
            <el-tag v-if="seller?.campus" size="small" effect="plain">{{ seller.campus }}</el-tag>
          </div>
          <div class="meta">
            <span class="credit">信用分 <b>{{ seller?.creditScore ?? '—' }}</b></span>
            <span v-if="seller?.createTime" class="join">加入于 {{ String(seller.createTime).slice(0, 10) }}</span>
          </div>
          <div class="reputation">
            <template v-if="avgRating">
              <el-rate :model-value="Number(avgRating)" disabled allow-half size="small" />
              <span class="num">{{ avgRating }}</span>
              <span class="muted">好评率 {{ goodRate }}% · {{ stats.reviews }} 条评价</span>
            </template>
            <span v-else class="muted">暂无评价</span>
          </div>
        </div>
        <div class="stats">
          <div class="stat"><b>{{ stats.onSale }}</b><span>在售</span></div>
          <div class="stat"><b>{{ stats.sold }}</b><span>已售出</span></div>
          <div class="stat"><b>{{ stats.reviews }}</b><span>收到评价</span></div>
        </div>
      </div>
    </el-card>

    <!-- 商品与评价 -->
    <el-card shadow="never" class="body">
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="`在售 (${onSale.length})`" name="onSale">
          <el-empty v-if="!onSale.length" description="这个卖家暂时没有在售商品" />
          <div v-else class="grid">
            <div v-for="p in onSale" :key="p.id" class="item" @click="openProduct(p.id)">
              <el-image :src="resolveImageUrl(p.coverImage)" fit="cover" class="cover" lazy />
              <div class="t">{{ p.title }}</div>
              <div class="p">￥{{ Number(p.price).toFixed(2) }}</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`交易中 (${trading.length})`" name="trading">
          <el-empty v-if="!trading.length" description="没有正在交易的商品" />
          <div v-else class="grid">
            <div v-for="p in trading" :key="p.id" class="item" @click="openProduct(p.id)">
              <el-image :src="resolveImageUrl(p.coverImage)" fit="cover" class="cover" lazy />
              <div class="t">{{ p.title }}</div>
              <div class="p">￥{{ Number(p.price).toFixed(2) }}</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`已售出 (${sold.length})`" name="sold">
          <el-empty v-if="!sold.length" description="还没有成交记录" />
          <div v-else class="grid">
            <div v-for="p in sold" :key="p.id" class="item" @click="openProduct(p.id)">
              <el-image :src="resolveImageUrl(p.coverImage)" fit="cover" class="cover" lazy />
              <div class="t">{{ p.title }}</div>
              <div class="p">￥{{ Number(p.price).toFixed(2) }}</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`收到的评价 (${evalTotal})`" name="reviews">
          <el-empty v-if="!evaluations.length" description="还没有收到评价" />
          <div v-else class="reviews">
            <div v-for="e in evaluations" :key="e.id" class="review">
              <div class="r-head">
                <el-avatar :size="26" :src="e.fromAvatar ? resolveImageUrl(e.fromAvatar) : undefined">
                  {{ (e.fromNickname || '匿').slice(0, 1) }}
                </el-avatar>
                <span class="r-name">{{ e.anonymous ? '匿名同学' : e.fromNickname || '同学' }}</span>
                <el-tag size="small" effect="plain">{{ e.roleLabel || '交易对方' }}</el-tag>
                <el-rate :model-value="e.rating" disabled size="small" />
              </div>
              <div class="r-content">{{ e.content || '（未填写文字评价）' }}</div>
              <div class="r-foot">
                <span class="r-product" @click="openProduct(e.productId)">商品：{{ e.productTitle }}</span>
                <span class="muted">{{ String(e.createTime || '').slice(0, 16) }}</span>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.seller-home { max-width: var(--ct-container, 1180px); margin: 0 auto; padding: 20px 16px 48px; }
.head { margin-bottom: 16px; }
.head-inner { display: flex; gap: 20px; align-items: flex-start; }
.ident { flex: 1; min-width: 0; }
.name-row { display: flex; align-items: center; gap: 10px; }
.name-row h2 { margin: 0; font-size: 22px; }
.meta { margin-top: 6px; display: flex; gap: 18px; color: #4a544d; font-size: 13px; }
.credit b { font-size: 16px; color: #1e241f; }
.reputation { margin-top: 10px; display: flex; align-items: center; gap: 8px; }
.reputation .num { font-weight: 600; }
.muted { color: #6b756f; font-size: 13px; }
.stats { display: flex; gap: 28px; }
.stat { text-align: center; }
.stat b { display: block; font-size: 20px; }
.stat span { font-size: 12px; color: #6b756f; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 16px; }
.item { border: 1px solid var(--ct-paper-200, #e2e4dd); border-radius: 10px; overflow: hidden; cursor: pointer; transition: box-shadow .15s; }
.item:hover { box-shadow: 0 4px 14px rgba(0,0,0,.08); }
.item .cover { width: 100%; height: 120px; display: block; }
.item .t { padding: 8px 10px 0; font-size: 14px; line-height: 1.4; height: 40px; overflow: hidden; }
.item .p { padding: 4px 10px 10px; font-weight: 600; }
.reviews { display: flex; flex-direction: column; gap: 14px; }
.review { border: 1px solid var(--ct-paper-200, #e2e4dd); border-radius: 10px; padding: 12px 14px; }
.r-head { display: flex; align-items: center; gap: 8px; }
.r-name { font-weight: 600; }
.r-content { margin: 8px 0 6px; color: #2c352f; line-height: 1.6; }
.r-foot { display: flex; justify-content: space-between; align-items: center; }
.r-product { cursor: pointer; color: #4a544d; font-size: 13px; text-decoration: underline dotted; }
</style>
