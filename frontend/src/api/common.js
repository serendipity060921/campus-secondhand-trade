import request from './request'

/**
 * 公共/自检接口（脚手架阶段用于验证前后端连通）
 * 注意：baseURL 已包含 /api，因此这里不再重复写 /api
 */

/** 后端存活检查 GET /api/health */
export function getHealth() {
  return request({ url: '/health', method: 'get' })
}

/** 数据库连通性检查 GET /api/health/db */
export function getDbHealth() {
  return request({ url: '/health/db', method: 'get' })
}

/** 分类列表 GET /api/categories */
export function getCategories(params) {
  return request({ url: '/categories', method: 'get', params })
}

/** 商品分页 GET /api/products */
export function getProducts(params) {
  return request({ url: '/products', method: 'get', params })
}

/**
 * 登录（后端接口将在 v0.04 里程碑实现，这里先占位，方便后续直接调用）
 * POST /api/auth/login
 */
export function login(data) {
  return request({ url: '/auth/login', method: 'post', data })
}

/** 退出登录 POST /api/auth/logout */
export function logout() {
  return request({ url: '/auth/logout', method: 'post' })
}
