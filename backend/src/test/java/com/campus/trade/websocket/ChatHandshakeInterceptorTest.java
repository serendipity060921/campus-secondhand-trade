package com.campus.trade.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import com.campus.trade.common.cache.TokenBlacklist;
import com.campus.trade.common.util.JwtUtil;
import com.campus.trade.entity.User;
import com.campus.trade.service.UserService;

import io.jsonwebtoken.Claims;

/**
 * WebSocket 握手鉴权验证（v0.18 / 收尾⑦-2）
 *
 * <p>实时私信不能"连上就信" —— 握手阶段必须完成鉴权，否则任何人都能挂着连接收消息。
 * 本测试把整套拒绝逻辑与<b>状态码语义</b>固定下来：</p>
 *
 * <ul>
 *   <li>缺 token / token 无效 / 已退出登录（黑名单）/ 用户不存在 → <b>401</b>，
 *       前端据此停止重连并跳登录页；</li>
 *   <li>★ 账号被禁用 → <b>403</b>（而不是 401）—— 前端要能区分"你该重新登录"与
 *       "你的号被封了"，否则会陷入"反复跳登录页却永远登不进"的死循环；</li>
 *   <li>鉴权通过时把 userId / nickname / username 写进会话属性，
 *       后续消息处理只认这份属性，不再重复解析 Token。</li>
 * </ul>
 *
 * <p>说明：{@code HandshakeInterceptor} 用的是 <b>Servlet 栈</b>的
 * {@code ServerHttpRequest/Response}，因此这里用
 * {@code ServletServerHttpRequest} 包装 {@code MockHttpServletRequest} 来构造入参。</p>
 */
class ChatHandshakeInterceptorTest {

    private JwtUtil jwtUtil;
    private TokenBlacklist tokenBlacklist;
    private UserService userService;
    private ChatHandshakeInterceptor interceptor;

