package com.campus.trade.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.campus.trade.common.exception.UserException;
import com.campus.trade.dto.MessageSendDTO;
import com.campus.trade.service.CacheService;
import com.campus.trade.service.ChatPushService;
import com.campus.trade.service.MessageModuleService;
import com.campus.trade.service.OnlineStatusService;
import com.campus.trade.vo.MessageVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WebSocket 消息处理器验证（v0.18 / 收尾⑦-2）
 *
 * <p>这一类是"长连接上的业务入口"，它的错误处理方式与 REST 完全不同：
 * REST 出错可以返回错误码并结束请求，而长连接<b>不能因为一条坏消息就断开</b> ——
 * 断掉意味着用户所有标签页的实时消息一起失效。本测试锁定：</p>
 *
 * <ul>
 *   <li>信封协议分发：ping / chat / read / queryOnline 各走各的，未知类型回 error 帧；</li>
 *   <li>★ <b>坏消息只回 error 帧，绝不断连</b>：非法 JSON、缺 type、业务异常
 *       （如"不能给自己发消息"5001）都只是回一帧错误，连接保留；</li>
 *   <li>★ <b>业务异常要带原始业务码</b>，前端才能按码做差异化提示；</li>
 *   <li><b>发消息限流</b>：与 REST 的 @RateLimit 同源（Redis 计数器），
 *       且缓存关闭时降级为不限流 —— 与限流切面的降级策略保持一致；</li>
 *   <li>★ <b>所有标签页都断开才算离线</b>：只有最后一个连接注销时才更新在线状态。</li>
 * </ul>
 */
class ChatWebSocketHandlerTest {

    private ChatSessionRegistry registry;
    private ChatHandshakeInterceptor interceptor;
    private MessageModuleService messageModuleService;
    private ChatPushService chatPushService;
    private OnlineStatusService onlineStatusService;
    private CacheService cacheService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatWebSocketHandler handler;

    private WebSocketSession session;
    private Map<String, Object> attributes;

    private static final Long USER_ID = 9L;

    @BeforeEach
    void setUp() {
        registry = mock(ChatSessionRegistry.class);
        interceptor = mock(ChatHandshakeInterceptor.class);
        messageModuleService = mock(MessageModuleService.class);
        chatPushService = mock(ChatPushService.class);
        onlineStatusService = mock(OnlineStatusService.class);
        cacheService = mock(CacheService.class);

        handler = new ChatWebSocketHandler(registry, interceptor, messageModuleService,
                chatPushService, onlineStatusService, cacheService, objectMapper);

        session = mock(WebSocketSession.class);
        attributes = new HashMap<>();
        attributes.put(ChatHandshakeInterceptor.ATTR_USER_ID, USER_ID);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("s-1");
        when(cacheService.enabled()).thenReturn(true);
        when(messageModuleService.unreadTotal(anyLong())).thenReturn(3L);
    }

    /** 匿名会话（握手未写入 userId） */
    private WebSocketSession anonymousSession() {
        WebSocketSession anon = mock(WebSocketSession.class);
        when(anon.getAttributes()).thenReturn(new HashMap<>());
        when(anon.getId()).thenReturn("anon");
        return anon;
    }

