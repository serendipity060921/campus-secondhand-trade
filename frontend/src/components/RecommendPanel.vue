<script setup>
/**
 * 推荐面板组件（v0.11）
 *
 * 两种用法：
 *   <RecommendPanel mode="personal" title="猜你喜欢" :size="8" />          首页个性化推荐
 *   <RecommendPanel mode="similar" :product-id="id" title="相关推荐" :size="4" />  详情页相似商品
 *
 * 特点：
 *   ① 自拉数据，父页面只需一行标签，避免改动既有页面逻辑；
 *   ② 展示算法可解释信息（来源标签 + 推荐理由），让用户知道"为什么推荐给我"；
 *   ③ 无数据时整块隐藏，不影响页面布局。
 */
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getRecommend, getSimilar } from '@/api/recommend'
import { demoImage, formatPrice, resolveImageUrl } from '@/utils/product'
import StateError from '@/components/states/StateError.vue'

const props = defineProps({
  mode: { type: String, default: 'personal' },      // personal | similar
  productId: { type: [Number, String], default: null },
  title: { type: String, default: '猜你喜欢' },
  size: { type: Number, default: 8 }
})

const router = useRouter()
const loading = ref(false)
const loadError = ref(false)
const items = ref([])
const result = ref(null)
const failed = ref(new Set())

/** 来源类型 -> 标签颜色（协同过滤/内容匹配/热门/混合） */
const tagType = (sourceType) => {
  switch (sourceType) {
    case 1: return 'success'
    case 2: return 'warning'
    case 4: return 'primary'
    default: return 'info'
  }
}

function imageOf(item) {
  if (failed.value.has(item.productId)) {
    return demoImage(item.categoryName)
  }
  return item.coverImage ? resolveImageUrl(item.coverImage) : demoImage(item.categoryName)
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const res = props.mode === 'similar'
      ? await getSimilar(props.productId, { size: props.size })
      : await getRecommend({ size: props.size })
    result.value = res.data
    items.value = res.data?.items || []
  } catch (e) {
    items.value = []
    loadError.value = true
    } finally {
    loading.value = false
  }
}

onMounted(load)
// 详情页切换到另一件商品时重新拉取相关推荐
watch(() => props.productId, (v) => {
  if (v && props.mode === 'similar') {
    load()
  }
})
</script>

<template>
  <StateError
    v-if="loadError && !loading"
    title="加载失败，请稍后重试"
    detail="网络可能不稳定，或服务正在重启"
    retry-text="重新加载"
    @retry="load"
  />
  <div v-else v-if="loading || items.length" class="recommend-panel" v-loading="loading">
    <div class="panel-header">
      <span class="panel-title">
        <el-icon><Star /></el-icon>
        {{ title }}
      </span>
      <span class="panel-sub">
        <template v-if="result?.personalized">
          推荐策略：{{ result.strategyLabel }}
          <template v-if="result.profileDesc"> · 画像：{{ result.profileDesc }}</template>
        </template>
        <template v-else-if="result">{{ result.strategyLabel }}</template>
      </span>
    </div>

    <el-row :gutter="16">
      <el-col v-for="item in items" :key="item.productId" :xs="12" :sm="8" :md="6" :lg="6">
        <el-card class="rec-card" shadow="hover" :body-style="{ padding: '0' }"
                 @click="router.push(`/product/${item.productId}`)">
          <div class="cover">
            <img :src="imageOf(item)" :alt="item.title" @error="failed.add(item.productId)" />
          </div>
          <div class="info">
            <div class="title" :title="item.title">{{ item.title }}</div>
            <div class="price-row">
              <span class="price">{{ formatPrice(item.price) }}</span>
              <span v-if="item.originalPrice" class="origin">{{ formatPrice(item.originalPrice) }}</span>
            </div>
            <div class="reason-row">
              <el-tag size="small" effect="plain" :type="tagType(item.sourceType)">
                {{ item.sourceLabel || '推荐' }}
              </el-tag>
              <span class="reason" :title="(item.reasons || []).join('；')">{{ item.reason }}</span>
            </div>
            <div class="meta">
              <span>{{ item.categoryName || '未分类' }}</span>
              <span>{{ item.viewCount || 0 }} 次浏览</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.recommend-panel {
  margin-top: 18px;
}

.panel-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
  padding-left: 10px;
  border-left: var(--ct-hairline) solid var(--ct-border);
}

.panel-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--ct-text-primary);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.panel-sub {
  font-size: 12px;
  color: var(--ct-text-muted);
}

.rec-card {
  margin-bottom: 16px;
  cursor: pointer;
  transition: transform 0.15s ease;
}

.rec-card:hover {
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
  padding: 10px 12px 12px;
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
  font-size: 17px;
  font-weight: 700;
}

.origin {
  color: var(--ct-text-muted);
  font-size: 12px;
  text-decoration: line-through;
}

.reason-row {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
}

.reason {
  font-size: 12px;
  color: var(--el-color-warning);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.meta {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ct-text-muted);
}
</style>
