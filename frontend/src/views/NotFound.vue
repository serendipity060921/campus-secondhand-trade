<script setup>
/**
 * 404 页面（v0.16 改版）
 *
 * 语言与数据状态面板一致：纸面白底 + 0.5px 墨线 + 一个"目录号"式的 404 标记，
 * 而不是通用模板的大图标 + 居中提示。可见文案保持原样（端到端与截图断言依赖）。
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <div class="not-found-panel">
      <p class="not-found-code mono">404</p>
      <h1 class="not-found-title">抱歉，你访问的页面不存在</h1>
      <p class="not-found-hint">可能是链接写错了，或者这件东西已经下架了。</p>
      <div class="not-found-actions">
        <el-button type="primary" @click="router.push('/home')">返回首页</el-button>
        <el-button @click="router.push('/login')">去登录</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--ct-space-6) var(--ct-space-5);
}

.not-found-panel {
  max-width: 520px;
  text-align: center;
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
  border-radius: var(--ct-radius-sm);
  padding: var(--ct-space-7) var(--ct-space-6);
}

/* 404 当成一个"分类号"来排：等宽、弱化，不喧哗 */
.not-found-code {
  margin: 0;
  font-size: var(--ct-text-2xl);
  letter-spacing: 0.12em;
  color: var(--ct-text-muted);
}

.not-found-title {
  margin: var(--ct-space-3) 0 0;
  font-size: var(--ct-text-lg);
  font-weight: var(--ct-weight-semibold);
}

.not-found-hint {
  margin: var(--ct-space-2) 0 0;
  font-size: var(--ct-text-sm);
  color: var(--ct-text-muted);
}

.not-found-actions {
  margin-top: var(--ct-space-5);
  display: flex;
  gap: var(--ct-space-3);
  justify-content: center;
}
</style>
