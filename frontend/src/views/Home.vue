<script setup>
/**
 * 首页（脚手架连通性看板）
 *
 * 依次调用三个后端接口，用来证明「Vue 前端 → Spring Boot → MyBatis-Plus → MySQL」整条链路连通：
 *   GET /api/health      后端存活
 *   GET /api/health/db   数据库连接 + 数据统计
 *   GET /api/categories  真实表数据查询
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getHealth, getDbHealth, getCategories, getProducts } from '@/api/common'
import { APP_VERSION, APP_VERSION_LABEL } from '@/utils/version'

const loading = ref(false)
const health = ref(null)
const dbHealth = ref(null)
const categories = ref([])
const productPage = ref(null)

async function loadAll() {
  loading.value = true
  try {
    const [h, d, c, p] = await Promise.all([
      getHealth(),
      getDbHealth().catch(() => null),
      getCategories({ parentId: 0 }).catch(() => null),
      getProducts({ page: 1, size: 5 }).catch(() => null)
    ])
    health.value = h.data
    dbHealth.value = d ? d.data : null
    categories.value = c ? c.data : []
    productPage.value = p ? p.data : null
    ElMessage.success('前后端连通正常')
  } catch (e) {
    // 错误提示已由 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<template>
  <div v-loading="loading">
    <!-- 顶部欢迎条 -->
    <el-card shadow="never" class="welcome-card">
      <div class="welcome">
        <div>
          <h2>欢迎使用校园二手交易平台</h2>
          <p class="text-muted">
            当前版本 {{ APP_VERSION }} · {{ APP_VERSION_LABEL.replace(APP_VERSION + ' ', '') }}
            ｜ 本页为脚手架阶段的连通性自检看板，业务功能请从「首页」进入
          </p>
        </div>
        <el-button type="primary" :loading="loading" @click="loadAll">重新检测连通性</el-button>
      </div>
    </el-card>

    <!-- 连通性看板 -->
    <el-row :gutter="16" class="mt-16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>① 后端服务</b></template>
          <el-descriptions v-if="health" :column="1" border size="small">
            <el-descriptions-item label="应用名称">{{ health.application }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ health.version }}</el-descriptions-item>
            <el-descriptions-item label="运行环境">{{ health.profile }}</el-descriptions-item>
            <el-descriptions-item label="服务状态">
              <el-tag type="success" size="small">{{ health.status }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="JDK 版本">{{ health.javaVersion }}</el-descriptions-item>
            <el-descriptions-item label="服务端时间">{{ health.serverTime }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="后端未连通，请启动 Spring Boot 服务" :image-size="60" />
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>② MySQL 数据库</b></template>
          <el-descriptions v-if="dbHealth" :column="1" border size="small">
            <el-descriptions-item label="数据库名">{{ dbHealth.database }}</el-descriptions-item>
            <el-descriptions-item label="用户数">{{ dbHealth.userCount }}</el-descriptions-item>
            <el-descriptions-item label="分类数">{{ dbHealth.categoryCount }}</el-descriptions-item>
            <el-descriptions-item label="商品数">{{ dbHealth.productCount }}</el-descriptions-item>
            <el-descriptions-item label="检测时间">{{ dbHealth.checkTime }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="数据库未连通（请检查 application-dev.yml 与 MySQL 服务）" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 真实表数据 -->
    <el-card shadow="never" class="mt-16">
      <template #header><b>③ 分类表数据（category 表真实查询结果）</b></template>
      <el-space wrap>
        <el-tag v-for="item in categories" :key="item.id" size="large" effect="plain">
          {{ item.name }}（id={{ item.id }}）
        </el-tag>
        <el-empty v-if="!categories.length" description="暂无分类数据" :image-size="60" />
      </el-space>
    </el-card>

    <el-card shadow="never" class="mt-16">
      <template #header>
        <b>④ 商品分页接口（演示 MyBatis-Plus 分页插件）</b>
      </template>
      <p v-if="productPage" class="text-muted">
        共 {{ productPage.total }} 条，第 {{ productPage.current }} / {{ productPage.pages || 1 }} 页
      </p>
      <el-table v-if="productPage && productPage.records.length" :data="productPage.records" border size="small">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="price" label="价格" width="100" />
        <el-table-column prop="status" label="状态" width="100" />
      </el-table>
      <el-empty v-else description="暂无商品数据（脚手架阶段尚未实现发布功能）" :image-size="60" />
    </el-card>
  </div>
</template>

<style scoped>
.welcome-card {
  border-left: var(--ct-hairline) solid var(--ct-border);
}

.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.welcome h2 {
  margin: 0 0 8px;
  font-size: 20px;
}

.welcome p {
  margin: 0;
}
</style>
