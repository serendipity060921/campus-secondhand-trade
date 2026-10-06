<template>
  <div class="skeleton-wrap">
    <p class="sr-only" role="status">正在加载</p>
    <div class="skeleton-table" aria-hidden="true">
      <div class="skeleton-head">
        <div v-for="c in columns" :key="`h${c}`" class="block block-cell" />
      </div>
      <div v-for="r in rows" :key="r" class="skeleton-row">
        <div v-for="c in columns" :key="`${r}-${c}`" class="block block-cell"
             :class="{ 'w-narrow': c === columns }" />
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 表格骨架（后台管理页与订单/收藏等列表页复用）
 *
 * 用法：<SkeletonTable :rows="6" :columns="5" />
 */
defineProps({
  rows: { type: Number, default: 6 },
  columns: { type: Number, default: 5 }
})
</script>

<style scoped>
.skeleton-table {
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
  border-radius: var(--ct-radius-md);
  overflow: hidden;
}

.skeleton-head,
.skeleton-row {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--ct-space-3);
  padding: var(--ct-space-3) var(--ct-space-4);
}

.skeleton-head {
  background: var(--ct-bg-subtle);
  border-bottom: var(--ct-hairline) solid var(--ct-border);
}

.skeleton-row + .skeleton-row {
  border-top: var(--ct-hairline) solid var(--ct-border);
}

.block {
  background: var(--ct-bg-subtle);
  border-radius: var(--ct-radius-sm);
  animation: ct-pulse 1.4s ease-in-out infinite;
}

.skeleton-head .block {
  height: 12px;
  opacity: 0.9;
}

.block-cell {
  height: 14px;
}

.w-narrow {
  width: 60%;
}

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
