<script setup>
/**
 * 聊天窗口页（v0.07 建立，v0.14 改为 WebSocket 实时通信）
 *
 * 路由：/chat/:userId?productId=xxx
 *   - :userId    聊天对象ID（从"私聊卖家"进入时即卖家ID）
 *   - productId  可选，从商品详情页进入时带上的关联商品
 *
 * 功能：
 *   ① 加载对方信息 + 最近 20 条聊天记录（后端返回最新在前，这里翻转成正序展示）
 *   ② 打开即把对方发给我的消息标记为已读，并订阅对方的已读回执（自己发的消息显示"已读"）
 *   ③ 上滑/点按钮加载更早的历史消息（分页）
 *   ④ 输入框发送消息（Enter 发送，Shift+Enter 换行）——优先走 WebSocket，失败降级 REST
 *   ⑤ v0.14：消息由服务端推送（不再 5 秒轮询）；WebSocket 断开时每 15 秒轮询兜底
 *   ⑥ v0.14：顶部显示对方"在线/离线"状态（Redis 心跳判定）
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getChatPeer, getMessageHistory, markMessageRead, sendMessage } from '@/api/message'
import { useUserStore } from '@/store/user'
import { useChatStore } from '@/store/chat'
import chatSocket from '@/utils/websocket'
import StateError from '@/components/states/StateError.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

const PEER_ID = Number(route.params.userId)
const PRODUCT_ID = route.query.productId ? Number(route.query.productId) : null
const PAGE_SIZE = 20

const loading = ref(false)
const loadError = ref(false)
const sending = ref(false)
const loadingMore = ref(false)
const peer = ref(null)
const peerOnline = ref(false)
const messages = ref([]) // 正序：旧 -> 新
const total = ref(0)
const loadedPages = ref(0)
const inputContent = ref('')
const listRef = ref(null)

const myId = computed(() => userStore.userInfo?.id)
const hasMore = computed(() => messages.value.length < total.value)

/** 是否是我发出的消息 */
function isMine(msg) {
  return msg.fromUserId === myId.value
}

/** 我发出的最后一条消息的下标：只在这一条上显示已读状态，避免每条都挂标签 */
const lastMineIndex = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (isMine(messages.value[i])) return i
  }
  return -1
})

/**
 * 商品引用条只在"换了商品"的那条消息上显示。
 * 原先每条带 productId 的消息都挂一条「关于：xxx」，同一商品连发三条就重复三遍。
 */
function showProductRef(index) {
  const msg = messages.value[index]
  if (!msg || !msg.productId || !msg.productTitle) return false
  const prev = messages.value[index - 1]
  return !prev || prev.productId !== msg.productId
}

/** 时间显示：HH:mm */
function timeOf(time) {
  return time ? String(time).slice(11, 16) : ''
}

/**
 * 是否需要在消息上方显示时间分隔（第一条，或与上一条间隔超过 5 分钟）
 */
function showTimeDivider(index) {
  if (index === 0) return true
  const prev = messages.value[index - 1]
  const cur = messages.value[index]
  const prevTime = new Date(String(prev.createTime).replace(' ', 'T')).getTime()
  const curTime = new Date(String(cur.createTime).replace(' ', 'T')).getTime()
  return curTime - prevTime > 5 * 60 * 1000
}

async function scrollToBottom() {
  await nextTick()
  if (listRef.value) {
    listRef.value.scrollTop = listRef.value.scrollHeight
  }
}

async function loadPeer() {
  try {
    const res = await getChatPeer(PEER_ID)
    peer.value = res.data
    if (res.data.self) {
      ElMessage.warning('这是你自己，无法给自己发消息')
    }
  } catch (e) {
    /* 接收人不存在等错误由拦截器提示 */
  }
}

/** 首次加载：最近一页 + 标记已读 */
async function loadFirst() {
  loading.value = true
  loadError.value = false
  try {
    const res = await getMessageHistory({ peerId: PEER_ID, page: 1, size: PAGE_SIZE })
    total.value = res.data.total || 0
    messages.value = (res.data.records || []).slice().reverse()
    loadedPages.value = 1
    await markReadIfNeeded()
    await scrollToBottom()
    // 从商品详情页带 productId 进来且还没有聊过天 → 预填一句开场白
    if (PRODUCT_ID && messages.value.length === 0) {
      inputContent.value = '你好，这件商品还在吗？'
    }
  } catch (e) {
    messages.value = []
    loadError.value = true
    } finally {
    loading.value = false
  }
}

