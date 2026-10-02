import request from './request'

/**
 * 搜索与分类管理接口（v0.09）
 */

/**
 * 商品搜索（公开接口）
 * GET /api/product/search
 * @param {{keyword?: string, categoryId?: number, minPrice?: number, maxPrice?: number,
 *          sort?: string, page?: number, size?: number}} params
 *        sort：new（默认）| priceAsc | priceDesc | hot
 */
export function searchProducts(params) {
  return request({ url: '/product/search', method: 'get', params })
}

/**
 * 分类列表（公开接口，搜索页与发布页的下拉筛选用）
 * GET /api/category/list
 */
export function getSearchCategories(params) {
  return request({ url: '/category/list', method: 'get', params })
}

/**
 * 新增分类（仅管理员，后端 @LoginRequired(admin = true)）
 * POST /api/category/add
 */
export function addCategory(data) {
  return request({ url: '/category/add', method: 'post', data })
}

/**
 * 修改分类（仅管理员）
 * PUT /api/category/update
 */
export function updateCategory(data) {
  return request({ url: '/category/update', method: 'put', data })
}
