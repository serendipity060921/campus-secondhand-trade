<template>
  <!--
    列表骨架：按最终版式预排，避免加载完成后的布局跳动。
    对辅助技术：整体 aria-hidden，另有一份屏幕阅读器可读的"加载中"提示。
  -->
  <div class="skeleton-wrap">
    <p class="sr-only" role="status">正在加载</p>
    <div class="skeleton-grid" :class="`cols-${columns}`" aria-hidden="true">
      <div v-for="i in count" :key="i" class="skeleton-item" :class="variant">
        <div class="block block-media" />
        <div class="block block-line w-70" />
        <div class="block block-line w-40" />
        <div class="block block-line w-55" />
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 列表骨架（卡片 / 行两种形态）
 *
 * 用法：
 *   <SkeletonList :count="6" :columns="3" />          <!-- 商品卡网格 -->
 *   <SkeletonList variant="row" :count="8" :columns="1" />  <!-- 单行列表 -->
 */
defineProps({
  count: { type: Number, default: 6 },
  columns: { type: Number, default: 3 },
  variant: { type: String, default: 'card' }        // card | row
})
</script>

<style scoped>
.skeleton-grid {
  display: grid;
  gap: var(--ct-space-4);
}

.cols-1 { grid-template-columns: 1fr; }
.cols-2 { grid-template-columns: repeat(2, 1fr); }
.cols-3 { grid-template-columns: repeat(3, 1fr); }
.cols-4 { grid-template-columns: repeat(4, 1fr); }

.skeleton-item {
  padding: var(--ct-space-3);
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
  border-radius: var(--ct-radius-md);
}

.skeleton-item.row {
  display: grid;
  grid-template-columns: 72px 1fr 120px;
  align-items: center;
  gap: var(--ct-space-3);
  padding: var(--ct-space-2) var(--ct-space-3);
}

.skeleton-item.row .block-media {
  height: 48px;
}

.skeleton-item.row .block-line:nth-child(4) {
  display: none;
}

.block {
  background: var(--ct-bg-subtle);
  border-radius: var(--ct-radius-sm);
  animation: ct-pulse 1.4s ease-in-out infinite;
}

.block-media {
  height: 132px;
  margin-bottom: var(--ct-space-3);
}

.block-line {
  height: 12px;
  margin-bottom: var(--ct-space-2);
}

.w-70 { width: 70%; }
.w-55 { width: 55%; }
.w-40 { width: 40%; }

@keyframes ct-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}

/* 屏幕阅读器可见、视觉隐藏 */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