/** 加载更早的消息（第 2、3…页） */
async function loadMore() {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  const container = listRef.value
  const oldHeight = container ? container.scrollHeight : 0
  try {
    const nextPage = loadedPages.value + 1
    const res = await getMessageHistory({ peerId: PEER_ID, page: nextPage, size: PAGE_SIZE })
    const older = (res.data.records || []).slice().reverse()
    messages.value = older.concat(messages.value)
    loadedPages.value = nextPage
    // 保持滚动位置，避免加载历史后视图跳动
    await nextTick()
    if (container) {
      container.scrollTop = container.scrollHeight - oldHeight
    }
  } catch (e) {
    /* 忽略 */
  } finally {
    loadingMore.value = false
  }
}

/** 把对方发给我的未读消息标记为已读（优先 WebSocket，失败降级 REST） */
async function markReadIfNeeded() {
  const hasUnread = messages.value.some((m) => !isMine(m) && m.isRead === 0)
  if (!hasUnread) {
    return
  }
  const viaSocket = chatStore.readBySocket({ peerId: PEER_ID })
  if (!viaSocket) {
    try {
      await markMessageRead({ peerId: PEER_ID })
    } catch (e) {
      return
    }
  }
  messages.value.forEach((m) => {
    if (!isMine(m)) m.isRead = 1
  })
  // 角标与服务端对齐（自己已读的消息要从"未读总数"里扣掉）
  chatStore.fetchUnreadTotal()
}

/**
 * 轮询兜底（v0.14）：只在 WebSocket 不可用时启用，避免"实时通道断了就收不到消息"。
 * 连接正常时完全依赖服务端推送，不再有定时请求。
 */
async function pollNew() {
  if (chatStore.connected) {
    return
  }
  try {
    const res = await getMessageHistory({ peerId: PEER_ID, page: 1, size: PAGE_SIZE })
    const known = new Set(messages.value.map((m) => m.id))
    const fresh = (res.data.records || []).filter((m) => !known.has(m.id)).reverse()
    total.value = res.data.total || total.value
    if (fresh.length > 0) {
      messages.value = messages.value.concat(fresh)
      await markReadIfNeeded()
      await scrollToBottom()
    }
  } catch (e) {
    /* 静默失败，避免刷屏 */
  }
}

/* ---------------- v0.14 实时消息处理 ---------------- */

/** 服务端推送新私信：只处理与当前聊天对象相关的，其余交给全局角标 */
function onIncoming(msg) {
  const data = msg.data
  if (!data || (data.fromUserId !== PEER_ID && data.toUserId !== PEER_ID)) {
    return
  }
  if (messages.value.some((m) => m.id === data.id)) {
    return
  }
  messages.value.push(data)
  total.value += 1
  loadedPages.value = Math.max(loadedPages.value, 1)
  scrollToBottom()
  // 对方发来的消息：立即标记已读（走 WebSocket，失败则退回 REST）
  if (!isMine(data)) {
    markReadIfNeeded()
  }
}

/** 对方已读回执：把我发出的消息标记为已读 */
function onReadReceipt(msg) {
  const data = msg.data
  if (!data || data.readerId !== PEER_ID) {
    return
  }
  messages.value.forEach((m) => {
    if (isMine(m)) m.isRead = 1
  })
}

/** 在线状态变化 */
function onOnline(msg) {
  const data = msg.data
  if (Array.isArray(data)) {
    data.forEach((item) => {
      if (item.userId === PEER_ID) peerOnline.value = item.online
    })
  } else if (data && data.userId === PEER_ID) {
    peerOnline.value = data.online
  }
}

let unsubscribe = []
let timer = null

onMounted(async () => {
  chatStore.setActivePeer(PEER_ID)
  await loadPeer()
  await loadFirst()
  // 订阅实时事件 + 查询对方在线状态
  unsubscribe.push(chatSocket.on('chat', onIncoming))
  unsubscribe.push(chatSocket.on('read', onReadReceipt))
  unsubscribe.push(chatSocket.on('online', onOnline))
  chatStore.queryOnline([PEER_ID])
  // 兜底轮询：连接可用时函数内部会直接返回，不产生请求
  timer = setInterval(pollNew, 15000)
})

