import request from './request'

/**
 * 私信聊天接口（v0.07）
 *
 * 所有接口都需要登录（后端 @LoginRequired），Token 由 request.js 自动注入。
 */

/**
 * 发送私信
 * POST /api/message/send
 * @param {{toUserId: number, content: string, productId?: number}} data
 */
export function sendMessage(data) {
  return request({ url: '/message/send', method: 'post', data })
}

/**
 * 会话列表（聊天对象 + 最后一条消息 + 未读数）
 * GET /api/message/conversationList
 */
export function getConversationList() {
  return request({ url: '/message/conversationList', method: 'get' })
}

/**
 * 与某个用户的聊天记录（分页，最新在前）
 * GET /api/message/history
 * @param {{peerId: number, page?: number, size?: number}} params
 */
export function getMessageHistory(params) {
  return request({ url: '/message/history', method: 'get', params })
}

/**
 * 标记已读
 * PUT /api/message/read
 * @param {{peerId?: number, messageIds?: number[]}} data peerId：把该聊天对象发给我的消息全部标记已读
 */
export function markMessageRead(data) {
  return request({ url: '/message/read', method: 'put', data })
}

/**
 * 聊天对象公开信息（昵称/头像/校区/信用分）
 * GET /api/message/peer
 */
export function getChatPeer(peerId) {
  return request({ url: '/message/peer', method: 'get', params: { peerId } })
}
