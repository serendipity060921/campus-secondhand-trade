import request from './request'

/**
 * 订单交易接口（v0.08）
 *
 * 全部需要登录（后端 @LoginRequired），Token 由 request.js 自动注入。
 */

/**
 * 创建订单
 * POST /api/order/create
 * @param {{productId: number, deliveryType?: number, tradePlace?: string, buyerRemark?: string}} data
 */
export function createOrder(data) {
  return request({ url: '/order/create', method: 'post', data })
}

/**
 * 修改订单状态
 * PUT /api/order/status
 * @param {{orderId: number, status: number, cancelReason?: string}} data status：3 已完成，4 已取消
 */
export function updateOrderStatus(data) {
  return request({ url: '/order/status', method: 'put', data })
}

/**
 * 我买到的订单
 * GET /api/order/buyList
 */
export function getBuyOrders(params) {
  return request({ url: '/order/buyList', method: 'get', params })
}

/**
 * 我卖出的订单
 * GET /api/order/sellList
 */
export function getSellOrders(params) {
  return request({ url: '/order/sellList', method: 'get', params })
}

/**
 * 订单详情（仅买卖双方可查看）
 * GET /api/order/{id}
 */
export function getOrderDetail(id) {
  return request({ url: `/order/${id}`, method: 'get' })
}