    private static final Long USER_ID = 9L;
    private static final String TOKEN = "valid.jwt.token";

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        tokenBlacklist = mock(TokenBlacklist.class);
        userService = mock(UserService.class);
        interceptor = new ChatHandshakeInterceptor(jwtUtil, tokenBlacklist, userService);
    }

    /* ---------------- 夹具 ---------------- */

    private Map<String, Object> attributes() {
        return new HashMap<>();
    }

    private User enabledUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setUsername("stu_demo");
        u.setNickname("演示同学");
        u.setStatus(1);
        return u;
    }

    private Claims claimsFor(Long userId) {
        // 用 jjwt 的真实实现而不是 mock：如果在 thenReturn(...) 的参数里创建并打桩
        // 另一个 mock，Mockito 会抛 UnfinishedStubbingException
        //（"在 thenReturn 完成前又去桩另一个 mock"）——本次实测 7 个用例因此报错。
        return new io.jsonwebtoken.impl.DefaultClaims(
                java.util.Map.of(io.jsonwebtoken.Claims.SUBJECT, String.valueOf(userId)));
    }

    /**
     * 发起一次握手。
     *
     * <p>注意：{@code ServletServerHttpRequest.getURI()} 是从
     * {@code getRequestURI() + getQueryString()} 拼出来的，
     * {@code MockHttpServletRequest} 的 addParameter 不会反映到 query string 上，
     * 因此这里必须用 setQueryString 才能让 resolveToken 读得到。</p>
     */
    private boolean handshake(String tokenInQuery, String tokenInProtocol,
                              Map<String, Object> attrs, MockHttpServletResponse response) {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/ws/chat");
        if (tokenInQuery != null) {
            raw.setQueryString("token=" + tokenInQuery);
        }
        if (tokenInProtocol != null) {
            raw.addHeader("Sec-WebSocket-Protocol", tokenInProtocol);
        }
        ServerHttpRequest request = new ServletServerHttpRequest(raw);
        ServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
        return interceptor.beforeHandshake(request, httpResponse, mock(WebSocketHandler.class), attrs);
    }

    @Test
    @DisplayName("★ 缺少 token → 拒绝握手并明确返回 401（前端据此跳登录页而不是无限重连）")
    void missingTokenRejected() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(handshake(null, null, attributes(), response));

        assertEquals(401, response.getStatus());
        verify(jwtUtil, never()).parseToken(anyString());
    }

    @Test
    @DisplayName("空白 token 同样按缺少处理")
    void blankTokenRejected() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(handshake("   ", null, attributes(), response));

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("token 无效或已过期 → 401")
    void invalidTokenRejected() {
        when(jwtUtil.parseToken(TOKEN)).thenThrow(new RuntimeException("expired"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(handshake(TOKEN, null, attributes(), response));

        assertEquals(401, response.getStatus());
        verify(userService, never()).getById(anyLong());
    }

    @Test
    @DisplayName("★ 已退出登录（黑名单）的 token 不能再建立实时连接 → 401")
    void blacklistedTokenRejected() {
        when(jwtUtil.parseToken(TOKEN)).thenReturn(claimsFor(USER_ID));
        when(tokenBlacklist.contains(TOKEN)).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(handshake(TOKEN, null, attributes(), response));

        assertEquals(401, response.getStatus());
        verify(userService, never()).getById(anyLong());
    }

    @Test
    @DisplayName("token 合法但用户已不存在 → 401")
    void unknownUserRejected() {
        when(jwtUtil.parseToken(TOKEN)).thenReturn(claimsFor(USER_ID));
        when(userService.getById(USER_ID)).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(handshake(TOKEN, null, attributes(), response));

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("★ 账号被禁用 → 403（区别于 401，避免前端陷入反复跳登录的死循环）")
    void disabledUserRejectedWith403() {
        when(jwtUtil.parseToken(TOKEN)).thenReturn(claimsFor(USER_ID));
        User banned = enabledUser();
        banned.setStatus(0);
        when(userService.getById(USER_ID)).thenReturn(banned);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(handshake(TOKEN, null, attributes(), response));

        assertEquals(403, response.getStatus());
    }

    @Test
    @DisplayName("★ 鉴权通过：把身份写入会话属性，后续消息处理不再解析 Token")
    void successWritesAttributes() {
        when(jwtUtil.parseToken(TOKEN)).thenReturn(claimsFor(USER_ID));
        when(userService.getById(USER_ID)).thenReturn(enabledUser());
        Map<String, Object> attrs = attributes();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(handshake(TOKEN, null, attrs, response));

        assertEquals(USER_ID, attrs.get(ChatHandshakeInterceptor.ATTR_USER_ID));
        assertEquals("演示同学", attrs.get(ChatHandshakeInterceptor.ATTR_NICKNAME));
        assertEquals("stu_demo", attrs.get(ChatHandshakeInterceptor.ATTR_USERNAME));
        assertEquals(200, response.getStatus(), "成功时不应设置错误状态码");
    }

    @Test
    @DisplayName("★ 兼容两种取 token 方式：查询参数与 Sec-WebSocket-Protocol 头")
    void tokenFromEitherPlace() {
        when(jwtUtil.parseToken(TOKEN)).thenReturn(claimsFor(USER_ID));
        when(userService.getById(USER_ID)).thenReturn(enabledUser());

        assertTrue(handshake(TOKEN, null, attributes(), new MockHttpServletResponse()), "查询参数方式");
        assertTrue(handshake(null, TOKEN, attributes(), new MockHttpServletResponse()), "子协议头方式");
    }

    @Test
    @DisplayName("查询参数优先于子协议头")
    void queryParamWins() {
        when(jwtUtil.parseToken("from-query")).thenReturn(claimsFor(USER_ID));
        when(userService.getById(USER_ID)).thenReturn(enabledUser());

        assertTrue(handshake("from-query", "from-header", attributes(), new MockHttpServletResponse()));
        verify(jwtUtil).parseToken("from-query");
        verify(jwtUtil, never()).parseToken("from-header");
    }

    @Test
    @DisplayName("token 两端空格会被去掉（真正生效的是子协议头路径）")
    void tokenTrimmed() {
        when(jwtUtil.parseToken(TOKEN)).thenReturn(claimsFor(USER_ID));
        when(userService.getById(USER_ID)).thenReturn(enabledUser());

        // 注意：查询参数在 URI 层面不允许裸空格（真实客户端会编码成 %20），
        // 而 UriComponentsBuilder.build() 不做解码，因此 trim 实际保护的是
        // 子协议头这条路径 —— 这里的用例就按 header 传，才是准确的行为。
        assertTrue(handshake(null, "  " + TOKEN + "  ", attributes(), new MockHttpServletResponse()));
        verify(jwtUtil).parseToken(TOKEN);
    }

    @Test
    @DisplayName("afterHandshake：握手异常只记日志，不向外抛")
    void afterHandshakeSwallowsException() {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/ws/chat");
        interceptor.afterHandshake(new ServletServerHttpRequest(raw),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class), new RuntimeException("boom"));
        // 没抛异常即通过
        assertTrue(true);
    }
}
