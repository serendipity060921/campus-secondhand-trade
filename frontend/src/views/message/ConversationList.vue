<script setup>
/**
 * 消息会话列表页（v0.07 建立，v0.14 接入实时能力）
 *
 * 数据来源：GET /api/message/conversationList
 * 展示：聊天对象昵称/头像、最后一条消息、时间、未读条数、**在线状态**；点击进入聊天窗口。
 *
 * v0.14 变化：
 *   ① 进入页面查询各会话对象的在线状态（WebSocket 批量查询，失败降级 REST）；
 *   ② 收到新私信/已读回执时自动刷新列表（不再只靠 15 秒定时器）；
 *   ③ 定时刷新保留为兜底（30 秒），把请求量降到原来的 1/2。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getConversationList } from '@/api/message'
import { useChatStore } from '@/store/chat'
import chatSocket from '@/utils/websocket'
import StateError from '@/components/states/StateError.vue'
import SkeletonList from '@/components/states/SkeletonList.vue'

const router = useRouter()
const chatStore = useChatStore()

const loading = ref(false)
const loadError = ref(false)
const conversations = ref([])
let timer = null
let unsubscribers = []

/** 未读总数（用于页面标题提示） */
const totalUnread = computed(() =>
  conversations.value.reduce((sum, item) => sum + (item.unreadCount || 0), 0)
)

function avatarText(item) {
  const name = item.peerNickname || '匿'
  return name.slice(0, 1)
}

/** 最后一条消息展示文案：我发的加"我："前缀 */
function lastMessageText(item) {
  if (!item.lastMessage) {
    return '（暂无消息）'
  }
  return (item.lastFromMe ? '我：' : '') + item.lastMessage
}

/** 消息时间：今天只显示时分，否则显示日期 */
function formatTime(time) {
  if (!time) return ''
  const today = new Date().toISOString().slice(0, 10)
  return String(time).startsWith(today) ? String(time).slice(11, 16) : String(time).slice(5, 16)
}

async function load(silent = false) {
  if (!silent) {
    loading.value = true
    loadError.value = false
  }
  try {
    const res = await getConversationList()
    conversations.value = res.data || []
    // v0.14：一次性查询所有会话对象的在线状态（WebSocket 批量查询优先）
    chatStore.queryOnline(conversations.value.map((item) => item.peerId).filter(Boolean))
  } catch (e) {
    // 静默刷新（定时器/推送触发）失败不打断页面；只有用户主动进入时才呈现错误态
    if (!silent) {
      conversations.value = []
      loadError.value = true
    }
  } finally {
    loading.value = false
  }
}

/** 在线状态（v0.14） */
function isPeerOnline(peerId) {
  return chatStore.isOnline(peerId)
}

function openChat(item) {
  router.push(`/chat/${item.peerId}`)
}

onMounted(() => {
  load()
  // v0.14：收到新私信/已读回执立即刷新列表（实时），30 秒定时兜底
  unsubscribers.push(chatSocket.on('chat', () => load(true)))
  unsubscribers.push(chatSocket.on('welcome', () => load(true)))
  timer = setInterval(() => load(true), 30000)
})

onUnmounted(() => {
  unsubscribers.forEach((fn) => fn())
  unsubscribers = []
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div>
    <StateError
      v-if="loadError && !loading"
      title="会话列表加载失败"
      detail="网络可能不稳定，或服务正在重启"
      retry-text="重新加载"
      @retry="load(false)"
    />
    <!-- 加载态：与最终列表同形的行骨架（此前是转圈遮罩） -->
    <div v-else-if="loading" class="loading-wrap">
      <SkeletonList variant="row" :count="6" :columns="1" />
    </div>
    <el-card v-else shadow="never">
      <template #header>
        <div class="card-header">
          <h1 class="card-title">我的消息</h1>
          <div class="header-right">
            <el-tag v-if="totalUnread > 0" type="danger" size="small" effect="dark">
              {{ totalUnread }} 条未读
            </el-tag>
            <el-button size="small" @click="load(false)">刷新</el-button>
          </div>
        </div>
      </template>

      <el-empty v-if="!loading && conversations.length === 0" description="还没有聊天记录，去商品详情页点「私聊卖家」开始吧～">
        <el-button type="primary" @click="router.push('/home')">去逛商品</el-button>
      </el-empty>

      <div v-else class="conversation-list">
        <div
          v-for="item in conversations"
          :key="item.peerId"
          class="conversation-item"
          role="link"
          tabindex="0"
          :aria-label="`与 ${item.peerNickname || '对方'} 的会话`"
          @click="openChat(item)"
          @keydown.enter.prevent="openChat(item)"
          @keydown.space.prevent="openChat(item)"
        >
          <el-badge :value="item.unreadCount" :hidden="!item.unreadCount" :max="99" class="avatar-badge">
            <el-avatar :size="48">{{ avatarText(item) }}</el-avatar>
          </el-badge>

          <div class="content">
            <div class="row-1">
              <span class="nickname">{{ item.peerNickname || '匿名用户' }}</span>
              <!-- v0.14：在线状态（WebSocket 推送 + 心跳判定） -->
              <span class="online" :class="{ on: isPeerOnline(item.peerId) }">
                {{ isPeerOnline(item.peerId) ? '● 在线' : '○ 离线' }}
              </span>
              <span class="time">{{ formatTime(item.lastMessageTime) }}</span>
            </div>
            <div class="row-2">
              <span class="last-message" :class="{ unread: item.unreadCount > 0 }">
                {{ lastMessageText(item) }}
              </span>
            </div>
            <div v-if="item.productTitle" class="row-3">
              <el-tag size="small" effect="plain" type="info">关于：{{ item.productTitle }}</el-tag>
            </div>
          </div>

          <div class="arrow">›</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 行骨架的承载：与列表卡片同宽同边距，避免加载完时跳动 */
.loading-wrap {
  background: var(--ct-bg-surface);
  border: var(--ct-hairline) solid var(--ct-border);
  border-radius: var(--ct-radius-sm);
  padding: var(--ct-space-4);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.conversation-list {
  display: flex;
  flex-direction: column;
}

.conversation-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 6px;
  border-bottom: 1px solid var(--ct-paper-100);
  cursor: pointer;
  transition: background 0.15s ease;
}

.conversation-item:hover {
  background: var(--ct-bg-subtle);
}

.avatar-badge {
  flex-shrink: 0;
}

.content {
  flex: 1;
  min-width: 0;
}

.row-1 {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nickname {
  font-weight: 600;
  font-size: 15px;
}

/* v0.14：在线状态标签 */
.online {
  font-size: 11px;
  color: var(--ct-text-muted);
  flex: 1;
}

.online.on {
  color: var(--el-color-success);
}

.time {
  font-size: 12px;
  color: var(--ct-text-muted);
}

.row-2 {
  margin-top: 4px;
}

.last-message {
  font-size: 13px;
  color: var(--ct-text-muted);
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.last-message.unread {
  color: var(--ct-text-primary);
  font-weight: 600;
}

.row-3 {
  margin-top: 6px;
}

.arrow {
  color: var(--ct-text-muted);
  font-size: 20px;
}

/* 卡片头里的页级主标题（原为 <b>，现为 h1）：保持与原先一致的视觉重量 */
.card-title {
  margin: 0;
  font-size: var(--ct-text-md);
  font-weight: var(--ct-weight-semibold);
}
</style>