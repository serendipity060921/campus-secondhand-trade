import request from './request'

/**
 * 双向评价接口（v0.18 / 第6批-㉑）
 *
 * 后端业务码段 9xxx：
 *   9002 非买卖双方 / 9003 订单未完成 / 9004 已评价过 / 9005 评分越界
 */

/**
 * 提交评价
 * POST /api/evaluation
 * @param {{orderId:number, rating:number, content?:string, tags?:string, anonymous?:number}} data
 */
export function submitEvaluation(data) {
  return request({ url: '/evaluation', method: 'post', data })
}

/**
 * 某商品的评价列表（公开）
 * GET /api/evaluation/product/{productId}
 */
export function getProductEvaluations(productId, params) {
  return request({ url: `/evaluation/product/${productId}`, method: 'get', params })
}

/**
 * 某人收到的评价（公开）
 * GET /api/evaluation/received/{userId}
 */
export function getReceivedEvaluations(userId, params) {
  return request({ url: `/evaluation/received/${userId}`, method: 'get', params })
}

/**
 * 我发出的评价
 * GET /api/evaluation/mine
 */
export function getMyEvaluations(params) {
  return request({ url: '/evaluation/mine', method: 'get', params })
}

/**
 * 某商品的评价汇总（平均分 / 好评率 / 星级分布，公开）
 * GET /api/evaluation/summary/product/{productId}
 */
export function getProductEvaluationSummary(productId) {
  return request({ url: `/evaluation/summary/product/${productId}`, method: 'get' })
}

/**
 * 某订单下的评价（用于判断"我评过没有"，仅买卖双方可见）
 * GET /api/evaluation/order/{orderId}
 */
export function getOrderEvaluations(orderId) {
  return request({ url: `/evaluation/order/${orderId}`, method: 'get' })
}

/** 评价标签候选（前后端都用自由文本，这里只是给用户少打字） */
export const EVALUATION_TAGS = [
  '描述相符',
  '态度很好',
  '沟通顺畅',
  '发货及时',
  '物超所值',
  '守约准时'
]
