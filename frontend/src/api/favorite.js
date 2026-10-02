import request from './request'

/**
 * 商品收藏接口（v0.06）
 *
 * 三个接口都需要登录（后端标注了 @LoginRequired），
 * Token 由 request.js 的请求拦截器自动注入。
 */

/**
 * 收藏 / 取消收藏
 * POST /api/favorite/operate
 * @param {{productId: number, type?: number}} data type：1 收藏（默认），2 取消收藏
 * @returns 成功时 res.data = { productId, favorited, favoriteCount }
 */
export function operateFavorite(data) {
  return request({ url: '/favorite/operate', method: 'post', data })
}

/**
 * 我的收藏分页列表（只返回上架商品）
 * GET /api/favorite/list
 */
export function getMyFavorites(params) {
  return request({ url: '/favorite/list', method: 'get', params })
}

/**
 * 当前用户是否已收藏该商品
 * GET /api/favorite/hasFavorite
 */
export function checkFavorite(productId) {
  return request({ url: '/favorite/hasFavorite', method: 'get', params: { productId } })
}
