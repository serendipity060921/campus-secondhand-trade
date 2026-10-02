import request from './request'

/**
 * 用户模块接口（v0.04）
 *
 * 注意：request.js 已配置 baseURL = '/api'，因此这里路径不再重复写 /api。
 * 请求头 Token 由 request.js 的请求拦截器自动注入，无需在此处理。
 */

/**
 * 用户注册
 * POST /api/user/register
 * @param {{username: string, password: string, nickname: string}} data
 */
export function registerUser(data) {
  return request({
    url: '/user/register',
    method: 'post',
    data
  })
}

/**
 * 用户登录
 * POST /api/user/login
 * @param {{username: string, password: string}} data
 * @returns 成功时 res.data = { token, tokenType, expiresIn, userInfo }
 */
export function loginUser(data) {
  return request({
    url: '/user/login',
    method: 'post',
    data
  })
}

/**
 * 查询当前登录用户信息（需要 Token，后端标注 @LoginRequired）
 * GET /api/user/info
 */
export function getUserInfo() {
  return request({
    url: '/user/info',
    method: 'get'
  })
}

/**
 * 退出登录（需要 Token）
 * POST /api/user/logout
 */
export function logoutUser() {
  return request({
    url: '/user/logout',
    method: 'post'
  })
}
