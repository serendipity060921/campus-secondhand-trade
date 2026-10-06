<script setup>
/**
 * 目录卡（v0.16）
 *
 * 按选定稿 A 的卡片解剖顺序：4:3 图 → 分类号 chip + 分类名 → 标题两行 → 价格 → 校区 · 成色。
 *
 * 无障碍：
 *   · 整张卡是一个 <router-link>（原生可聚焦、Enter 可打开、右键可新窗口），
 *     修掉此前 <el-card @click> 键盘完全不可达的问题（评审 P0-1）
 *   · 图片 alt 取商品标题，不再是一堆相同的"商品图片"
 *   · 分类不只靠颜色：色块内的分类号文字同时表达分类
 *   · loading="lazy"：首屏只加载视口内的图
 */
import { computed, ref } from 'vue'
import { demoImage, formatPrice, resolveImageUrl } from '@/utils/product'

const props = defineProps({
  item: { type: Object, required: true },
  /** 索书号，如 TN·004（由父组件用 utils/catalogue 派生） */
  code: { type: String, default: '' },
  /** 该分类的浅底色令牌名，如 --ct-cat-2-tint */
  tintVar: { type: String, default: '--ct-cat-8-tint' }
})

const failed = ref(false)

const src = computed(() => {
  if (failed.value) {
    return demoImage(props.item.categoryName)
  }
  return props.item.coverImage
    ? resolveImageUrl(props.item.coverImage)
    : demoImage(props.item.categoryName)
})

/** 成色文案（与后台/发布的取值一致） */
const CONDITION = ['', '全新', '几乎全新', '轻微使用', '明显使用']
const conditionText = computed(() => CONDITION[props.item.conditionLevel] || '')
const campus = computed(() => props.item.campus || '校区未填')
const priceText = computed(() => formatPrice(props.item.price))
</script>

<template>
  <router-link
    class="product-card"
    :to="`/product/${item.id}`"
    :aria-label="`${item.title}，${priceText}，${campus}`"
  >
    <div class="cover">
      <img :src="src" :alt="item.title" loading="lazy" @error="failed = true">
    </div>

    <div class="info">
      <div class="card-top">
        <span v-if="code" class="shelf-code" :style="{ background: `var(${tintVar})` }">{{ code }}</span>
        <span class="category">{{ item.categoryName || '未分类' }}</span>
      </div>

      <div class="title product-title" :title="item.title">{{ item.title }}</div>

      <div class="price-row">
        <span class="price">{{ priceText }}</span>
        <span v-if="item.originalPrice" class="origin">{{ formatPrice(item.originalPrice) }}</span>
      </div>

      <div class="meta">
        <span class="campus">{{ campus }}</span>
        <span v-if="conditionText" class="condition-text">{{ conditionText }}</span>
      </div>
    </div>
  </router-link>
</template>

<style scoped>
.product-card {
  display: block;
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
  border-radius: var(--ct-radius-sm);
  overflow: hidden;
  color: inherit;
  text-decoration: none;
  transition: border-color var(--ct-duration-fast) var(--ct-ease, ease);
}

.product-card:hover {
  border-color: var(--ct-border-strong);
}

/* 焦点环：键盘用户必须看得见当前卡片 */
.product-card:focus-visible {
  outline: 2px solid var(--ct-action);
  outline-offset: 2px;
}

.cover {
  aspect-ratio: 4 / 3;
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
  padding: var(--ct-space-3);
}

.card-top {
  display: flex;
  align-items: center;
  gap: var(--ct-space-2);
  margin-bottom: var(--ct-space-2);
}

.shelf-code {
  font-family: var(--ct-font-mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  padding: 2px var(--ct-space-1);
  border-radius: var(--ct-radius-sm);
  color: var(--ct-text-primary);
}

.category {
  font-size: 11px;
  /* 用 muted 而非 faint：11px 属小字，faint（#8a8f86）对白底只有 3.31:1 不达 AA；
     DESIGN.md 也明确规定 faint 仅用于装饰、不得用于正文 */
  color: var(--ct-text-muted);
}

.title {
  font-size: var(--ct-text-base);
  line-height: 1.4;
  height: 39px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.price-row {
  margin-top: var(--ct-space-2);
  display: flex;
  align-items: baseline;
  gap: var(--ct-space-2);
}

.price {
  color: var(--ct-price);
  font-size: 17px;
  font-weight: var(--ct-weight-semibold);
  font-variant-numeric: tabular-nums;
}

.origin {
  color: var(--ct-text-muted);
  font-size: var(--ct-text-xs);
  text-decoration: line-through;
}

.meta {
  margin-top: var(--ct-space-1);
  display: flex;
  align-items: center;
  gap: var(--ct-space-2);
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
}
</style>