    /** 取出发给该会话的最后一帧，解析成 JSON */
    private JsonNode lastFrame(WebSocketSession target) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(registry, org.mockito.Mockito.atLeastOnce()).sendToSession(eq(target), payload.capture());
        List<String> all = payload.getAllValues();
        return objectMapper.readTree(all.get(all.size() - 1));
    }

    private void send(String json) {
        handler.handleTextMessage(session, new TextMessage(json));
    }

    @Nested
    @DisplayName("一、连接建立")
    class Established {

        @Test
        @DisplayName("★ 握手未带身份（userId 为空）→ 直接关闭连接，不注册")
        void anonymousConnectionClosed() throws Exception {
            WebSocketSession anon = anonymousSession();

            handler.afterConnectionEstablished(anon);

            verify(anon).close(CloseStatus.NOT_ACCEPTABLE);
            verify(registry, never()).register(anyLong(), any());
        }

        @Test
        @DisplayName("正常建连：注册会话 + 心跳 + 欢迎包（带未读数）+ 通知对方我上线")
        void welcomeFlow() throws Exception {
            handler.afterConnectionEstablished(session);

            verify(registry).register(USER_ID, session);
            verify(onlineStatusService).heartbeat(USER_ID);
            verify(chatPushService).pushOnline(USER_ID, true);

            JsonNode frame = lastFrame(session);
            assertEquals(WsEnvelope.TYPE_WELCOME, frame.get("type").asText());
            assertEquals(3L, frame.get("data").get("unreadTotal").asLong(),
                    "欢迎包要带未读数供角标刷新");
        }
    }

    @Nested
    @DisplayName("二、信封协议分发")
    class Dispatch {

        @Test
        @DisplayName("ping → 回 pong（同时算一次心跳）")
        void pingPong() throws Exception {
            send("{\"type\":\"ping\"}");

            assertEquals(WsEnvelope.TYPE_PONG, lastFrame(session).get("type").asText());
            verify(onlineStatusService).heartbeat(USER_ID);
        }

        @Test
        @DisplayName("★ 未知类型 → 回 error 帧，连接保留（不断连）")
        void unknownTypeGetsErrorFrame() throws Exception {
            send("{\"type\":\"nonsense\"}");

            JsonNode frame = lastFrame(session);
            assertEquals(WsEnvelope.TYPE_ERROR, frame.get("type").asText());
            assertTrue(frame.get("message").asText().contains("不支持的消息类型"));
            verify(session, never()).close(any());
        }

        @Test
        @DisplayName("★ 非法 JSON → 回 error 帧，连接保留")
        void malformedJsonGetsErrorFrame() throws Exception {
            send("not-a-json");

            JsonNode frame = lastFrame(session);
            assertEquals(WsEnvelope.TYPE_ERROR, frame.get("type").asText());
            assertEquals(400, frame.get("code").asInt());
            verify(session, never()).close(any());
        }

        @Test
        @DisplayName("★ 缺 type 字段 → 回 error 帧，连接保留")
        void missingTypeGetsErrorFrame() throws Exception {
            send("{\"toUserId\":5}");

            assertEquals(WsEnvelope.TYPE_ERROR, lastFrame(session).get("type").asText());
            verify(session, never()).close(any());
        }

        @Test
        @DisplayName("★ 未认证会话发消息 → 关闭连接（不处理内容）")
        void anonymousMessageClosed() throws Exception {
            WebSocketSession anon = anonymousSession();

            handler.handleTextMessage(anon, new TextMessage("{\"type\":\"ping\"}"));

            verify(anon).close(CloseStatus.NOT_ACCEPTABLE);
        }

        @Test
        @DisplayName("queryOnline → 回在线状态列表")
        void queryOnline() throws Exception {
            when(onlineStatusService.filterOnline(any())).thenReturn(Set.of(5L));

            send("{\"type\":\"queryOnline\",\"userIds\":[5,6]}");

            JsonNode frame = lastFrame(session);
            assertEquals(WsEnvelope.TYPE_ONLINE, frame.get("type").asText());
            assertEquals(2, frame.get("data").size());
            assertEquals(5L, frame.get("data").get(0).get("userId").asLong());
            assertTrue(frame.get("data").get(0).get("online").asBoolean());
            assertTrue(!frame.get("data").get(1).get("online").asBoolean());
        }
    }

    @Nested
    @DisplayName("三、发送私信：参数、限流与业务异常")
    class Chat {

        @Test
        @DisplayName("缺少 toUserId / 内容为空 / 超过 500 字 → 各自回 400，且不调用业务服务")
        void parameterValidation() throws Exception {
            send("{\"type\":\"chat\",\"content\":\"你好\"}");
            assertTrue(lastFrame(session).get("message").asText().contains("toUserId"));

            send("{\"type\":\"chat\",\"toUserId\":5,\"content\":\"   \"}");
            assertTrue(lastFrame(session).get("message").asText().contains("不能为空"));

            send("{\"type\":\"chat\",\"toUserId\":5,\"content\":\"" + "字".repeat(501) + "\"}");
            assertTrue(lastFrame(session).get("message").asText().contains("500"));

            verify(messageModuleService, never()).send(any(), anyLong());
        }

        @Test
        @DisplayName("★ 触发限流 → 回 429 且不落库（防止绕过 REST 接口刷消息）")
        void rateLimited() throws Exception {
            when(cacheService.increment(anyString(), anyLong())).thenReturn(31L);

            send("{\"type\":\"chat\",\"toUserId\":5,\"content\":\"你好\"}");

            JsonNode frame = lastFrame(session);
            assertEquals(429, frame.get("code").asInt());
            verify(messageModuleService, never()).send(any(), anyLong());
        }

        @Test
        @DisplayName("正常发送：组装 DTO 交给业务服务（入库 + 埋点 + 推送都在业务层）")
        void sendsThroughBusinessService() {
            when(cacheService.increment(anyString(), anyLong())).thenReturn(1L);
            MessageVO vo = new MessageVO();
            vo.setId(66L);
            when(messageModuleService.send(any(), eq(USER_ID))).thenReturn(vo);

            send("{\"type\":\"chat\",\"toUserId\":5,\"content\":\"你好\",\"productId\":7}");

            ArgumentCaptor<MessageSendDTO> captor = ArgumentCaptor.forClass(MessageSendDTO.class);
            verify(messageModuleService).send(captor.capture(), eq(USER_ID));
            assertEquals(5L, captor.getValue().getToUserId());
            assertEquals("你好", captor.getValue().getContent());
            assertEquals(7L, captor.getValue().getProductId());
        }

        @Test
        @DisplayName("★ 业务异常（不能给自己发消息 5001）→ 回带原始业务码的 error 帧，连接保留")
        void businessExceptionKeepsConnection() throws Exception {
            when(cacheService.increment(anyString(), anyLong())).thenReturn(1L);
            when(messageModuleService.send(any(), anyLong()))
                    .thenThrow(new UserException(5001, "不能给自己发消息"));

            send("{\"type\":\"chat\",\"toUserId\":9,\"content\":\"你好\"}");

            JsonNode frame = lastFrame(session);
            assertEquals(WsEnvelope.TYPE_ERROR, frame.get("type").asText());
            assertEquals(5001, frame.get("code").asInt(), "必须透出原始业务码，前端才能差异化提示");
            verify(session, never()).close(any());
        }

        @Test
        @DisplayName("★ 非业务异常 → 回 500 通用错误帧，连接同样保留")
        void unexpectedExceptionKeepsConnection() throws Exception {
            when(cacheService.increment(anyString(), anyLong())).thenReturn(1L);
            when(messageModuleService.send(any(), anyLong())).thenThrow(new RuntimeException("boom"));

            send("{\"type\":\"chat\",\"toUserId\":5,\"content\":\"你好\"}");

            JsonNode frame = lastFrame(session);
            assertEquals(500, frame.get("code").asInt());
            verify(session, never()).close(any());
        }

        @Test
        @DisplayName("★ 缓存关闭时限流降级为不限流（与 @RateLimit 的降级策略一致）")
        void rateLimitDegradesWhenCacheOff() {
            when(cacheService.enabled()).thenReturn(false);
            MessageVO vo = new MessageVO();
            vo.setId(1L);
            when(messageModuleService.send(any(), eq(USER_ID))).thenReturn(vo);

            send("{\"type\":\"chat\",\"toUserId\":5,\"content\":\"你好\"}");

            verify(cacheService, never()).increment(anyString(), anyLong());
            verify(messageModuleService).send(any(), eq(USER_ID));
        }
    }

    @Nested
    @DisplayName("四、标记已读")
    class Read {

        @Test
        @DisplayName("peerId 与 messageIds 都为空 → 回 400，不调用业务服务")
        void needsPeerOrIds() throws Exception {
            send("{\"type\":\"read\"}");

            assertTrue(lastFrame(session).get("message").asText().contains("至少传一个"));
            verify(messageModuleService, never()).markRead(any(), anyLong());
        }

        @Test
        @DisplayName("按会话标记已读：透传 peerId")
        void markByPeer() {
            send("{\"type\":\"read\",\"peerId\":5}");

            ArgumentCaptor<com.campus.trade.dto.MessageReadDTO> captor =
                    ArgumentCaptor.forClass(com.campus.trade.dto.MessageReadDTO.class);
            verify(messageModuleService).markRead(captor.capture(), eq(USER_ID));
            assertEquals(5L, captor.getValue().getPeerId());
        }

        @Test
        @DisplayName("按消息 ID 标记已读：透传 messageIds")
        void markByIds() {
            send("{\"type\":\"read\",\"messageIds\":[1,2,3]}");

            ArgumentCaptor<com.campus.trade.dto.MessageReadDTO> captor =
                    ArgumentCaptor.forClass(com.campus.trade.dto.MessageReadDTO.class);
            verify(messageModuleService).markRead(captor.capture(), eq(USER_ID));
            assertEquals(List.of(1L, 2L, 3L), captor.getValue().getMessageIds());
        }
    }

    @Nested
    @DisplayName("五、连接关闭与异常")
    class Closing {

        @Test
        @DisplayName("★ 只有最后一个标签页关闭时才置离线并通知对方")
        void offlineOnlyWhenAllClosed() {
            when(registry.unregister(USER_ID, session)).thenReturn(false);
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
            verify(onlineStatusService, never()).offline(anyLong());
            verify(chatPushService, never()).pushOnline(anyLong(), eq(false));

            when(registry.unregister(USER_ID, session)).thenReturn(true);
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
            verify(onlineStatusService).offline(USER_ID);
            verify(chatPushService).pushOnline(USER_ID, false);
        }

        @Test
        @DisplayName("匿名会话关闭：不触碰在线状态")
        void anonymousCloseIgnored() {
            handler.afterConnectionClosed(anonymousSession(), CloseStatus.NORMAL);

            verify(registry, never()).unregister(anyLong(), any());
            verify(onlineStatusService, never()).offline(anyLong());
        }

        @Test
        @DisplayName("★ 传输异常 → 关闭连接（避免半开连接占着会话表）")
        void transportErrorCloses() throws Exception {
            handler.handleTransportError(session, new RuntimeException("io error"));

            verify(session).close(CloseStatus.SERVER_ERROR);
        }
    }
}
