<script setup>
/**
 * 订单详情页（v0.08）
 *
 * 展示：订单信息、商品信息、对方用户信息、状态操作按钮（仅"待交易"可完成/取消）。
 * 权限：后端会校验只有买卖双方能访问，其他人访问返回 6004。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getOrderDetail, updateOrderStatus } from '@/api/order'
import { demoImage, resolveImageUrl } from '@/utils/product'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const notAllowed = ref(false)
const order = ref(null)

const productImage = computed(() =>
  order.value && order.value.productImage ? resolveImageUrl(order.value.productImage) : demoImage()
)

/** 是否是买家视角 */
const isBuyer = computed(() => order.value?.myRole === 'buyer')

/** 交易对方信息（头像/昵称/校区/信用分） */
const counterpart = computed(() => {
  if (!order.value) return {}
  return isBuyer.value
    ? {
        id: order.value.sellerId,
        nickname: order.value.sellerNickname,
        avatar: order.value.sellerAvatar,
        campus: order.value.sellerCampus,
        creditScore: order.value.sellerCreditScore
      }
    : {
        id: order.value.buyerId,
        nickname: order.value.buyerNickname,
        avatar: order.value.buyerAvatar,
        campus: order.value.buyerCampus,
        creditScore: order.value.buyerCreditScore
      }
})

const statusTagType = computed(() => {
  if (!order.value) return 'info'
  if (order.value.status === 0) return 'warning'
  if (order.value.status === 3) return 'success'
  return 'info'
})

async function load() {
  loading.value = true
  notAllowed.value = false
  try {
    const res = await getOrderDetail(route.params.id)
    order.value = res.data
  } catch (e) {
    notAllowed.value = true
    order.value = null
  } finally {
    loading.value = false
  }
}

