package com.campus.trade.websocket;

import com.campus.trade.common.exception.BusinessException;
import com.campus.trade.dto.MessageReadDTO;
import com.campus.trade.dto.MessageSendDTO;
import com.campus.trade.service.CacheService;
import com.campus.trade.service.ChatPushService;
import com.campus.trade.service.MessageModuleService;
import com.campus.trade.service.OnlineStatusService;
import com.campus.trade.vo.MessageVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 私信 WebSocket 处理器（v0.14）。
 *
 * <p><b>职责</b>：把同一条私信业务的"实时通道"接起来 —— 收发消息、已读回执、心跳与在线状态。</p>
 *
 * <p><b>设计要点</b>：</p>
 * <ol>
 *   <li><b>复用 REST 的业务逻辑</b>：发送/已读都调用 {@link MessageModuleService}，
 *       不复制一份校验（不能给自己发、接收人存在、商品存在、只能标记自己收到的消息为已读），
 *       保证"接口"与"实时通道"两条路径行为完全一致；</li>
 *   <li><b>限流不漏</b>：REST 接口用 {@code @RateLimit} 注解限流，WebSocket 不走拦截器，
 *       因此这里用同一套 Redis 计数器补上"30 条/分钟"的限制，否则可以绕过限流刷消息；</li>
 *   <li><b>异常不外抛</b>：任何业务异常（BusinessException）都转成 error 帧回给客户端，
 *       连接本身保持存活，避免一次非法输入就断线；</li>
 *   <li><b>心跳</b>：客户端每 25 秒发 ping，服务端刷新 Redis 在线时间戳并回 pong；
 *       这样能识别"连接假死"，也能让后台的在线人数准确。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** WebSocket 通道的消息发送限流：30 条 / 分钟（与 REST 接口保持一致口径） */
    private static final int SEND_LIMIT = 30;
    private static final long SEND_WINDOW_SECONDS = 60;

    private final ChatSessionRegistry registry;
    private final ChatHandshakeInterceptor interceptor;
    private final MessageModuleService messageModuleService;
    private final ChatPushService chatPushService;
    private final OnlineStatusService onlineStatusService;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    /* ==================== 1. 连接建立 ==================== */

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        registry.register(userId, session);
        onlineStatusService.heartbeat(userId);

        // ① 欢迎包：告诉前端"你是谁 + 你有多少未读"，前端据此刷新角标
        long unread = messageModuleService.unreadTotal(userId);
        send(session, WsEnvelope.welcome(userId, unread));

        // ② 告诉我的会话对象"我上线了"
        chatPushService.pushOnline(userId, true);
    }

    /* ==================== 2. 收发消息 ==================== */

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        WsInboundMessage inbound = WsInboundMessage.parse(message.getPayload(), objectMapper);
        if (inbound == null || inbound.getType() == null) {
            send(session, WsEnvelope.error(400, "消息格式错误，缺少 type 字段"));
            return;
        }

        // 任何一次上行报文都算一次心跳（用户活跃即在线的直观定义）
        onlineStatusService.heartbeat(userId);

        try {
            switch (inbound.getType()) {
                case WsEnvelope.TYPE_PING -> send(session, WsEnvelope.pong());
                case WsEnvelope.TYPE_CHAT -> handleChat(session, userId, inbound);
                case WsEnvelope.TYPE_READ -> handleRead(session, userId, inbound);
                case WsEnvelope.TYPE_QUERY_ONLINE -> handleQueryOnline(session, inbound);
                default -> send(session, WsEnvelope.error(400, "不支持的消息类型：" + inbound.getType()));
            }
        } catch (BusinessException e) {
            // 业务异常（如"不能给自己发消息"5001、"接收人不存在"5002）以 error 帧返回，连接保留
            log.info("[WS 业务异常] userId={} type={} code={} msg={}",
                    userId, inbound.getType(), e.getCode(), e.getMessage());
            send(session, WsEnvelope.error(e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("[WS 处理异常] userId={} type={}", userId, inbound.getType(), e);
            send(session, WsEnvelope.error(500, "服务器处理消息失败"));
        }
    }

    /** 发送私信：限流 → 复用业务服务（入库 + 埋点 + 推送） */
    private void handleChat(WebSocketSession session, Long userId, WsInboundMessage inbound) {
        if (inbound.getToUserId() == null) {
            send(session, WsEnvelope.error(400, "缺少 toUserId"));
            return;
        }
        if (inbound.getContent() == null || inbound.getContent().isBlank()) {
            send(session, WsEnvelope.error(400, "消息内容不能为空"));
            return;
        }
        if (inbound.getContent().length() > 500) {
            send(session, WsEnvelope.error(400, "消息内容不能超过 500 字"));
            return;
        }
        // 限流：与 REST 的 @RateLimit(key="message") 同样的口径，防止绕过接口刷消息
        if (isRateLimited(userId)) {
            send(session, WsEnvelope.error(429, "发送过于频繁，请稍后再试"));
            return;
        }

        MessageSendDTO dto = new MessageSendDTO();
        dto.setToUserId(inbound.getToUserId());
        dto.setContent(inbound.getContent());
        dto.setProductId(inbound.getProductId());

        MessageVO vo = messageModuleService.send(dto, userId);
        // MessageModuleService 内部已通过 ChatPushService 推给双方，这里只需给发送方一个确认回执
        log.debug("[WS 私信] from={} to={} id={}", userId, inbound.getToUserId(), vo.getId());
    }

    /** 标记已读：复用业务服务（只能标记自己收到的消息），并向对方推送已读回执 */
    private void handleRead(WebSocketSession session, Long userId, WsInboundMessage inbound) {
        if (inbound.getPeerId() == null && (inbound.getMessageIds() == null || inbound.getMessageIds().isEmpty())) {
            send(session, WsEnvelope.error(400, "peerId 与 messageIds 至少传一个"));
            return;
        }
        MessageReadDTO dto = new MessageReadDTO();
        dto.setPeerId(inbound.getPeerId());
        dto.setMessageIds(inbound.getMessageIds());
        messageModuleService.markRead(dto, userId);
    }

    /** 批量查询在线状态（前端打开会话列表时用一次即可） */
    private void handleQueryOnline(WebSocketSession session, WsInboundMessage inbound) {
        List<Long> ids = inbound.getUserIds() == null ? List.of() : inbound.getUserIds();
        Set<Long> online = onlineStatusService.filterOnline(new HashSet<>(ids));
        List<Map<String, Object>> data = new ArrayList<>();
        for (Long id : ids) {
            data.add(Map.of("userId", id, "online", online.contains(id)));
        }
        send(session, WsEnvelope.of(WsEnvelope.TYPE_ONLINE, data));
    }

    /* ==================== 3. 连接关闭 ==================== */

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = userIdOf(session);
        if (userId == null) {
            return;
        }
        boolean allClosed = registry.unregister(userId, session);
        if (allClosed) {
            // 所有标签页都关了才算离线
            onlineStatusService.offline(userId);
            chatPushService.pushOnline(userId, false);
            log.info("[WS 断开] userId={} 状态={}", userId, status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[WS 传输异常] sessionId={} 原因={}", session.getId(), exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    /* ==================== 4. 工具方法 ==================== */

    /** 限流判定：Redis 计数器（与 REST 的 @RateLimit 同源） */
    private boolean isRateLimited(Long userId) {
        if (!cacheService.enabled()) {
            return false;      // 缓存关闭时降级为不限流（与 @RateLimit 的降级策略一致）
        }
        long count = cacheService.increment("campus:ratelimit:ws-message:user" + userId, SEND_WINDOW_SECONDS);
        return count > SEND_LIMIT;
    }

    private Long userIdOf(WebSocketSession session) {
        Object value = session.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);
        return value == null ? null : (Long) value;
    }

    private void send(WebSocketSession session, WsEnvelope envelope) {
        if (session != null) {
            registry.sendToSession(session, envelope.toJson(objectMapper));
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("[WS 关闭失败] {}", e.getMessage());
        }
    }
}
