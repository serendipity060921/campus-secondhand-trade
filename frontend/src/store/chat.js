import { defineStore } from 'pinia'
import { ElNotification } from 'element-plus'
import chatSocket from '@/utils/websocket'
import { getMessageUnreadTotal, getOnlineStatus } from '@/api/message'
import { useUserStore } from '@/store/user'

/**
 * 实时私信状态（v0.14）
 *
 * 集中管理"跨页面"的实时状态，避免每个页面各自连一条 WebSocket：
 *   · connected      实时连接是否可用（页面据此决定用推送还是轮询兜底）
 *   · unreadTotal    未读私信总数（顶栏角标）
 *   · onlineUsers    userId -> 是否在线（会话列表、聊天窗口顶部展示）
 *   · lastMessage    最近收到的一条私信（用于全局弹窗提醒）
 *
 * 消息去重由页面负责（聊天页按 id 判断），这里只做"状态 + 提醒"。
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    connected: false,
    initialized: false,
    unreadTotal: 0,
    onlineUsers: {},
    lastMessage: null,
    /** 当前正在聊天的对象（在该对象的聊天页时，新消息不弹全局提醒，也不计未读） */
    activePeerId: null
  }),

  getters: {
    isOnline: (state) => (userId) => state.onlineUsers[userId] === true,
    onlineCount: (state) => Object.values(state.onlineUsers).filter(Boolean).length
  },

  actions: {
    /** 登录后调用一次：建立连接并注册全局事件处理 */
    init() {
      if (this.initialized) {
        chatSocket.connect()
        return
      }
      this.initialized = true

      chatSocket.onStateChange((s) => {
        this.connected = s.connected
      })

      // 连接成功时服务端会先下发 welcome（含未读总数），用它校准角标
      chatSocket.on('welcome', (msg) => {
        this.unreadTotal = msg.data?.unreadTotal || 0
      })

      // 新私信
      chatSocket.on('chat', (msg) => {
        const data = msg.data
        if (!data) return
        this.lastMessage = data
        const myId = useUserStore().userInfo?.id
        // 自己发出的消息回显 → 不计未读、不弹提醒
        if (data.toUserId !== myId) {
          return
        }
        // 正在和这个人聊天 → 聊天页会立刻标记已读，这里不累加也不弹窗
        if (this.activePeerId && data.fromUserId === this.activePeerId) {
          return
        }
        this.unreadTotal += 1
        ElNotification({
          title: `新私信来自 ${data.fromNickname || '同学'}`,
          message: data.content?.length > 40 ? `${data.content.slice(0, 40)}…` : data.content,
          type: 'info',
          duration: 4000,
          onClick: () => {
            window.location.href = `/chat/${data.fromUserId}`
          }
        })
      })

      // 在线状态（可能是单条对象，也可能是批量数组）
      chatSocket.on('online', (msg) => {
        const data = msg.data
        if (Array.isArray(data)) {
          const next = { ...this.onlineUsers }
          data.forEach((item) => {
            next[item.userId] = item.online
          })
          this.onlineUsers = next
        } else if (data && data.userId) {
          this.onlineUsers = { ...this.onlineUsers, [data.userId]: data.online }
        }
      })

      // 连接关闭：清空在线状态，避免显示"对方在线"但实际已断开
      chatSocket.on('close', () => {
        this.onlineUsers = {}
      })

      chatSocket.connect()
      this.fetchUnreadTotal()
    },

    /** 退出登录时调用 */
    reset() {
      chatSocket.close()
      this.initialized = false
      this.connected = false
      this.unreadTotal = 0
      this.onlineUsers = {}
      this.lastMessage = null
      this.activePeerId = null
    },

    /** 从服务端同步未读总数（发送/已读后调用，保证角标准确） */
    async fetchUnreadTotal() {
      try {
        const res = await getMessageUnreadTotal()
        this.unreadTotal = res.data || 0
      } catch (e) {
        /* 未登录或接口异常时忽略 */
      }
    },

    /** 通过 WebSocket 发送私信；返回 false 表示连接不可用，调用方需降级到 REST */
    sendBySocket({ toUserId, content, productId }) {
      return chatSocket.send({
        type: 'chat',
        toUserId,
        content,
        productId: productId || undefined
      })
    },

    /** 通过 WebSocket 标记已读 */
    readBySocket({ peerId, messageIds }) {
      return chatSocket.send({
        type: 'read',
        peerId: peerId || undefined,
        messageIds: messageIds || undefined
      })
    },

    /** 批量查询在线状态（进入会话列表/聊天页时调用一次；连接不可用时降级为 REST） */
    async queryOnline(userIds) {
      if (!userIds || userIds.length === 0) {
        return
      }
      if (chatSocket.send({ type: 'queryOnline', userIds })) {
        return
      }
      const next = { ...this.onlineUsers }
      for (const id of userIds) {
        try {
          const res = await getOnlineStatus(id)
          next[id] = res.data.online
        } catch (e) {
          /* 忽略单个失败 */
        }
      }
      this.onlineUsers = next
    },

    setActivePeer(peerId) {
      this.activePeerId = peerId
    }
  }
})
