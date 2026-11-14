/**
 * 订单动作单元测试（node 直跑，无需浏览器）
 *
 * 这个文件存在的意义：BUG-23 的根因是"同一份规则写在两处、改一处漏另一处"。
 * 现在规则集中在 utils/orderActions.js，用它做**规则级**断言，
 * 而不是等界面点错才发现。
 *
 * 运行：node tests/unit/order-actions.test.mjs
 */
import assert from 'node:assert/strict'
import { actionsFor, statusHint, ORDER_STATUS } from '../../frontend/src/utils/orderActions.js'

let n = 0
function t(name, fn) {
  fn()
  n += 1
  console.log('  ✓ ' + name)
}

const tos = (acts) => acts.map((a) => a.to)
const labels = (acts) => acts.map((a) => a.label)

console.log('[orderActions] 订单动作规则')

t('待交易·卖家：标记可交付 + 取消，且没有「完成」', () => {
  const acts = actionsFor({ status: ORDER_STATUS.PENDING, isSeller: true })
  assert.deepEqual(tos(acts), [1, 4])
  assert.ok(!labels(acts).some((l) => l.includes('完成')), '待交易不应出现"完成"动作')
})

t('待交易·买家：只能取消（不能下单即完成）', () => {
  const acts = actionsFor({ status: ORDER_STATUS.PENDING, isSeller: false })
  assert.deepEqual(tos(acts), [4])
  assert.equal(acts[0].label, '取消订单')
})

t('待交付·卖家：确认已交付 + 申请退款', () => {
  const acts = actionsFor({ status: ORDER_STATUS.DELIVERING, isSeller: true })
  assert.deepEqual(tos(acts), [2, 5])
})

t('待交付·买家：没有"确认已交付"（卖家专属），可申请退款', () => {
  const acts = actionsFor({ status: ORDER_STATUS.DELIVERING, isSeller: false })
  assert.deepEqual(tos(acts), [5])
})

t('待收货·买家：确认收货 + 申请退款', () => {
  const acts = actionsFor({ status: ORDER_STATUS.RECEIVING, isSeller: false })
  assert.deepEqual(tos(acts), [5, 3].sort((a, b) => (a === 3 ? 1 : -1)) && [3, 5])
})

t('待收货·卖家：不能替买家确认收货', () => {
  const acts = actionsFor({ status: ORDER_STATUS.RECEIVING, isSeller: true })
  assert.ok(!tos(acts).includes(3), '卖家不应有确认收货动作')
})

t('终态（已完成/已取消/已退款）：没有任何动作', () => {
  for (const s of [ORDER_STATUS.FINISHED, ORDER_STATUS.CANCELLED, ORDER_STATUS.REFUNDED]) {
    assert.deepEqual(actionsFor({ status: s, isSeller: true }), [], `status=${s} 卖家不应有动作`)
    assert.deepEqual(actionsFor({ status: s, isSeller: false }), [], `status=${s} 买家不应有动作`)
  }
})

t('每个动作都带确认文案（弹窗要用）', () => {
  for (const s of [0, 1, 2]) {
    for (const seller of [true, false]) {
      for (const a of actionsFor({ status: s, isSeller: seller })) {
        assert.ok(a.confirmTitle && a.confirmText, `status=${s} isSeller=${seller} 动作 ${a.label} 缺确认文案`)
      }
    }
  }
})

t('状态提示文案：6 个状态都有话可说', () => {
  for (const s of [0, 1, 2, 3, 4, 5]) {
    const hint = statusHint(s)
    assert.ok(hint && hint.length > 4, `status=${s} 提示文案过短`)
  }
})

console.log(`\n[orderActions] 通过 ${n} / ${n}`)