onUnmounted(() => {
  unsubscribe.forEach((fn) => fn())
  unsubscribe = []
  if (timer) clearInterval(timer)
  chatStore.setActivePeer(null)
})

async function handleSend() {
  const content = inputContent.value.trim()
  if (!content) {
    ElMessage.warning('请输入消息内容')
    return
  }
  if (peer.value?.self) {
    ElMessage.warning('不能给自己发送消息')
    return
  }
  sending.value = true
  try {
    // v0.14：优先走 WebSocket 实时通道（服务端入库后会推给双方），
    // 连接不可用（未建立/断线）时降级为 REST 接口，保证消息不丢
    const viaSocket = chatStore.sendBySocket({
      toUserId: PEER_ID,
      content,
      productId: PRODUCT_ID
    })
    if (!viaSocket) {
      const res = await sendMessage({
        toUserId: PEER_ID,
        content,
        productId: PRODUCT_ID || undefined
      })
      if (!messages.value.some((m) => m.id === res.data.id)) {
        messages.value.push(res.data)
        total.value += 1
        loadedPages.value = Math.max(loadedPages.value, 1)
      }
      await scrollToBottom()
    }
    inputContent.value = ''
  } catch (e) {
    /* 错误提示由 axios 拦截器统一处理 */
  } finally {
    sending.value = false
  }
}

/** Enter 发送，Shift+Enter 换行 */
function onEnter(event) {
  if (event.shiftKey) return
  event.preventDefault()
  handleSend()
}

</script>

<template>
  <!-- 页面主标题（会话对象是动态值，视觉上由会话头承担） -->
  <h1 class="sr-only">与 {{ peer?.nickname || '对方' }} 的聊天</h1>
  <StateError
    v-if="loadError && !loading"
    title="加载失败，请稍后重试"
    detail="网络可能不稳定，或服务正在重启"
    retry-text="重新加载"
    @retry="loadFirst"
  />
  <div v-else class="chat-page" v-loading="loading">
    <el-card shadow="never" :body-style="{ padding: '0' }">
      <!-- 顶部：对方信息 -->
      <div class="chat-header">
        <el-button text @click="router.back()">← 返回</el-button>
        <el-avatar :size="40">{{ (peer?.nickname || '匿').slice(0, 1) }}</el-avatar>
        <div class="peer-info">
          <div class="peer-name">
            {{ peer?.nickname || '加载中…' }}
            <el-tag v-if="peer?.self" size="small" type="warning">这是你自己</el-tag>
          </div>
          <div class="peer-meta">
            <!-- v0.14：在线状态（Redis 心跳判定，WebSocket 实时更新） -->
            <el-tag :type="peerOnline ? 'success' : 'info'" size="small" effect="plain" class="online-tag">
              {{ peerOnline ? '● 在线' : '○ 离线' }}
            </el-tag>
            <span v-if="!chatStore.connected" class="reconnecting">实时连接中断，已切换为轮询</span>
            <span v-if="peer?.campus">{{ peer.campus }}</span>
            <span v-if="peer?.creditScore !== undefined"> · 信用分 {{ peer.creditScore }}</span>
          </div>
        </div>
        <el-button text type="primary" class="to-list" @click="router.push('/messages')">消息列表</el-button>
      </div>

      <!-- 消息区域 -->
      <div ref="listRef" class="message-area">
        <div class="load-more">
          <el-button v-if="hasMore" size="small" text :loading="loadingMore" @click="loadMore">
            加载更早的消息（共 {{ total }} 条）
          </el-button>
          <span v-else-if="messages.length > 0" class="text-muted">—— 已经是最早的消息 ——</span>
        </div>

        <el-empty v-if="!loading && messages.length === 0" description="还没有聊天记录，发一条消息打个招呼吧～" :image-size="70" />

        <div v-for="(msg, index) in messages" :key="msg.id" class="message-row">
          <div v-if="showTimeDivider(index)" class="time-divider">{{ msg.createTime }}</div>
          <div class="bubble-line" :class="isMine(msg) ? 'mine' : 'theirs'">
            <el-avatar v-if="!isMine(msg)" :size="32" class="bubble-avatar">
              {{ (peer?.nickname || '对').slice(0, 1) }}
            </el-avatar>
            <div class="bubble-wrap">
              <div class="bubble" :class="isMine(msg) ? 'bubble-mine' : 'bubble-theirs'">
                {{ msg.content }}
              </div>
              <div
              v-if="showProductRef(index)"
              class="product-ref"
              role="link"
              tabindex="0"
              :aria-label="msg.productTitle"
              @click="router.push(`/product/${msg.productId}`)"
              @keydown.enter.prevent="router.push(`/product/${msg.productId}`)"
              @keydown.space.prevent="router.push(`/product/${msg.productId}`)"
            >
                <el-tag size="small" effect="plain" type="info">关于：{{ msg.productTitle }}</el-tag>
              </div>

              <!-- 已读回执：整段会话只挂在我发出的最后一条上。
                   此前模板从未渲染它（尽管 isRead 字段与已读回执订阅都已具备），
                   评审 P1-4「已读回执从未渲染」已核实属实。 -->
              <span
                v-if="isMine(msg) && index === lastMineIndex"
                class="read-state"
                :aria-label="msg.isRead === 1 ? '对方已读' : '对方未读'"
              >
                {{ msg.isRead === 1 ? '已读' : '未读' }}
              </span>
            </div>
            <el-avatar v-if="isMine(msg)" :size="32" class="bubble-avatar">
              {{ (userStore.nickname || '我').slice(0, 1) }}
            </el-avatar>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-area">
        <el-input
          v-model="inputContent"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          resize="none"
          :disabled="peer?.self"
          aria-label="输入消息"
              placeholder="输入消息，Enter 发送，Shift + Enter 换行"
          @keydown.enter="onEnter"
        />
        <div class="input-actions">
          <span class="text-muted">
            {{ chatStore.connected ? '● 实时推送已连接（WebSocket）' : '○ 实时连接中断，已切换为 15 秒轮询' }}
          </span>
          <el-button type="primary" :loading="sending" :disabled="peer?.self" @click="handleSend">发送</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.chat-page {
  max-width: 900px;
  margin: 0 auto;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--ct-paper-200);
  background: var(--ct-bg-surface);
  border-radius: 4px 4px 0 0;
}

