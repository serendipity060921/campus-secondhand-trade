<script setup>
/**
 * 占位页：用于路由表里已声明、但业务尚未实现的页面（如个人中心）。
 * 后续里程碑用真实页面替换即可。
 */
import { useRoute } from 'vue-router'
import { APP_VERSION } from '@/utils/version'

const route = useRoute()
</script>

<template>
  <el-card shadow="never">
    <el-result icon="info" :title="`${route.meta.title || '该功能'}正在开发中`" :sub-title="`当前版本：${APP_VERSION}`">
      <template #extra>
        <el-button type="primary" @click="$router.push('/home')">返回首页</el-button>
      </template>
    </el-result>
  </el-card>
</template>
