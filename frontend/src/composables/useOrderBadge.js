import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElNotification } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getBuyOrders, getSellOrders } from '@/api/order'

/**
 * 订单待处理徽标 + 新订单提醒
 *
 * 背景（学生反馈）：买家下单后，卖家那边"没有提醒也没有入口感" ——
 * 卖家不知道自己有单要处理。这里在顶栏「订单」上显示待办数量，
 * 并在数量增加时弹一次通知（点击直达对应列表）。
 *
 * 口径：
 *   · 卖家待办 = 我的订单里 status ∈ {0 待交易, 1 待交付}（需要我标记交付）
 *   · 买家待办 = status = 2 待收货（需要我确认收货）
 *
 * 为什么不用后端新接口：仅新增统计接口会拉低后端覆盖率、且演示前不宜动状态机周边；
 * 两条列表请求每 30s 一次，对演示规模完全够用。
 */
const POLL_MS = 30000

export function useOrderBadge() {
  const userStore = useUserStore()
  const sellPending = ref(0)
  const buyPending = ref(0)
  const ready = ref(false)

  const total = computed(() => sellPending.value + buyPending.value)

  /**
   * 点「订单」应该进哪个列表：
   *   · 有卖出待办 → 我卖出的（我需要标记交付）
   *   · 否则有买入待办 → 我买到的（我需要确认收货）
   *   · 都没有 → 我买到的（默认）
   * 这样徽标数字与落地的列表一致，不会出现"有 1 个待办但列表是空的"。
   */
  const orderEntryPath = computed(() =>
    sellPending.value > 0 ? '/orders/sold' : '/orders/bought')

  /** 悬停提示：把卖出/买入拆开说清楚 */
  const orderHint = computed(() => {
    if (total.value === 0) return '订单'
    const parts = []
    if (sellPending.value > 0) parts.push(`卖出待处理 ${sellPending.value}`)
    if (buyPending.value > 0) parts.push(`买入待收货 ${buyPending.value}`)
    return `订单：${parts.join('，')}（点击进入）`
  })

  let timer = null
  let lastSell = 0
  let lastBuy = 0
  let firstRun = true

  async function refresh() {
    if (!userStore.isLogin) return
    try {
      const [sell, buy] = await Promise.all([
        getSellOrders({ page: 1, size: 100 }),
        getBuyOrders({ page: 1, size: 100 }),
      ])
      const sellRows = sell?.data?.records || []
      const buyRows = buy?.data?.records || []
      sellPending.value = sellRows.filter((o) => o.status === 0 || o.status === 1).length
      buyPending.value = buyRows.filter((o) => o.status === 2).length
      ready.value = true

      // 数量增加时提醒（首次加载不提醒，避免一登录就弹）
      if (!firstRun) {
        if (sellPending.value > lastSell) {
          ElNotification({
            title: '你有新的订单待处理',
            message: `${sellPending.value} 笔订单等待你标记交付，点击查看「我卖出的」`,
            type: 'success',
            duration: 8000,
            onClick: () => { window.location.href = '/orders/sold' },
          })
        } else if (buyPending.value > lastBuy) {
          ElNotification({
            title: '有订单等待你确认收货',
            message: `${buyPending.value} 笔订单已由卖家交付，点击查看「我买到的」`,
            type: 'info',
            duration: 8000,
            onClick: () => { window.location.href = '/orders/bought' },
          })
        }
      }
      lastSell = sellPending.value
      lastBuy = buyPending.value
      firstRun = false
    } catch (e) {
      // 静默失败：徽标是辅助信息，不应打扰主流程
    }
  }

  onMounted(() => {
    refresh()
    timer = window.setInterval(refresh, POLL_MS)
  })
  onBeforeUnmount(() => {
    if (timer) window.clearInterval(timer)
  })

  return { total, sellPending, buyPending, ready, refresh, orderEntryPath, orderHint }
}