.peer-info {
  flex: 1;
  min-width: 0;
}

.peer-name {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.peer-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.online-tag {
  font-size: 11px;
}

.reconnecting {
  font-size: 11px;
  color: var(--el-color-warning);
}

.peer-meta-text {
  font-size: 12px;
  color: var(--ct-text-muted);
  margin-top: 2px;
}

.to-list {
  flex-shrink: 0;
}

.message-area {
  height: 460px;
  overflow-y: auto;
  padding: 16px;
  background: var(--ct-bg-canvas);
}

.load-more {
  text-align: center;
  margin-bottom: 10px;
}

.time-divider {
  text-align: center;
  font-size: 12px;
  color: var(--ct-text-muted);
  margin: 12px 0 8px;
}

.bubble-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 10px;
}

.bubble-line.mine {
  justify-content: flex-end;
}

.bubble-wrap {
  max-width: 68%;
  display: flex;
  flex-direction: column;
}

.bubble {
  padding: 9px 13px;
  border-radius: 8px;
  line-height: 1.6;
  font-size: 14px;
  word-break: break-word;
  white-space: pre-wrap;
  /* 气泡最大宽度：1440 宽下不加限制会横贯整屏，读起来很累 */
  max-width: min(560px, 62vw);
}

/* 已读回执：小字弱化，只在最后一条出站消息下出现 */
.read-state {
  align-self: flex-end;
  margin-top: var(--ct-space-1);
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
}

/* 已读回执：小字弱化，只在最后一条出站消息下出现 */
.read-state {
  align-self: flex-end;
  margin-top: var(--ct-space-1);
  font-size: var(--ct-text-xs);
  color: var(--ct-text-muted);
}

.bubble-theirs {
  background: var(--ct-bg-surface);
  border: 1px solid var(--ct-paper-200);
  border-top-left-radius: 2px;
}

.bubble-mine {
  background: var(--ct-action);
  color: var(--ct-text-inverse);
  border-top-right-radius: 2px;
}

.bubble-avatar {
  flex-shrink: 0;
}

.product-ref {
  margin-top: 4px;
  cursor: pointer;
}

.mine .product-ref {
  text-align: right;
}

.input-area {
  padding: 12px 16px 16px;
  border-top: 1px solid var(--ct-paper-200);
  background: var(--ct-bg-surface);
}

.input-actions {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
