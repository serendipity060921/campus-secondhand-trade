<template>
  <!--
    空状态：说清"为什么空"并给一个主行动。
    形态编码用"空框"与在售状态的空框书标呼应，与错误态（实心感叹）区分开，
    不依赖颜色单独传达含义。
  -->
  <div class="state-panel" role="status">
    <span class="state-mark" aria-hidden="true" />
    <p class="state-title">{{ title }}</p>
    <p v-if="hint" class="state-hint">{{ hint }}</p>
    <div v-if="actionText" class="state-actions">
      <router-link v-if="actionTo" class="state-action" :to="actionTo">{{ actionText }}</router-link>
      <button v-else class="state-action" type="button" @click="$emit('action')">{{ actionText }}</button>
    </div>
    <slot />
  </div>
</template>

<script setup>
/**
 * 空状态（v0.16 状态组件）
 *
 * 用法：
 *   <StateEmpty title="这个书标下暂时没有卡片" hint="换个分类，或者成为第一个在这儿发布的人"
 *               action-text="去发布闲置" action-to="/product/publish" />
 */
defineProps({
  title: { type: String, default: '这里暂时没有内容' },
  hint: { type: String, default: '' },
  /** 主行动文案；为空则不渲染行动区 */
  actionText: { type: String, default: '' },
  /** 传路由则渲染为 router-link，否则渲染为按钮并抛出 action 事件 */
  actionTo: { type: [String, Object], default: '' }
})
defineEmits(['action'])
</script>

<style scoped>
.state-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ct-space-2);
  padding: var(--ct-space-7) var(--ct-space-5);
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) dashed var(--ct-border);
  border-radius: var(--ct-radius-md);
  text-align: center;
}

/* 空框：与"在售"状态书标同一形态语言 */
.state-mark {
  width: 28px;
  height: 28px;
  border: var(--ct-line-strong) solid var(--ct-border);
  border-radius: var(--ct-radius-sm);
}

.state-title {
  margin: 0;
  font-size: var(--ct-text-md);
  color: var(--ct-text-primary);
}

.state-hint {
  margin: 0;
  font-size: var(--ct-text-sm);
  color: var(--ct-text-muted);
  max-width: 42ch;
}

.state-actions {
  margin-top: var(--ct-space-2);
}

.state-action {
  display: inline-block;
  padding: var(--ct-space-2) var(--ct-space-4);
  font-size: var(--ct-text-base);
  color: var(--ct-action-text);
  background: var(--ct-action);
  border: var(--ct-hairline) solid var(--ct-action);
  border-radius: var(--ct-radius-sm);
  cursor: pointer;
}

.state-action:hover {
  background: var(--ct-action-hover);
  text-decoration: none;
}
</style>
