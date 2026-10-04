package com.campus.trade.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * WebSocket 会话注册表（v0.14）。
 *
 * <p>维护"用户ID → 该用户的全部连接"映射，解决三件事：</p>
 * <ol>
 *   <li><b>定向推送</b>：给指定用户的所有设备/标签页推送消息；</li>
 *   <li><b>多端在线</b>：同一账号可开多个标签页，只有全部断开才算真正离线；</li>
 *   <li><b>在线查询</b>：{@link #isOnline(Long)} 判断某人是否有活跃连接。</li>
 * </ol>
 *
 * <p>并发说明：{@code ConcurrentHashMap} + {@code CopyOnWriteArraySet}，
 * 读多写少（推送频繁、连接变化少），符合该场景。发送时对 session 加锁，
 * 因为 Spring 的 WebSocketSession 不是线程安全的（并发 sendMessage 会抛
 * {@code TextMessage will not be sent: session is already sending} 之类的异常）。</p>
 */
@Slf4j
@Component
public class ChatSessionRegistry {

    /** userId -> 该用户的连接集合 */
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /** 注册连接 */
    public void register(Long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("[WS 上线] userId={} 当前连接数={} 在线人数={}",
                userId, sessionCount(userId), onlineUserCount());
    }

    /**
     * 注销连接。
     *
     * @return true = 该用户的所有连接都已断开（真正离线）
     */
    public boolean unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set == null) {
            return true;
        }
        set.remove(session);
        if (set.isEmpty()) {
            sessions.remove(userId);
            log.info("[WS 下线] userId={} 在线人数={}", userId, onlineUserCount());
            return true;
        }
        return false;
    }

    /** 该用户是否在线（存在任意连接） */
    public boolean isOnline(Long userId) {
        Set<WebSocketSession> set = sessions.get(userId);
        return set != null && !set.isEmpty();
    }

    /** 某用户的连接数 */
    public int sessionCount(Long userId) {
        Set<WebSocketSession> set = sessions.get(userId);
        return set == null ? 0 : set.size();
    }

    /** 当前在线人数（去重后的用户数） */
    public int onlineUserCount() {
        return sessions.size();
    }

    /** 在线用户ID集合 */
    public Set<Long> onlineUserIds() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    /**
     * 给某个用户的所有连接推送文本消息。
     *
     * @return 成功发送的连接数
     */
    public int sendToUser(Long userId, String payload) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (WebSocketSession session : set) {
            if (sendToSession(session, payload)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * 给多个用户推送同一条消息（用于"我上线了"通知我的会话对象）。
     */
    public int sendToUsers(Set<Long> userIds, String payload) {
        int sent = 0;
        for (Long userId : userIds) {
            sent += sendToUser(userId, payload);
        }
        return sent;
    }

    /** 单个连接发送（加锁 + 异常吞掉，避免一个坏连接影响其他推送） */
    public boolean sendToSession(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            // WebSocketSession 非线程安全：同一 session 并发写会抛异常，这里串行化
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("[WS 推送失败] sessionId={} 原因={}", session.getId(), e.getMessage());
            return false;
        }
    }

    /** 关闭某用户的全部连接（例如账号被禁用时踢下线） */
    public int closeUser(Long userId) {
        Set<WebSocketSession> set = sessions.remove(userId);
        if (set == null) {
            return 0;
        }
        for (WebSocketSession session : set) {
            try {
                session.close();
            } catch (IOException e) {
                log.debug("[WS 关闭失败] {}", e.getMessage());
            }
        }
        return set.size();
    }

    /** 调试用：在线用户ID快照 */
    public String snapshot() {
        return sessions.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().size())
                .collect(Collectors.joining(", "));
    }
}
