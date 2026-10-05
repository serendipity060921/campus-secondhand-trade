<script setup>
/**
 * 订单列表页（v0.08）
 *
 * 一个组件两种角色，由路由 props 决定：
 *   /orders/bought  role="buy"   我买到的
 *   /orders/sold    role="sell"  我卖出的
 *
 * 功能：按状态筛选、分页、查看详情、快速完成/取消（仅"待交易"可操作）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getBuyOrders, getSellOrders, updateOrderStatus } from '@/api/order'
import { demoImage, resolveImageUrl } from '@/utils/product'
import StateError from '@/components/states/StateError.vue'

const props = defineProps({
  role: { type: String, default: 'buy' } // buy | sell
})

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const loadError = ref(false)
const orders = ref([])
const total = ref(0)
const activeStatus = ref('all')

const query = reactive({ page: 1, size: 10 })

const statusTabs = [
  { name: 'all', label: '全部' },
  { name: '0', label: '待交易' },
  { name: '3', label: '已完成' },
  { name: '4', label: '已取消' }
]

const pageTitle = computed(() => (props.role === 'buy' ? '我买到的' : '我卖出的'))
const counterpartLabel = computed(() => (props.role === 'buy' ? '卖家' : '买家'))

function imageOf(item) {
  return item.productImage ? resolveImageUrl(item.productImage) : demoImage()
}

function statusTagType(status) {
  if (status === 0) return 'warning'
  if (status === 3) return 'success'
  if (status === 4) return 'info'
  return 'info'
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const params = {
      page: query.page,
      size: query.size,
      status: activeStatus.value === 'all' ? undefined : Number(activeStatus.value)
    }
    const res = props.role === 'buy' ? await getBuyOrders(params) : await getSellOrders(params)
    orders.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    orders.value = []
    total.value = 0
    loadError.value = true
    } finally {
    loading.value = false
  }
}

async function handleStatusChange(item, status) {
  const isFinish = status === 3
  try {
    await ElMessageBox.confirm(
      isFinish
        ? `确认「${item.productTitle}」这笔交易已完成吗？确认后商品将标记为已售出。`
        : `确定取消订单「${item.orderNo}」吗？取消后商品会重新上架。`,
      isFinish ? '确认完成' : '取消订单',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    const res = await updateOrderStatus({ orderId: item.id, status })
    ElMessage.success(res.message || '操作成功')
    load()
  } catch (e) {
    /* 错误提示由拦截器统一处理 */
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

// 切换"我买到的 / 我卖出的"时重新加载
watch(
  () => props.role,
  () => {
    activeStatus.value = 'all'
    query.page = 1
    load()
  }
)

// 从别的页面带着 ?status= 进来时按状态筛选
onMounted(() => {
  if (route.query.status !== undefined) {
    activeStatus.value = String(route.query.status)
  }
  load()
})
</script>

<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <b>{{ pageTitle }}</b>
          <el-button-group>
            <el-button
              size="small"
              :type="props.role === 'buy' ? 'primary' : 'default'"
              @click="router.push('/orders/bought')"
            >
              我买到的
            </el-button>
            <el-button
              size="small"
              :type="props.role === 'sell' ? 'primary' : 'default'"
              @click="router.push('/orders/sold')"
            >
              我卖出的
            </el-button>
          </el-button-group>
        </div>
      </template>

      <el-tabs v-model="activeStatus" @tab-change="handleTabChange">
        <el-tab-pane v-for="tab in statusTabs" :key="tab.name" :label="tab.label" :name="tab.name" />
      </el-tabs>

      <StateError
        v-if="loadError && !loading"
        title="加载失败，请稍后重试"
        detail="网络可能不稳定，或服务正在重启"
        retry-text="重新加载"
        @retry="load"
      />
      <el-table v-else v-loading="loading" :data="orders" border stripe>
        <el-table-column label="商品" min-width="250">
          <template #default="{ row }">
            <div class="product-cell">
              <img class="table-img" :src="imageOf(row)" alt="商品图片" />
              <div class="product-info">
                <el-link type="primary" :underline="false" @click="router.push(`/product/${row.productId}`)">
                  {{ row.productTitle }}
                </el-link>
                <div class="order-no">订单号：{{ row.orderNo }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="金额" width="110">
          <template #default="{ row }">
            <span class="price">￥{{ Number(row.amount).toFixed(2) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="counterpartLabel" width="130" prop="counterpartNickname" />

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ row.statusLabel }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="下单时间" width="165" prop="createTime" />

        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="router.push(`/orders/${row.id}`)">详情</el-button>
            <template v-if="row.status === 0">
              <el-button size="small" type="success" @click="handleStatusChange(row, 3)">完成</el-button>
              <el-button size="small" type="warning" @click="handleStatusChange(row, 4)">取消</el-button>
            </template>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="props.role === 'buy' ? '还没有买过东西，去首页看看～' : '还没有卖出记录，先发布一件闲置吧～'" />
        </template>
      </el-table>

      <div v-if="total > query.size" class="pagination">
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

.product-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.table-img {
  width: 56px;
  height: 56px;
  border-radius: 6px;
  object-fit: cover;
  background: var(--ct-bg-subtle);
  flex-shrink: 0;
}

.product-info {
  min-width: 0;
}

.order-no {
  font-size: 12px;
  color: var(--ct-text-muted);
  margin-top: 4px;
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
