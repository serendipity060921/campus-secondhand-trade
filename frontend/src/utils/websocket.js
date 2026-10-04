import { getToken } from './auth'

/**
 * 私信 WebSocket 客户端（v0.14）
 *
 * 目标：把"轮询"换成"服务端推送"，同时保证弱网/断线场景下依然可用。
 *
 * 设计要点：
 *   ① 单例连接：整个应用共用一个 WebSocket（Chat 页面、顶栏角标、会话列表都用它）；
 *   ② 事件订阅：按消息 type 分发（chat/read/online/welcome/pong/error），
 *      业务代码用 on('chat', fn) 订阅，不关心底层连接细节；
 *   ③ 自动重连：指数退避（1s → 2s → 4s … 最多 30s），握手 401 时停止重连并提示重新登录；
 *   ④ 心跳保活：每 25 秒发一次 ping，服务端回 pong 并刷新 Redis 在线时间戳，
 *      防止 Nginx/浏览器把长时间空闲的连接掐掉；
 *   ⑤ 降级：连接不可用时 send() 返回 false，调用方可退回 REST 接口（消息不会丢）。
 */

/** 事件类型 -> 处理函数集合 */
const listeners = new Map()
/** 连接状态变化的订阅者 */
const stateListeners = new Set()

const state = {
  connected: false,
  connecting: false,
  attempts: 0,
  lastError: '',
  lastMessageAt: null
}

let socket = null
let heartbeatTimer = null
let reconnectTimer = null
let manualClose = false

/** 心跳间隔（毫秒）：小于服务端 90 秒的在线窗口，也小于常见网关的 60 秒空闲超时 */
const HEARTBEAT_INTERVAL = 25000
/** 重连退避上限 */
const MAX_RECONNECT_DELAY = 30000

/** 拼接 WebSocket 地址：走同源（Vite/生产 Nginx 代理 /ws → 后端 8080） */
function buildUrl() {
  const token = getToken()
  if (!token) {
    return null
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/chat?token=${encodeURIComponent(token)}`
}

function notifyState() {
  stateListeners.forEach((fn) => {
    try {
      fn({ ...state })
    } catch (e) {
      /* 忽略订阅者异常 */
    }
  })
}

function emit(type, payload) {
  const handlers = listeners.get(type)
  if (handlers) {
    handlers.forEach((fn) => {
      try {
        fn(payload)
      } catch (e) {
        console.error(`[WS] 处理 ${type} 事件出错`, e)
      }
    })
  }
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

function startHeartbeat() {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    send({ type: 'ping' })
  }, HEARTBEAT_INTERVAL)
}

function scheduleReconnect() {
  if (manualClose) {
    return
  }
  state.attempts += 1
  const delay = Math.min(1000 * 2 ** (state.attempts - 1), MAX_RECONNECT_DELAY)
  console.warn(`[WS] ${delay}ms 后第 ${state.attempts} 次重连`)
  reconnectTimer = setTimeout(connect, delay)
}

/**
 * 建立连接（已连接时直接返回 true）
 * @returns {boolean} 是否发起了连接
 */
export function connect() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return true
  }
  const url = buildUrl()
  if (!url) {
    state.lastError = '未登录，无法建立实时连接'
    notifyState()
    return false
  }
  manualClose = false
  state.connecting = true
  notifyState()

  try {
    socket = new WebSocket(url)
  } catch (e) {
    state.connecting = false
    state.lastError = e.message
    notifyState()
    scheduleReconnect()
    return false
  }

  socket.onopen = () => {
    state.connected = true
    state.connecting = false
    state.attempts = 0
    state.lastError = ''
    notifyState()
    startHeartbeat()
    emit('open', {})
    console.info('[WS] 实时连接已建立')
  }

  socket.onmessage = (event) => {
    let msg
    try {
      msg = JSON.parse(event.data)
    } catch (e) {
      return
    }
    state.lastMessageAt = Date.now()
    // 先按具体类型分发，再发给通配订阅者（便于统一日志/调试）
    emit(msg.type, msg)
    emit('*', msg)
  }

  socket.onerror = () => {
    state.lastError = '实时连接异常'
    notifyState()
  }

  socket.onclose = (event) => {
    const wasConnected = state.connected
    state.connected = false
    state.connecting = false
    stopHeartbeat()
    notifyState()
    emit('close', { code: event.code, reason: event.reason })
    // 401/403：Token 失效或被禁用，重连没有意义，交给页面处理
    if (event.code === 1006 && state.attempts > 5) {
      console.warn('[WS] 多次重连失败，停止重连')
      return
    }
    if (wasConnected || state.attempts < 8) {
      scheduleReconnect()
    }
  }

  return true
}

/**
 * 发送一帧数据
 * @returns {boolean} true = 已交给浏览器发送；false = 连接不可用（调用方应降级）
 */
export function send(payload) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return false
  }
  try {
    socket.send(JSON.stringify(payload))
    return true
  } catch (e) {
    return false
  }
}

/** 主动关闭（退出登录时调用，不再自动重连） */
export function close() {
  manualClose = true
  stopHeartbeat()
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (socket) {
    try {
      socket.close(1000, 'client logout')
    } catch (e) {
      /* 忽略 */
    }
    socket = null
  }
  state.connected = false
  state.connecting = false
  state.attempts = 0
  notifyState()
  listeners.clear()
  stateListeners.clear()
}

/**
 * 订阅某类消息
 * @param {string} type 消息类型；'*' 表示所有消息
 * @param {Function} handler
 * @returns {Function} 取消订阅
 */
export function on(type, handler) {
  if (!listeners.has(type)) {
    listeners.set(type, new Set())
  }
  listeners.get(type).add(handler)
  return () => off(type, handler)
}

/** 取消订阅 */
export function off(type, handler) {
  const handlers = listeners.get(type)
  if (handlers) {
    handlers.delete(handler)
  }
}

/** 订阅连接状态变化 */
export function onStateChange(handler) {
  stateListeners.add(handler)
  handler({ ...state })
  return () => stateListeners.delete(handler)
}

export function isConnected() {
  return state.connected
}

export function getState() {
  return { ...state }
}

export default { connect, close, send, on, off, onStateChange, isConnected, getState }
