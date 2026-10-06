<template>
  <div class="skeleton-wrap">
    <p class="sr-only" role="status">正在加载</p>
    <div class="skeleton-detail" aria-hidden="true">
      <div class="block block-media" />
      <div class="skeleton-body">
        <div class="block block-line w-70" />
        <div class="block block-price" />
        <div class="block block-line w-40" />
        <div class="block block-line w-55" />
        <div class="block block-divider" />
        <div class="block block-line w-40" />
        <div class="block block-line w-70" />
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 详情骨架（商品详情 / 订单详情 / 个人中心复用）
 *
 * 用法：<SkeletonDetail />
 */
</script>

<style scoped>
.skeleton-detail {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: var(--ct-space-5);
  padding: var(--ct-space-5);
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
  border-radius: var(--ct-radius-md);
}

.skeleton-body {
  display: flex;
  flex-direction: column;
  gap: var(--ct-space-2);
}

.block {
  background: var(--ct-bg-subtle);
  border-radius: var(--ct-radius-sm);
  animation: ct-pulse 1.4s ease-in-out infinite;
}

.block-media {
  height: 240px;
}

.block-line {
  height: 14px;
}

.block-price {
  height: 28px;
  width: 30%;
}

.block-divider {
  height: var(--ct-hairline);
  margin: var(--ct-space-3) 0;
  animation: none;
}

.w-70 { width: 70%; }
.w-55 { width: 55%; }
.w-40 { width: 40%; }

@keyframes ct-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}

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
