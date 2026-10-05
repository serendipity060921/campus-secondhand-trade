<template>
  <!--
    未登录 / 无权限：两种形态共用一个组件，因为它们的差别只在文案与出口，
    而"谁都不能白屏"是同一件事。
  -->
  <div class="state-panel" role="alert">
    <span class="state-mark" aria-hidden="true">{{ mode === 'login' ? '→' : '⊘' }}</span>
    <p class="state-title">{{ message }}</p>
    <p v-if="hint" class="state-hint">{{ hint }}</p>
    <div class="state-actions">
      <router-link v-if="mode === 'login'" class="state-action" :to="loginTarget">去登录</router-link>
      <router-link class="state-action" to="/home">返回首页</router-link>
      <slot />
    </div>
  </div>
</template>

<script setup>
/**
 * 未登录 / 无权限状态
 *
 * 用法：
 *   <StateForbidden mode="login" />                       <!-- 未登录 -->
 *   <StateForbidden mode="forbidden" message="无权查看或操作该订单" />  <!-- 无权限 -->
 *
 * 说明：mode="login" 时把当前路径带在 redirect 参数里，登录后可回跳。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const props = defineProps({
  mode: { type: String, default: 'forbidden' },   // login | forbidden
  message: { type: String, default: '' },
  hint: { type: String, default: '' }
})

const route = useRoute()

const loginTarget = computed(() => ({ path: '/login', query: { redirect: route.fullPath } }))
const message = computed(() => props.message
  || (props.mode === 'login' ? '请先登录后再继续' : '你没有权限查看这个页面'))
</script>

<style scoped>
.state-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ct-space-2);
  padding: var(--ct-space-7) var(--ct-space-5);
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
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
  color: var(--ct-text-muted);
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
  display: flex;
  gap: var(--ct-space-2);
}

.state-action {
  display: inline-block;
  padding: var(--ct-space-2) var(--ct-space-4);
  font-size: var(--ct-text-base);
  color: var(--ct-text-primary);
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border-strong);
  border-radius: var(--ct-radius-sm);
}

.state-action:hover {
  background: var(--ct-bg-subtle);
  text-decoration: none;
}
</style>
