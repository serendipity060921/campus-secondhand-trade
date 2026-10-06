<template>
  <!--
    错误状态：一行说明 + 重试。
    注意：文案由调用方传入 —— 既有的可见错误提示（端到端用例断言依赖）必须原样传进来，
    本组件只负责呈现，不改写任何业务文案。
  -->
  <div class="state-panel" role="alert">
    <span class="state-mark" aria-hidden="true">!</span>
    <p class="state-title">{{ title }}</p>
    <p v-if="detail" class="state-detail">{{ detail }}</p>
    <div class="state-actions">
      <button v-if="retryText" class="state-action" type="button" @click="$emit('retry')">
        {{ retryText }}
      </button>
      <slot />
    </div>
  </div>
</template>

<script setup>
/**
 * 错误状态
 *
 * 用法：
 *   <StateError title="加载失败，请稍后重试" detail="网络可能不稳定" retry-text="重新加载"
 *               @retry="load" />
 */
defineProps({
  title: { type: String, default: '加载失败' },
  /** 次要说明：建议写"接下来能做什么"，不要重复标题 */
  detail: { type: String, default: '' },
  retryText: { type: String, default: '重新加载' }
})
defineEmits(['retry'])
</script>

<style scoped>
.state-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ct-space-2);
  padding: var(--ct-space-6) var(--ct-space-5);
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
  border-left: var(--ct-space-1) solid var(--el-color-danger);
  border-radius: var(--ct-radius-md);
  text-align: center;
}

.state-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  font-size: var(--ct-text-md);
  font-weight: var(--ct-weight-semibold);
  color: var(--ct-text-inverse);
  background: var(--el-color-danger);
  border-radius: var(--ct-radius-sm);
}

.state-title {
  margin: 0;
  font-size: var(--ct-text-md);
  color: var(--ct-text-primary);
}

.state-detail {
  margin: 0;
  font-size: var(--ct-text-sm);
  color: var(--ct-text-muted);
  max-width: 42ch;
}

.state-actions {
  margin-top: var(--ct-space-2);
  display: flex;
  gap: var(--ct-space-2);
}

.state-action {
  padding: var(--ct-space-2) var(--ct-space-4);
  font-size: var(--ct-text-base);
  color: var(--ct-text-primary);
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border-strong);
  border-radius: var(--ct-radius-sm);
  cursor: pointer;
}

.state-action:hover {
  background: var(--ct-bg-subtle);
}
</style>
