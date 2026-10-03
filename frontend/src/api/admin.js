import request from './request'

/**
 * 管理后台接口（v0.13）
 *
 * 说明：这些接口全部映射在 /api/admin 下，后端 AdminController 类级别标注
 * @LoginRequired(admin = true)，非管理员访问统一返回 403。
 * 前端路由守卫也会拦截非管理员，二者形成双重校验。
 */

/** 数据看板：概览指标 + 近 7 天趋势 + 分类分布 + 状态分布 */
export function getDashboard() {
  return request({ url: '/admin/dashboard', method: 'get' })
}

/**
 * 商品列表（含全部状态）
 * @param {{page?: number, size?: number, status?: number, keyword?: string, sellerId?: number}} params
 */
export function getAdminProducts(params) {
  return request({ url: '/admin/product/list', method: 'get', params })
}

/**
 * 商品审核
 * @param {{productId: number, approve: boolean, remark?: string}} data
 */
export function auditProduct(data) {
  return request({ url: '/admin/product/audit', method: 'put', data })
}

/** 强制下架 */
export function offlineProduct(productId, reason) {
  return request({ url: '/admin/product/offline', method: 'put', params: { productId, reason } })
}

/**
 * 用户列表
 * @param {{page?: number, size?: number, keyword?: string, role?: number, status?: number}} params
 */
export function getAdminUsers(params) {
  return request({ url: '/admin/user/list', method: 'get', params })
}

/**
 * 启用 / 禁用用户
 * @param {{userId: number, status: number}} data status：1 正常 / 0 禁用
 */
export function changeUserStatus(data) {
  return request({ url: '/admin/user/status', method: 'put', data })
}

/**
 * 举报列表
 * @param {{page?: number, size?: number, status?: number}} params
 */
export function getAdminReports(params) {
  return request({ url: '/admin/report/list', method: 'get', params })
}

/**
 * 处理举报
 * @param {{reportId: number, action: number, result?: string}} data action：1 处理 / 2 忽略
 */
export function handleReport(data) {
  return request({ url: '/admin/report/handle', method: 'put', data })
}

/** 管理员操作日志 */
export function getAdminLogs(params) {
  return request({ url: '/admin/log/list', method: 'get', params })
}
