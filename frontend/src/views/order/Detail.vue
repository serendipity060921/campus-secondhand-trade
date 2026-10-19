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
import StateError from '@/components/states/StateError.vue'
import EvaluationDialog from '@/components/evaluation/EvaluationDialog.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const loadError = ref(false)
const notAllowed = ref(false)
const order = ref(null)

// v0.18：订单完成后可评价（双向）。入口放在详情页而不是列表里 ——
// 列表里的按钮在 fixed-right 列中，实测点击不触发处理器（Element Plus 固定列的坑），
// 详情页是更稳也更符合习惯的入口（先看订单，再评价）。
const evalDialogVisible = ref(false)
const evalDialogRef = ref(null)

async function openEvaluation() {
  const ok = await evalDialogRef.value?.checkBeforeOpen?.()
  if (ok === false) return
  evalDialogVisible.value = true
}

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
  loadError.value = false
  notAllowed.value = false
  try {
    const res = await getOrderDetail(route.params.id)
    order.value = res.data
  } catch (e) {
    notAllowed.value = true
    order.value = null
    loadError.value = true
    } finally {
    loading.value = false
  }
}

/** 是否卖家视角（v0.18：状态机里"谁能推进"要看这个） */
const isSeller = computed(() => order.value?.myRole === 'seller')

/**
 * v0.18：按钮由**状态机**推导，与后端 OrderStatusFlow 一一对应。
 *
 * 之所以不写死在模板里：后端刚把 1 待交付 / 2 待收货 / 5 已退款 接进状态机，
 * 前端如果继续只认"完成/取消"，这三个状态在界面上就依然不可达（等于没做）。
 * 这里把"当前状态 × 我是谁 → 能做什么"集中成一份数据，模板只负责渲染。
 */
const stateActions = computed(() => {
  const o = order.value
  if (!o) return []
  const acts = []
  const push = (to, label, type, desc = '') => acts.push({ to, label, type, desc })

  if (o.status === 0) {
    // 待交易
    if (isSeller.value) push(1, '标记可交付', 'primary', '标记后进入待交付，买家等待收货')
    push(3, '确认交易完成', 'success', '确认后商品标记为已售出，双方信用分 +1')
    push(4, '取消订单', 'warning')
  } else if (o.status === 1) {
    // 待交付：交付动作由卖家掌握
    if (isSeller.value) push(2, '确认已交付', 'primary', '买家确认后订单完成')
    push(5, '申请退款', 'danger', '退款后商品回到在售')
  } else if (o.status === 2) {
    // 待收货
    push(3, '确认收货', 'success', '确认后商品标记为已售出，双方信用分 +1')
    push(5, '申请退款', 'danger', '退款后商品回到在售')
  }
  return acts
})

/** 终态提示文案 */
const statusHint = computed(() => {
  const s = order.value?.status
  if (s === 3) return '该订单已完成，商品已标记为已售出，双方信用分 +1'
  if (s === 4) return '该订单已取消，商品已重新上架'
  if (s === 5) return '该订单已退款，商品已重新上架'
  return '该订单当前没有可执行的操作'
})

/** 推进订单状态（统一入口：确认 → 调接口 → 刷新） */
async function handleAdvance(action) {
  let reason = ''
  if (action.to === 4 || action.to === 5) {
    try {
      const { value } = await ElMessageBox.prompt(
        action.to === 4 ? '请填写取消原因（选填）' : '请填写退款原因（选填）',
        action.label,
        {
          confirmButtonText: '确认',
          cancelButtonText: '再想想',
          inputPlaceholder: action.to === 4 ? '例如：约不上时间 / 商品与描述不符' : '例如：商品有瑕疵 / 一直没收到',
          inputValue: ''
        }
      )
      reason = value || ''
    } catch (e) {
      return
    }
  } else {
    try {
      await ElMessageBox.confirm(
        action.desc || `${action.label}？`,
        action.label,
        { type: 'warning' }
      )
    } catch (e) {
      return
    }
  }
  try {
    const res = await updateOrderStatus({
      orderId: order.value.id,
      status: action.to,
      cancelReason: reason || undefined
    })
    ElMessage.success(res.message || '操作成功')
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
  <!-- 页面主标题（订单详情） -->
  <h1 class="sr-only">订单详情</h1>
  <StateError
    v-if="loadError && !loading"
    title="加载失败，请稍后重试"
    detail="网络可能不稳定，或服务正在重启"
    retry-text="重新加载"
    @retry="load"
  />
  <div v-else v-loading="loading">
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
            <div
              class="product-block"
              role="link"
              tabindex="0"
              :aria-label="order.productTitle"
              @click="router.push(`/product/${order.productId}`)"
              @keydown.enter.prevent="router.push(`/product/${order.productId}`)"
              @keydown.space.prevent="router.push(`/product/${order.productId}`)"
            >
              <img class="product-img" :src="productImage" :alt="order.productTitle" />
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

            <!-- v0.18：订单完成后可评价（双向）。入口放详情页，不放列表——
                 列表里的按钮在 fixed-right 列中，实测点击不触发处理器。 -->
            <div v-if="order.status === 3" class="eval-entry">
              <div class="eval-hint">交易已完成，可以对交易对方留下评价（每单一次；匿名只隐藏昵称与头像）</div>
              <el-button type="primary" plain @click="openEvaluation">评价这笔交易</el-button>
            </div>

            <div class="actions">
              <template v-if="stateActions.length">
                <el-button
                  v-for="a in stateActions"
                  :key="a.to"
                  :type="a.type"
                  @click="handleAdvance(a)"
                >
                  {{ a.label }}
                </el-button>
              </template>
              <el-alert
                v-else
                class="status-alert"
                :type="order.status === 3 ? 'success' : 'info'"
                :closable="false"
                show-icon
                :title="statusHint"
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

    <EvaluationDialog
      ref="evalDialogRef"
      v-model="evalDialogVisible"
      :order="order"
    />
  </div>
</template>

<style scoped>
.eval-entry {
  margin: 16px 0 4px;
}

.eval-hint {
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--ct-text-muted);
}

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
  background: var(--ct-bg-subtle);
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
  color: var(--ct-price);
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
  color: var(--ct-text-muted);
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