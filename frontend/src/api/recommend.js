import request from './request'

/**
 * 推荐接口（v0.11）
 *
 * GET /api/product/recommend  猜你喜欢（登录后个性化，未登录热门冷启动）
 * GET /api/product/similar/{id}  相似商品（详情页相关推荐）
 *
 * 说明：两个接口都是公开接口，但若携带 Token 会自动个性化 —— 由 request.js 统一注入，
 * 因此前端无需区分登录状态。
 */

/**
 * 猜你喜欢
 * @param {{size?: number, strategy?: string}} params
 *        strategy：auto（默认）/ hot / content / cf / hybrid / hybrid-cf / hybrid-content
 *        一般不用传，保留给后台做 A/B 实验与离线评测
 */
export function getRecommend(params) {
  return request({ url: '/product/recommend', method: 'get', params })
}

/**
 * 相似商品（相关推荐）
 * @param {number|string} productId 当前商品ID
 * @param {{size?: number}} params
 */
export function getSimilar(productId, params) {
  return request({ url: `/product/similar/${productId}`, method: 'get', params })
}
