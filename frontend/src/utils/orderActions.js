/**
 * 订单动作的唯一来源（v0.19）
 *
 * 背景：同一份"状态 → 可做什么"的规则曾分散在**详情页**与**列表页**两处，
 * 状态机改动后（移除 0→3 一步完成）两处没同步，导致：
 *   · 详情页已正确（只给买家「确认收货」）
 *   · 列表页仍硬编码「完成」→ 点了被后端拒绝（6005）→ BUG-23
 *
 * 现在：**任何页面要显示订单动作，都必须调用本文件**，不允许各自写 if/else。
 * 纯函数、无 Vue 依赖，可直接用 node 跑单元测试（tests/unit/order-actions.test.mjs）。
 */

/** 订单状态码 */
export const ORDER_STATUS = {
  PENDING: 0, // 待交易
  DELIVERING: 1, // 待交付（卖家已标记可交付）
  RECEIVING: 2, // 待收货（卖家已确认交付）
  FINISHED: 3, // 已完成
  CANCELLED: 4, // 已取消
  REFUNDED: 5 // 已退款
}

/**
 * 某个状态下、某个角色（是否卖家）可以执行的动作。
 *
 * 规则（与后端 OrderStatusFlow 保持一致，改规则时**必须同时改这里和后端**）：
 *   0 待交易 → 卖家：标记可交付(→1)；双方：取消订单(→4)
 *   1 待交付 → 卖家：确认已交付(→2)；双方：申请退款(→5)
 *   2 待收货 → **买家**：确认收货(→3)；双方：申请退款(→5)
 *   3/4/5   → 终态，无动作
 *
 * @param {{ status: number, isSeller: boolean }} ctx
 * @returns {Array<{to:number,label:string,type:string,desc:string,confirmTitle:string,confirmText:string}>}
 */
export function actionsFor({ status, isSeller }) {
  const acts = []
  const push = (to, label, type, desc, confirmTitle, confirmText) =>
    acts.push({ to, label, type, desc, confirmTitle: confirmTitle || label, confirmText: confirmText || desc })

  if (status === ORDER_STATUS.PENDING) {
    if (isSeller) {
      push(
        1,
        '标记可交付',
        'primary',
        '标记后订单进入待交付，买家会看到进度更新',
        '标记可交付',
        '标记后进入「待交付」，买家可看到进度。'
      )
    }
    push(4, '取消订单', 'warning', '取消后商品会重新上架', '取消订单', '取消后商品会重新上架，确定吗？')
  } else if (status === ORDER_STATUS.DELIVERING) {
    if (isSeller) {
      push(
        2,
        '确认已交付',
        'primary',
        '确认后订单进入待收货，由买家确认收货',
        '确认已交付',
        '确认已当面交付？买家随后可确认收货。'
      )
    }
    push(5, '申请退款', 'danger', '退款后商品回到在售', '申请退款', '退款后商品会重新上架，确定吗？')
  } else if (status === ORDER_STATUS.RECEIVING) {
    if (!isSeller) {
      push(
        3,
        '确认收货',
        'success',
        '确认后商品标记为已售出，双方信用分 +1',
        '确认收货',
        '确认已收到商品？确认后商品标记为已售出、双方信用分 +1。'
      )
    }
    push(5, '申请退款', 'danger', '退款后商品回到在售', '申请退款', '退款后商品会重新上架，确定吗？')
  }
  return acts
}

/** 终态/中间态的文案提示（详情页用） */
export function statusHint(status) {
  if (status === ORDER_STATUS.FINISHED) return '该订单已完成，商品已标记为已售出，双方信用分 +1'
  if (status === ORDER_STATUS.CANCELLED) return '该订单已取消，商品已重新上架'
  if (status === ORDER_STATUS.REFUNDED) return '该订单已退款，商品已重新上架'
  if (status === ORDER_STATUS.PENDING) return '等待卖家标记交付；线下当面交易，双方可随时取消'
  if (status === ORDER_STATUS.DELIVERING) return '卖家已标记可交付，等待卖家确认已交付'
  if (status === ORDER_STATUS.RECEIVING) return '卖家已确认交付，等待买家确认收货'
  return '该订单当前没有可执行的操作'
}
