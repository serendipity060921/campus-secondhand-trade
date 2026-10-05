<script setup>
/**
 * 我的商品页（v0.05，需要登录）
 *
 * 只展示当前登录用户发布的商品（后端 /api/product/mine 从 Token 取 userId），
 * 支持按状态筛选与上架 / 下架操作（后端会再次校验商品归属）。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyProducts, updateProductStatus } from '@/api/product'
import { demoImage, formatPrice, PRODUCT_STATUS, resolveImageUrl } from '@/utils/product'

const router = useRouter()

const loading = ref(false)
const products = ref([])
const total = ref(0)
const activeStatus = ref('all')

const query = reactive({ page: 1, size: 10 })

const statusTabs = [
  { name: 'all', label: '全部' },
  { name: '1', label: '在售' },
  { name: '3', label: '已下架' },
  { name: '4', label: '交易中' },
  { name: '5', label: '已售出' }
]

function imageOf(item) {
  return item.coverImage ? resolveImageUrl(item.coverImage) : demoImage(item.categoryName)
}

function statusInfo(status) {
  return PRODUCT_STATUS[status] || { label: '未知', type: 'info' }
}

/** 是否可以上下架（交易中/已售出不可操作） */
function canToggle(status) {
  return status === 1 || status === 3
}

async function load() {
  loading.value = true
  try {
    const res = await getMyProducts({
      page: query.page,
      size: query.size,
      status: activeStatus.value === 'all' ? undefined : Number(activeStatus.value)
    })
    products.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    products.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function handleToggle(item) {
  const target = item.status === 1 ? 3 : 1
  const actionText = target === 1 ? '上架' : '下架'
  try {
    await ElMessageBox.confirm(`确定要${actionText}商品「${item.title}」吗？`, `${actionText}确认`, {
      type: 'warning'
    })
  } catch (e) {
    return // 用户取消
  }
  try {
    const res = await updateProductStatus({ productId: item.id, status: target })
    ElMessage.success(res.message || `${actionText}成功`)
    load()
  } catch (e) {
    /* 无权限/状态非法等错误由拦截器提示 */
  }
}

function handleTabChange() {
  query.page = 1
  load()
}

function handlePageChange(page) {
  query.page = page
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <b>我的商品</b>
          <el-button type="primary" size="small" @click="router.push('/product/publish')">+ 发布新商品</el-button>
        </div>
      </template>

      <el-tabs v-model="activeStatus" @tab-change="handleTabChange">
        <el-tab-pane v-for="tab in statusTabs" :key="tab.name" :label="tab.label" :name="tab.name" />
      </el-tabs>

      <el-table v-loading="loading" :data="products" border stripe>
        <el-table-column label="图片" width="92">
          <template #default="{ row }">
            <img class="table-img" :src="imageOf(row)" alt="商品图片" />
          </template>
        </el-table-column>

        <el-table-column label="商品名称" min-width="220">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="router.push(`/product/${row.id}`)">
              {{ row.title }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="120" prop="categoryName" />

        <el-table-column label="价格" width="110">
          <template #default="{ row }">
            <span class="price">{{ formatPrice(row.price) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusInfo(row.status).type" size="small">{{ statusInfo(row.status).label }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="浏览量" width="90" prop="viewCount" />

        <el-table-column label="发布时间" width="170" prop="createTime" />

        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canToggle(row.status)"
              size="small"
              :type="row.status === 1 ? 'warning' : 'success'"
              @click="handleToggle(row)"
            >
              {{ row.status === 1 ? '下架' : '上架' }}
            </el-button>
            <el-button size="small" @click="router.push(`/product/${row.id}`)">查看</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="还没有发布商品，点右上角发布一件闲置吧～" />
        </template>
      </el-table>

      <div v-if="total > 0" class="pagination">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :current-page="query.page"
          :page-size="query.size"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-img {
  width: 64px;
  height: 64px;
  border-radius: 6px;
  object-fit: cover;
  display: block;
  background: var(--ct-bg-subtle);
}

.price {
  color: var(--ct-price);
  font-weight: 600;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