/** 确认交易完成 */
async function handleFinish() {
  try {
    await ElMessageBox.confirm(
      `确认「${order.value.productTitle}」这笔交易已经完成吗？确认后商品将标记为已售出，双方信用分 +1。`,
      '确认完成',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    const res = await updateOrderStatus({ orderId: order.value.id, status: 3 })
    ElMessage.success(res.message || '交易已完成')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 取消订单（带原因） */
async function handleCancel() {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写取消原因（选填）', '取消订单', {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：约不上时间 / 商品与描述不符',
      inputValue: '临时不需要了'
    })
    reason = value || ''
  } catch (e) {
    return
  }
  try {
    const res = await updateOrderStatus({ orderId: order.value.id, status: 4, cancelReason: reason })
    ElMessage.success(res.message || '订单已取消')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 联系对方（跳到 v0.07 的聊天窗口） */
function contactCounterpart() {
  router.push({
    path: `/chat/${counterpart.value.id}`,
    query: { productId: order.value.productId }
  })
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-result
      v-if="notAllowed"
      icon="warning"
      title="无权查看该订单"
      sub-title="只有该订单的买家或卖家可以查看（后端返回 6004）"
    >
      <template #extra>
        <el-button type="primary" @click="router.push('/orders/bought')">我买到的</el-button>
        <el-button @click="router.push('/orders/sold')">我卖出的</el-button>
      </template>
    </el-result>

    <template v-else-if="order">
      <el-page-header class="page-header" @back="router.back()">
        <template #content>
          <span class="header-title">订单详情</span>
          <el-tag class="ml-12" :type="statusTagType" effect="dark">{{ order.statusLabel }}</el-tag>
          <el-tag class="ml-8" type="info" effect="plain">
            {{ isBuyer ? '我是买家' : '我是卖家' }}
          </el-tag>
        </template>
      </el-page-header>

      <el-row :gutter="16">
        <!-- 左侧：商品 + 对方信息 -->
        <el-col :xs="24" :md="10">
          <el-card shadow="never">
            <template #header><b>商品信息</b></template>
            <div class="product-block" @click="router.push(`/product/${order.productId}`)">
              <img class="product-img" :src="productImage" alt="商品图片" />
              <div class="product-detail">
                <div class="product-title">{{ order.productTitle }}</div>
                <div class="price">￥{{ Number(order.amount).toFixed(2) }}</div>
                <el-tag size="small" effect="plain" type="info">
                  商品当前：{{ order.productStatusLabel || '未知' }}
                </el-tag>
              </div>
            </div>
          </el-card>

          <el-card shadow="never" class="mt-16">
            <template #header><b>{{ isBuyer ? '卖家信息' : '买家信息' }}</b></template>
            <div class="user-block">
              <el-avatar :size="48">{{ (counterpart.nickname || '匿').slice(0, 1) }}</el-avatar>
              <div class="user-detail">
                <div class="user-name">{{ counterpart.nickname || '匿名用户' }}</div>
                <div class="user-meta">
                  <span v-if="counterpart.campus">{{ counterpart.campus }} · </span>
                  信用分 {{ counterpart.creditScore ?? '-' }}
                </div>
              </div>
              <el-button size="small" type="primary" plain @click="contactCounterpart">私聊对方</el-button>
            </div>
          </el-card>
        </el-col>

        <!-- 右侧：订单信息 + 操作 -->
        <el-col :xs="24" :md="14">
          <el-card shadow="never">
            <template #header><b>订单信息</b></template>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="订单号">{{ order.orderNo }}</el-descriptions-item>
              <el-descriptions-item label="成交金额">￥{{ Number(order.amount).toFixed(2) }}</el-descriptions-item>
              <el-descriptions-item label="交付方式">{{ order.deliveryType === 2 ? '快递' : '校内面交' }}</el-descriptions-item>
              <el-descriptions-item label="交易地点">{{ order.tradePlace || '面议' }}</el-descriptions-item>
              <el-descriptions-item label="下单时间">{{ order.createTime }}</el-descriptions-item>
              <el-descriptions-item v-if="order.buyerRemark" label="买家备注">{{ order.buyerRemark }}</el-descriptions-item>
              <el-descriptions-item v-if="order.finishTime" label="完成时间">{{ order.finishTime }}</el-descriptions-item>
              <el-descriptions-item v-if="order.cancelTime" label="取消时间">{{ order.cancelTime }}</el-descriptions-item>
              <el-descriptions-item v-if="order.cancelReason" label="取消原因">{{ order.cancelReason }}</el-descriptions-item>
            </el-descriptions>

            <div class="actions">
              <template v-if="order.operable">
                <el-button type="success" @click="handleFinish">确认交易完成</el-button>
                <el-button type="warning" @click="handleCancel">取消订单</el-button>
              </template>
              <el-alert
                v-else
                class="status-alert"
                :type="order.status === 3 ? 'success' : 'info'"
                :closable="false"
                show-icon
                :title="order.status === 3
                  ? '该订单已完成，商品已标记为已售出，双方信用分 +1'
                  : '该订单已取消，商品已重新上架'"
              />
              <el-button text type="primary" @click="router.push(isBuyer ? '/orders/bought' : '/orders/sold')">
                ← 返回订单列表
              </el-button>
            </div>
          </el-card>

          <el-card shadow="never" class="mt-16">
            <template #header><b>交易流程提示</b></template>
            <el-steps :active="order.status === 0 ? 1 : 3" align-center finish-status="success">
              <el-step title="下单" :description="order.createTime" />
              <el-step title="线下交易" description="约定地点当面交易" />
              <el-step :title="order.status === 4 ? '已取消' : '已完成'"
                       :description="order.finishTime || order.cancelTime || ''" />
            </el-steps>
          </el-card>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 16px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.ml-8 {
  margin-left: 8px;
}

.ml-12 {
  margin-left: 12px;
}

.product-block {
  display: flex;
  gap: 12px;
  cursor: pointer;
}

.product-img {
  width: 96px;
  height: 96px;
  border-radius: 8px;
  object-fit: cover;
  background: #f7f9fc;
  flex-shrink: 0;
}

.product-detail {
  min-width: 0;
}

.product-title {
  font-weight: 600;
  line-height: 1.5;
  margin-bottom: 6px;
}

.price {
  color: #f56c6c;
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 6px;
}

.user-block {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-detail {
  flex: 1;
  min-width: 0;
}

.user-name {
  font-weight: 600;
}

.user-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.actions {
  margin-top: 18px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.status-alert {
  width: 100%;
}

.mt-16 {
  margin-top: 16px;
}
</style>
