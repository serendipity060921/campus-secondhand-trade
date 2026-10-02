<script setup>
/**
 * 聊天窗口页（v0.07，需要登录）
 *
 * 路由：/chat/:userId?productId=xxx
 *   - :userId    聊天对象ID（从"私聊卖家"进入时即卖家ID）
 *   - productId  可选，从商品详情页进入时带上的关联商品
 *
 * 功能：
 *   ① 加载对方信息 + 最近 20 条聊天记录（后端返回最新在前，这里翻转成正序展示）
 *   ② 打开即把对方发给我的消息标记为已读
 *   ③ 上滑/点按钮加载更早的历史消息（分页）
 *   ④ 输入框发送消息（Enter 发送，Shift+Enter 换行）
 *   ⑤ 5 秒轮询拉取新消息（毕设阶段用轮询模拟实时；后续可升级 WebSocket）
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getChatPeer, getMessageHistory, markMessageRead, sendMessage } from '@/api/message'
import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const PEER_ID = Number(route.params.userId)
const PRODUCT_ID = route.query.productId ? Number(route.query.productId) : null
const PAGE_SIZE = 20

const loading = ref(false)
const sending = ref(false)
const loadingMore = ref(false)
const peer = ref(null)
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

/** 把对方发给我的未读消息标记为已读 */
async function markReadIfNeeded() {
  const hasUnread = messages.value.some((m) => !isMine(m) && m.isRead === 0)
  if (hasUnread) {
    try {
      await markMessageRead({ peerId: PEER_ID })
      messages.value.forEach((m) => {
        if (!isMine(m)) m.isRead = 1
      })
    } catch (e) {
      /* 忽略 */
    }
  }
}

/** 轮询新消息：拉第一页，把本地没有的追加进来 */
async function pollNew() {
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
    const res = await sendMessage({
      toUserId: PEER_ID,
      content,
      productId: PRODUCT_ID || undefined
    })
    messages.value.push(res.data)
    total.value += 1
    loadedPages.value = Math.max(loadedPages.value, 1)
    inputContent.value = ''
    await scrollToBottom()
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

let timer = null
onMounted(async () => {
  await loadPeer()
  await loadFirst()
  timer = setInterval(pollNew, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="chat-page" v-loading="loading">
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
              <div v-if="msg.productId && msg.productTitle" class="product-ref" @click="router.push(`/product/${msg.productId}`)">
                <el-tag size="small" effect="plain" type="info">关于：{{ msg.productTitle }}</el-tag>
              </div>
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
          placeholder="输入消息，Enter 发送，Shift + Enter 换行"
          @keydown.enter="onEnter"
        />
        <div class="input-actions">
          <span class="text-muted">每 5 秒自动刷新新消息</span>
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
  border-bottom: 1px solid #ebeef5;
  background: #fff;
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
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.to-list {
  flex-shrink: 0;
}

.message-area {
  height: 460px;
  overflow-y: auto;
  padding: 16px;
  background: #f5f7fa;
}

.load-more {
  text-align: center;
  margin-bottom: 10px;
}

.time-divider {
  text-align: center;
  font-size: 12px;
  color: #a8abb2;
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
}

.bubble {
  padding: 9px 13px;
  border-radius: 8px;
  line-height: 1.6;
  font-size: 14px;
  word-break: break-word;
  white-space: pre-wrap;
}

.bubble-theirs {
  background: #fff;
  border: 1px solid #ebeef5;
  border-top-left-radius: 2px;
}

.bubble-mine {
  background: #409eff;
  color: #fff;
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
  border-top: 1px solid #ebeef5;
  background: #fff;
}

.input-actions {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
