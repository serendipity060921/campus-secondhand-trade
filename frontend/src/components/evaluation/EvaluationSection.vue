<script setup>
/**
 * 商品评价区（v0.18 / 第6批-㉑）
 *
 * 三件事：汇总（平均分/好评率/星级分布）、列表、三态（loading/empty/error）。
 * 视觉只用语义令牌（--ct-*），不写裸色值 —— CI 的 check-tokens.py 会拦。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { getProductEvaluations, getProductEvaluationSummary } from '@/api/evaluation'
import SkeletonList from '@/components/states/SkeletonList.vue'
import StateEmpty from '@/components/states/StateEmpty.vue'
import StateError from '@/components/states/StateError.vue'

const props = defineProps({
  productId: { type: [Number, String], required: true },
  size: { type: Number, default: 5 }
})

const loading = ref(false)
const loadError = ref(false)
const summary = ref(null)
const records = ref([])
const total = ref(0)

const hasData = computed(() => (summary.value?.count || 0) > 0)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [s, list] = await Promise.all([
      getProductEvaluationSummary(props.productId),
      getProductEvaluations(props.productId, { page: 1, size: props.size })
    ])
    summary.value = s.data
    records.value = list.data?.records || []
    total.value = list.data?.total || 0
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

/** 星级分布条宽度（5 星在左），按最高一档归一化，避免全部贴地看不见 */
function barWidth(star) {
  const dist = summary.value?.distribution || {}
  const max = Math.max(1, ...Object.values(dist).map(Number))
  const val = Number(dist[star] || 0)
  return `${Math.round((val / max) * 100)}%`
}

function tagText(item) {
  return (item.tagList || []).join(' · ')
}

onMounted(load)
watch(() => props.productId, load)
defineExpose({ reload: load })
</script>

<template>
  <section class="eval-section">
    <header class="eval-head">
      <h2 class="eval-title">交易评价</h2>
      <span v-if="hasData" class="eval-sub">{{ total }} 条</span>
    </header>

    <SkeletonList v-if="loading" :rows="2" />
    <StateError
      v-else-if="loadError"
      title="评价加载失败"
      detail="网络可能不稳定，稍后再试"
      retry-text="重新加载"
      @retry="load"
    />
    <StateEmpty
      v-else-if="!hasData"
      title="还没有评价"
      detail="这笔交易完成后，买卖双方都可以在这里留下评价"
    />

    <template v-else>
      <div class="eval-summary">
        <div class="score">
          <div class="score-num">{{ summary.avgRating?.toFixed(1) }}</div>
          <el-rate :model-value="Math.round(summary.avgRating || 0)" disabled />
          <div class="score-good">好评率 {{ summary.goodRate?.toFixed(1) }}%</div>
        </div>
        <ul class="dist">
          <li v-for="star in [5, 4, 3, 2, 1]" :key="star" class="dist-row">
            <span class="dist-label">{{ star }} 星</span>
            <span class="dist-track"><i class="dist-fill" :style="{ width: barWidth(star) }" /></span>
            <span class="dist-count">{{ summary.distribution?.[star] || 0 }}</span>
          </li>
        </ul>
      </div>

      <ul class="eval-list">
        <li v-for="item in records" :key="item.id" class="eval-item">
          <div class="item-head">
            <img v-if="item.fromAvatar" class="avatar" :src="item.fromAvatar" :alt="item.fromNickname" />
            <span v-else class="avatar avatar-fallback">{{ (item.fromNickname || '匿')[0] }}</span>
            <span class="nickname">{{ item.fromNickname }}</span>
            <el-tag size="small" type="info" effect="plain">{{ item.roleLabel }}</el-tag>
            <span class="item-time">{{ item.createTime }}</span>
          </div>
          <el-rate :model-value="item.rating" disabled size="small" />
          <p v-if="item.content" class="item-content">{{ item.content }}</p>
          <p v-if="item.tagList && item.tagList.length" class="item-tags">{{ tagText(item) }}</p>
        </li>
      </ul>
      <p v-if="total > records.length" class="eval-more">共 {{ total }} 条，先显示最近 {{ records.length }} 条</p>
    </template>
  </section>
</template>

<style scoped>
.eval-section {
  margin-top: var(--ct-space-6);
  padding-top: var(--ct-space-5);
  border-top: 0.5px solid var(--ct-rule);
}

.eval-head {
  display: flex;
  align-items: baseline;
  gap: var(--ct-space-2);
  margin-bottom: var(--ct-space-4);
}

.eval-title {
  font-size: var(--ct-text-lg);
  font-weight: var(--ct-weight-medium);
  color: var(--ct-ink-900);
}

.eval-sub {
  font-size: var(--ct-text-sm);
  color: var(--ct-text-muted);
}

.eval-summary {
  display: flex;
  gap: var(--ct-space-8);
  padding: var(--ct-space-4);
  background: var(--ct-bg-subtle);
  border-radius: var(--ct-radius-md);
  margin-bottom: var(--ct-space-4);
}

.score {
  min-width: 8rem;
  text-align: center;
}

.score-num {
  font-size: 2.4rem;
  line-height: 1.1;
  color: var(--ct-ink-900);
}

.score-good {
  margin-top: var(--ct-space-1);
  font-size: var(--ct-text-sm);
  color: var(--ct-text-muted);
}

.dist {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--ct-space-1);
}

.dist-row {
  display: grid;
  grid-template-columns: 3rem 1fr 2.5rem;
  align-items: center;
  gap: var(--ct-space-2);
  font-size: var(--ct-text-sm);
  color: var(--ct-text-muted);
}

.dist-track {
  height: 0.5rem;
  background: var(--ct-paper-100);
  border-radius: 999px;
  overflow: hidden;
}

.dist-fill {
  display: block;
  height: 100%;
  background: var(--ct-cat-1-bar);
}

.eval-list {
  display: flex;
  flex-direction: column;
  gap: var(--ct-space-4);
}

.eval-item {
  padding-bottom: var(--ct-space-4);
  border-bottom: 0.5px solid var(--ct-rule);
}

.eval-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.item-head {
  display: flex;
  align-items: center;
  gap: var(--ct-space-2);
  margin-bottom: var(--ct-space-1);
}

.avatar {
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 50%;
  object-fit: cover;
}

.avatar-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--ct-paper-100);
  color: var(--ct-ink-600);
  font-size: var(--ct-text-sm);
}

.nickname {
  font-size: var(--ct-text-base);
  color: var(--ct-ink-900);
}

.item-time {
  margin-left: auto;
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
}

.item-content {
  margin: var(--ct-space-2) 0 0;
  font-size: var(--ct-text-base);
  color: var(--ct-ink-700);
  line-height: 1.7;
}

.item-tags {
  margin: var(--ct-space-1) 0 0;
  font-size: var(--ct-text-sm);
  color: var(--ct-cat-1-bar);
}

.eval-more {
  margin-top: var(--ct-space-3);
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
  text-align: center;
}
</style>
