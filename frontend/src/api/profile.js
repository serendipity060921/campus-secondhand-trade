import request from './request'

/**
 * 个人资料接口（v0.09）
 *
 * 说明：
 *   GET  /api/user/profile  查询自己的完整资料（含手机号/邮箱/真实姓名）
 *   PUT  /api/user/update   修改资料（只提交要改的字段）
 *   POST /api/user/avatar   上传头像（multipart）
 *
 * 与 v0.04 的 api/user.js 区分：那边是登录注册与对外展示用的用户信息，
 * 这边是"个人中心"里的资料维护，返回的是含隐私字段的 UserProfileVO。
 */

/** 查询自己的完整资料（需要 Token） */
export function getProfile() {
  return request({ url: '/user/profile', method: 'get' })
}

/**
 * 修改个人资料（需要 Token）
 * @param {{nickname?: string, avatar?: string, realName?: string, gender?: number,
 *          school?: string, campus?: string, phone?: string, email?: string}} data
 */
export function updateProfile(data) {
  return request({ url: '/user/update', method: 'put', data })
}

/**
 * 上传头像（需要 Token）
 *
 * 注意：必须显式声明 multipart/form-data，
 * 否则 axios 会因为实例默认的 application/json 把 FormData 转成 JSON。
 *
 * @param {FormData} formData 字段名固定为 file
 */
export function uploadAvatar(formData) {
  return request({
    url: '/user/avatar',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
