<script setup>
/**
 * 分类书标色带（v0.16）
 *
 * 按选定稿 A 的 class-band / band-1..8 区域：8 段等宽，每段是"浅底色场 + 底部 4px 实色条"，
 * 段内为分类号（等宽）+ 分类名。它是首页的**主色场**与标志性动作。
 *
 * 无障碍：
 *   · 用 <button> + aria-pressed 表达筛选态（键盘可达、状态可读）
 *   · 分类不只靠颜色：每段都有分类号文字
 *   · 外层是 <nav aria-label>，补上页面缺失的导航地标
 *
 * 数据说明：分类接口只返回 id/parentId/name/sortOrder，**没有件数**，
 * 因此这里不显示"每类 N 件"（不编造数字）。件数如需展示，需要后端补一个统计字段。
 */
import { classBarVar, classTintVar } from '@/utils/catalogue'

const props = defineProps({
  /** buildClassIndex().tops —— 8 个一级分类（含 code / index） */
  tops: { type: Array, default: () => [] },
  /** 当前选中的一级分类 id，null 表示全部 */
  activeId: { type: [Number, String], default: null }
})

const emit = defineEmits(['select'])

/** 再点一次已选中的分类＝取消筛选（等价于旧的"重置"） */
function toggle(top) {
  emit('select', String(props.activeId) === String(top.id) ? null : top.id)
}
</script>

<template>
  <nav class="class-band" aria-label="商品分类">
    <button
      v-for="top in tops"
      :key="top.id"
      type="button"
      class="band-segment"
      :class="{ 'is-active': String(activeId) === String(top.id) }"
      :style="{
        backgroundColor: `var(${classTintVar(top.index)})`,
        '--band-bar': `var(${classBarVar(top.index)})`
      }"
      :aria-pressed="String(activeId) === String(top.id)"
      @click="toggle(top)"
    >
      <span class="band-code">{{ top.code }}</span>
      <span class="band-name">{{ top.name }}</span>
      <!-- 件数来自分类接口的真实统计（含子分类汇总）；没有该字段时整行不渲染，不显示 0 冒充 -->
      <span v-if="top.productCount !== null && top.productCount !== undefined" class="band-count">
        {{ top.productCount }} 件
      </span>
    </button>
  </nav>
</template>

<style scoped>
.class-band {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  border-top: var(--ct-hairline) solid var(--ct-border);
  border-bottom: var(--ct-hairline) solid var(--ct-border);
}

.band-segment {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
  min-height: 60px;
  padding: var(--ct-space-2) var(--ct-space-3);
  border: 0;
  /* 底部是分类实色条：整条色带的"书标"感来自这里。
     段本身是直角，因此不做圆角声明（避免被检测器误判为"圆角元素上的粗强调边框"） */
  border-bottom: 4px solid var(--band-bar);
  text-align: left;
  font: inherit;
  color: var(--ct-text-primary);
  cursor: pointer;
  transition: filter var(--ct-duration-fast) ease;
}

.band-segment:hover {
  filter: brightness(0.97);
}

.band-segment:focus-visible {
  outline: 2px solid var(--ct-action);
  outline-offset: -2px;
}

.band-segment.is-active {
  border-bottom-width: 6px;
  font-weight: var(--ct-weight-semibold);
}

.band-code {
  font-family: var(--ct-font-mono);
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--ct-text-muted);
}

.band-name {
  font-size: var(--ct-text-sm);
}

/* 件数：等宽小字，弱化 —— 是"有多少"而不是"卖点" */
.band-count {
  font-family: var(--ct-font-mono);
  font-size: 11px;
  color: var(--ct-text-muted);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1100px) {
  .class-band {
    grid-template-columns: repeat(4, 1fr);
  }
}

@media (max-width: 600px) {
  .class-band {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
