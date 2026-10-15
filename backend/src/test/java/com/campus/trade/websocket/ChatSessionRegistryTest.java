package com.campus.trade.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 会话注册表验证（v0.17）
 *
 * <p>注册表是实时私信的"路由表"，它出错的后果都很隐蔽 —— 消息发给错误的连接、
 * 用户明明在线却收不到、或者一个坏连接把整批推送拖垮。本测试锁定：</p>
 *
 * <ol>
 *   <li><b>多端在线语义</b>：同一账号多个标签页，只有全部断开才算真正离线；</li>
 *   <li><b>定向推送</b>：给某用户的所有在线连接推送，关闭的连接必须跳过；</li>
 *   <li><b>坏连接隔离</b>：单个连接发送/关闭失败不得影响其他连接；</li>
 *   <li><b>并发安全</b>：多线程同时上下线后，统计值仍然自洽。</li>
 * </ol>
 */
class ChatSessionRegistryTest {

    private ChatSessionRegistry registry;
    private static final Long U1 = 1L;
    private static final Long U2 = 2L;

    @BeforeEach
    void setUp() {
        registry = new ChatSessionRegistry();
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    private WebSocketSession closedSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(false);
        return s;
    }

    private TextMessage capturedPayload(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("一、上线注册与多端在线")
    class Register {

        @Test
        @DisplayName("注册后即在线，连接数与在线人数都为 1")
        void singleSession() {
            registry.register(U1, openSession("s1"));

            assertTrue(registry.isOnline(U1));
            assertEquals(1, registry.sessionCount(U1));
            assertEquals(1, registry.onlineUserCount());
            assertTrue(registry.onlineUserIds().contains(U1));
        }

        @Test
        @DisplayName("同一账号多端登录：连接数为 2，但在线人数仍按人去重")
        void multiDeviceCountsAsOneUser() {
            registry.register(U1, openSession("s1"));
            registry.register(U1, openSession("s2"));

            assertEquals(2, registry.sessionCount(U1));
            assertEquals(1, registry.onlineUserCount(), "同一账号多端只应算一个在线用户");
        }

        @Test
        @DisplayName("多个用户各自独立")
        void multipleUsers() {
            registry.register(U1, openSession("s1"));
            registry.register(U2, openSession("s2"));

            assertEquals(2, registry.onlineUserCount());
            assertEquals(Set.of(U1, U2), new HashSet<>(registry.onlineUserIds()));
            assertTrue(registry.isOnline(U1));
            assertTrue(registry.isOnline(U2));
        }

        @Test
        @DisplayName("重复注册同一个 session 不会重复计数（Set 去重）")
        void duplicateRegisterIgnored() {
            WebSocketSession s = openSession("s1");
            registry.register(U1, s);
            registry.register(U1, s);

            assertEquals(1, registry.sessionCount(U1));
        }

        @Test
        @DisplayName("未注册过的用户一律离线")
        void unknownUserOffline() {
            assertFalse(registry.isOnline(999L));
            assertEquals(0, registry.sessionCount(999L));
            assertEquals(0, registry.onlineUserCount());
        }
    }

    @Nested
    @DisplayName("二、下线注销与「真正离线」的判定")
    class Unregister {

        @Test
        @DisplayName("多端时断开其中一个 → 仍在线，返回 false")
        void oneOfManyStillOnline() {
            WebSocketSession s1 = openSession("s1");
            WebSocketSession s2 = openSession("s2");
            registry.register(U1, s1);
            registry.register(U1, s2);

            boolean fullyOffline = registry.unregister(U1, s1);

            assertFalse(fullyOffline, "还有其他连接，不应判定为离线");
            assertTrue(registry.isOnline(U1));
            assertEquals(1, registry.sessionCount(U1));
        }

        @Test
        @DisplayName("★ 断开最后一个连接 → 真正离线，返回 true，且在线集合中不再出现该用户")
        void lastSessionMeansOffline() {
            WebSocketSession s1 = openSession("s1");
            registry.register(U1, s1);

            boolean fullyOffline = registry.unregister(U1, s1);

            assertTrue(fullyOffline);
            assertFalse(registry.isOnline(U1));
            assertEquals(0, registry.sessionCount(U1));
            assertEquals(0, registry.onlineUserCount());
            assertFalse(registry.onlineUserIds().contains(U1), "离线后不应残留 userId（否则会一直误判在线）");
        }

        @Test
        @DisplayName("注销未注册过的用户是幂等的（返回 true，不抛异常）")
        void unregisterUnknownUser() {
            assertTrue(registry.unregister(999L, openSession("s1")));
            assertEquals(0, registry.onlineUserCount());
        }

        @Test
        @DisplayName("注销不属于该用户的会话不会影响该用户的其他连接")
        void unregisterForeignSession() {
            WebSocketSession mine = openSession("mine");
            registry.register(U1, mine);

            boolean fullyOffline = registry.unregister(U1, openSession("other"));

            assertFalse(fullyOffline);
            assertEquals(1, registry.sessionCount(U1), "误传的 session 不应移除真实连接");
        }

        @Test
        @DisplayName("只影响目标用户，其他用户不受牵连")
        void doesNotAffectOthers() {
            registry.register(U1, openSession("s1"));
            registry.register(U2, openSession("s2"));

            registry.unregister(U1, mock(WebSocketSession.class));

            assertTrue(registry.isOnline(U1), "误传 session 时该用户原有连接仍在");
            assertTrue(registry.isOnline(U2));
        }
    }

    @Nested
    @DisplayName("三、定向推送：发给某用户的所有在线连接")
    class SendToUser {

        @Test
        @DisplayName("用户不在线时返回 0，且不发送")
        void offlineReturnsZero() throws Exception {
            assertEquals(0, registry.sendToUser(U1, "hi"));
        }

        @Test
        @DisplayName("多端全部收到同一条消息，返回发送成功的连接数")
        void allSessionsReceive() throws Exception {
            WebSocketSession s1 = openSession("s1");
            WebSocketSession s2 = openSession("s2");
            registry.register(U1, s1);
            registry.register(U1, s2);

            int sent = registry.sendToUser(U1, "{\"type\":\"chat\"}");

            assertEquals(2, sent);
            assertEquals("{\"type\":\"chat\"}", capturedPayload(s1).getPayload());
            assertEquals("{\"type\":\"chat\"}", capturedPayload(s2).getPayload());
        }

        @Test
        @DisplayName("★ 已关闭的连接被跳过，不影响其他连接（返回值为实际成功数）")
        void closedSessionSkipped() throws Exception {
            WebSocketSession open = openSession("open");
            WebSocketSession closed = closedSession("closed");
            registry.register(U1, open);
            registry.register(U1, closed);

            int sent = registry.sendToUser(U1, "msg");

            assertEquals(1, sent, "只应统计真正发送成功的连接");
            verify(open).sendMessage(any(TextMessage.class));
            verify(closed, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("只推送给目标用户，不会发给别人")
        void notSentToOtherUsers() throws Exception {
            WebSocketSession mine = openSession("mine");
            WebSocketSession other = openSession("other");
            registry.register(U1, mine);
            registry.register(U2, other);

            registry.sendToUser(U1, "secret");

            verify(other, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("群发多个用户：逐个累加成功数，未在线用户计 0")
        void sendToUsersAggregates() throws Exception {
            WebSocketSession s1 = openSession("s1");
            WebSocketSession s2a = openSession("s2a");
            WebSocketSession s2b = openSession("s2b");
            registry.register(U1, s1);
            registry.register(U2, s2a);
            registry.register(U2, s2b);

            int sent = registry.sendToUsers(Set.of(U1, U2, 999L), "online-notice");

            assertEquals(3, sent, "两个在线用户共 3 条连接，未在线的 999 计 0");
        }
    }

    @Nested
    @DisplayName("四、单连接发送与坏连接隔离")
    class SendToSession {

        @Test
        @DisplayName("session 为 null 或已关闭 → 返回 false，不抛异常")
        void nullOrClosed() {
            assertFalse(registry.sendToSession(null, "x"));
            assertFalse(registry.sendToSession(closedSession("c"), "x"));
        }

        @Test
        @DisplayName("正常发送返回 true，载荷原样送达")
        void sendsPayload() throws Exception {
            WebSocketSession s = openSession("s1");

            assertTrue(registry.sendToSession(s, "hello"));
            assertEquals("hello", capturedPayload(s).getPayload());
        }

        @Test
        @DisplayName("★ 发送抛 IOException → 返回 false（吞掉异常，避免一个坏连接影响整批推送）")
        void ioExceptionSwallowed() throws Exception {
            WebSocketSession s = openSession("bad");
            doThrow(new IOException("连接已断开")).when(s).sendMessage(any(TextMessage.class));

            assertFalse(registry.sendToSession(s, "x"));
        }

        @Test
        @DisplayName("★ 发送抛 IllegalStateException（并发写）→ 返回 false")
        void illegalStateSwallowed() throws Exception {
            WebSocketSession s = openSession("busy");
            doThrow(new IllegalStateException("session is already sending"))
                    .when(s).sendMessage(any(TextMessage.class));

            assertFalse(registry.sendToSession(s, "x"));
        }

        @Test
        @DisplayName("一个连接失败不影响同用户的其他连接")
        void failureIsolatedPerSession() throws Exception {
            WebSocketSession bad = openSession("bad");
            WebSocketSession good = openSession("good");
            doThrow(new IOException("boom")).when(bad).sendMessage(any(TextMessage.class));
            registry.register(U1, bad);
            registry.register(U1, good);

            int sent = registry.sendToUser(U1, "msg");

            assertEquals(1, sent);
            verify(good).sendMessage(any(TextMessage.class));
        }
    }

    @Nested
    @DisplayName("五、踢下线与快照")
    class CloseAndSnapshot {

        @Test
        @DisplayName("关闭某用户全部连接：逐个 close、整体移除、返回关闭数")
        void closeUserClosesAll() throws Exception {
            WebSocketSession s1 = openSession("s1");
            WebSocketSession s2 = openSession("s2");
            registry.register(U1, s1);
            registry.register(U1, s2);

            int closed = registry.closeUser(U1);

            assertEquals(2, closed);
            verify(s1).close();
            verify(s2).close();
            assertFalse(registry.isOnline(U1), "踢下线后不应再是在线状态");
            assertEquals(0, registry.sessionCount(U1));
            assertEquals(0, registry.closeUser(U1), "重复踢下线应返回 0");
        }

        @Test
        @DisplayName("★ 某个连接 close 抛异常 → 其余连接照常关闭（坏连接不阻断）")
        void closeFailureDoesNotBlockOthers() throws Exception {
            WebSocketSession bad = openSession("bad");
            WebSocketSession good = openSession("good");
            doThrow(new IOException("close failed")).when(bad).close();
            registry.register(U1, bad);
            registry.register(U1, good);

            int closed = registry.closeUser(U1);

            assertEquals(2, closed, "返回值是本次处理的连接数，不受单个失败影响");
            verify(good).close();
            assertFalse(registry.isOnline(U1));
        }

        @Test
        @DisplayName("踢下线只影响目标用户")
        void closeUserScopedToUser() {
            registry.register(U1, openSession("s1"));
            registry.register(U2, openSession("s2"));

            registry.closeUser(U1);

            assertFalse(registry.isOnline(U1));
            assertTrue(registry.isOnline(U2), "不应把其他用户一起踢下线");
        }

        @Test
        @DisplayName("快照格式为「用户:连接数」，多端用户显示真实连接数")
        void snapshotFormat() {
            registry.register(U1, openSession("s1"));
            registry.register(U1, openSession("s2"));
            registry.register(U2, openSession("s3"));

            String snap = registry.snapshot();

            assertTrue(snap.contains("1:2"), "多端用户应显示连接数 2：" + snap);
            assertTrue(snap.contains("2:1"), "单端用户应显示连接数 1：" + snap);
            assertEquals("", new ChatSessionRegistry().snapshot(), "无人在线时快照为空串");
        }

        @Test
        @DisplayName("在线用户集合是只读视图（防止外部误改内部状态）")
        void onlineUserIdsIsUnmodifiable() {
            registry.register(U1, openSession("s1"));

            Set<Long> ids = registry.onlineUserIds();
            assertThrows(UnsupportedOperationException.class, () -> ids.add(999L));
        }
    }

    @Nested
    @DisplayName("六、并发上下线后统计仍然自洽")
    class Concurrency {

        @Test
        @DisplayName("多线程反复注册/注销后：在线人数与连接数一致，无残留")
        void concurrentRegisterAndUnregister() throws Exception {
            int threads = 8;
            int perThread = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                WebSocketSession session = openSession("t" + tid);
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            registry.register(U1, session);
                            registry.unregister(U1, session);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在 30 秒内结束");

            assertEquals(0, errors.get(), "并发注册/注销不应抛异常");
            assertEquals(0, registry.onlineUserCount(), "全部断开后在线人数应为 0");
            assertEquals(0, registry.sessionCount(U1));
            assertFalse(registry.isOnline(U1), "不应残留任何连接（残留会导致误判在线）");
        }

        @Test
        @DisplayName("并发注册多端后连接数准确（不丢不重）")
        void concurrentRegisterCountsExactly() throws Exception {
            int n = 32;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);

            for (int i = 0; i < n; i++) {
                WebSocketSession s = openSession("c" + i);
                pool.submit(() -> {
                    try {
                        start.await();
                        registry.register(U1, s);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(n, registry.sessionCount(U1));
            assertEquals(1, registry.onlineUserCount());
            assertEquals(n, registry.sendToUser(U1, "broadcast"), "所有连接都应收到广播");
        }
    }
}
