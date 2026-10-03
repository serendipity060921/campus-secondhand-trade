import request from './request'

/**
 * 举报接口（v0.13，普通用户）
 */

/**
 * 提交举报
 * @param {{targetType: number, targetId: number, reasonType: number, content?: string, imageUrl?: string}} data
 *        targetType：1 商品 / 2 用户
 *        reasonType：1 虚假信息 / 2 违禁物品 / 3 辱骂骚扰 / 4 其他
 */
export function submitReport(data) {
  return request({ url: '/report/submit', method: 'post', data })
}

/** 我的举报记录 */
export function getMyReports(params) {
  return request({ url: '/report/mine', method: 'get', params })
}
