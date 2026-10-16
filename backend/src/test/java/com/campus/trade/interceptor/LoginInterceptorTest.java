package com.campus.trade.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.cache.TokenBlacklist;
import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.BusinessException;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.common.util.JwtUtil;
import com.campus.trade.config.JwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;

/**
 * 登录拦截器验证（v0.18 / 收尾⑨-3）
 *
 * <p>鉴权是整个后端的"门"，而它的实现方式是<b>注解决定是否拦截</b>（不是配置白名单）。
 * 这种写法安全与否，完全取决于拦截器本身的几条分支，因此这里逐条固定：</p>
 *
 * <ul>
 *   <li><b>公开接口</b>：无注解 → 放行；但若带了有效 Token，要顺手把用户放进上下文
 *       （业务层靠它判断"卖家本人可见"），Token 有问题则<b>静默忽略</b>，绝不影响公开访问；</li>
 *   <li><b>受保护接口</b>：无 Token → 401；Token 无效/过期 → 401；<b>黑名单（已退出登录）→ 401</b>；</li>
 *   <li><b>管理员接口</b>：`@LoginRequired(admin = true)` 且角色不是管理员 → 403；</li>
 *   <li>★ <b>请求结束必须清理 ThreadLocal</b> —— 否则线程池复用会让"上一个请求的登录用户"
 *       残留到下一个请求，造成越权（这类缺陷极难复现，但一旦出现就是安全问题）。</li>
 * </ul>
 */
class LoginInterceptorTest {

    private JwtUtil jwtUtil;
    private TokenBlacklist tokenBlacklist;
    private LoginInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        tokenBlacklist = mock(TokenBlacklist.class);
        // ★ 这里必须用**真实**的 JwtProperties（默认 header=Authorization、prefix="Bearer "）：
        //   若用 mock，getHeader()/getPrefix() 返回 null，resolveToken 取不到 token，
        //   会导致"有效 Token 也被判 401 / 上下文为空"——本次实测 7 个用例因此失败。
        interceptor = new LoginInterceptor(jwtUtil, new JwtProperties(), tokenBlacklist);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 用于构造 HandlerMethod 的测试控制器 */
    static class TestController {
        @LoginRequired
        public void needLogin() {
        }

        @LoginRequired(admin = true)
        public void needAdmin() {
        }

        public void open() {
        }
    }

    private HandlerMethod handler(String method) throws Exception {
        return new HandlerMethod(new TestController(),
                TestController.class.getMethod(method));
    }

    private MockHttpServletRequest request(String method, String authHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/demo");
        if (authHeader != null) {
            req.addHeader("Authorization", authHeader);
        }
        return req;
    }

    private Claims claims(Long userId, String username, Integer role) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put(Claims.SUBJECT, String.valueOf(userId));
        map.put("username", username);
        map.put("role", role);
        return new DefaultClaims(map);
    }

    @Nested
    @DisplayName("一、放行场景")
    class PassThrough {

        @Test
        @DisplayName("跨域预检（OPTIONS）直接放行，不做鉴权")
        void optionsPasses() throws Exception {
            assertTrue(interceptor.preHandle(request("OPTIONS", null), new MockHttpServletResponse(),
                    handler("needLogin")));
            verify(jwtUtil, never()).parseToken(anyString());
        }

        @Test
        @DisplayName("非 Controller 方法（静态资源、错误页）放行")
        void nonHandlerMethodPasses() {
            assertTrue(interceptor.preHandle(request("GET", null), new MockHttpServletResponse(),
                    new Object()));
        }

        @Test
        @DisplayName("★ 公开接口不带 Token：放行，且上下文保持为空")
        void openEndpointWithoutToken() throws Exception {
            assertTrue(interceptor.preHandle(request("GET", null), new MockHttpServletResponse(),
                    handler("open")));
            assertNull(UserContext.get());
        }

        @Test
        @DisplayName("★ 公开接口带有效 Token：放行并顺手写入上下文（供「卖家本人可见」判断）")
        void openEndpointWithTokenPopulatesContext() throws Exception {
            when(jwtUtil.parseToken("good")).thenReturn(claims(7L, "stu_demo", 0));

            assertTrue(interceptor.preHandle(request("GET", "Bearer good"), new MockHttpServletResponse(),
                    handler("open")));

            LoginUser user = UserContext.get();
            assertEquals(7L, user.userId());
            assertEquals("stu_demo", user.username());
        }

        @Test
        @DisplayName("★ 公开接口带无效 Token：静默忽略，绝不影响公开访问")
        void openEndpointWithBadTokenStillPasses() throws Exception {
            when(jwtUtil.parseToken("bad")).thenThrow(new RuntimeException("invalid"));

            assertTrue(interceptor.preHandle(request("GET", "Bearer bad"), new MockHttpServletResponse(),
                    handler("open")));
            assertNull(UserContext.get());
        }
    }

    @Nested
    @DisplayName("二、受保护接口：三种情况都要 401")
    class Protected {

        @Test
        @DisplayName("未携带 Token → 抛 401（tokenMissing）")
        void missingToken() throws Exception {
            UserException e = assertThrows(UserException.class,
                    () -> interceptor.preHandle(request("GET", null), new MockHttpServletResponse(),
                            handler("needLogin")));
            assertEquals(UserException.tokenMissing().getCode(), e.getCode());
        }

        @Test
        @DisplayName("Token 无效或过期 → 由 JwtUtil 抛出对应异常（拦截器不吞）")
        void invalidToken() throws Exception {
            when(jwtUtil.parseToken("bad")).thenThrow(UserException.tokenInvalid());

            assertThrows(UserException.class,
                    () -> interceptor.preHandle(request("GET", "Bearer bad"),
                            new MockHttpServletResponse(), handler("needLogin")));
            assertNull(UserContext.get(), "解析失败不应写入上下文");
        }

        @Test
        @DisplayName("★ 黑名单 Token（已退出登录）→ 401，即使签名本身有效")
        void blacklistedToken() throws Exception {
            when(jwtUtil.parseToken("logout")).thenReturn(claims(7L, "stu_demo", 0));
            when(tokenBlacklist.contains("logout")).thenReturn(true);

            UserException e = assertThrows(UserException.class,
                    () -> interceptor.preHandle(request("GET", "Bearer logout"),
                            new MockHttpServletResponse(), handler("needLogin")));

            assertEquals(UserException.tokenInvalid().getCode(), e.getCode());
            assertNull(UserContext.get(), "黑名单命中不应写入上下文");
        }

        @Test
        @DisplayName("有效 Token → 放行并写入上下文（userId / username / role）")
        void validToken() throws Exception {
            when(jwtUtil.parseToken("good")).thenReturn(claims(7L, "stu_demo", 0));

            assertTrue(interceptor.preHandle(request("GET", "Bearer good"), new MockHttpServletResponse(),
                    handler("needLogin")));

            LoginUser user = UserContext.get();
            assertEquals(7L, user.userId());
            assertEquals("stu_demo", user.username());
            assertEquals(0, user.role());
            assertFalse(user.isAdmin());
        }

        @Test
        @DisplayName("Authorization 头不带 Bearer 前缀也能识别（兼容部分客户端）")
        void tokenWithoutBearerPrefix() throws Exception {
            when(jwtUtil.parseToken("raw-token")).thenReturn(claims(7L, "stu_demo", 0));

            assertTrue(interceptor.preHandle(request("GET", "raw-token"), new MockHttpServletResponse(),
                    handler("needLogin")));
            assertEquals(7L, UserContext.get().userId());
        }
    }

    @Nested
    @DisplayName("三、管理员接口的角色校验")
    class AdminOnly {

        @Test
        @DisplayName("★ 普通用户访问管理员接口 → 403（业务码 NO_PERMISSION）")
        void nonAdminRejected() throws Exception {
            when(jwtUtil.parseToken("good")).thenReturn(claims(7L, "stu_demo", 0));

            BusinessException e = assertThrows(BusinessException.class,
                    () -> interceptor.preHandle(request("GET", "Bearer good"),
                            new MockHttpServletResponse(), handler("needAdmin")));

            assertEquals(403, e.getCode());
            assertNull(UserContext.get(), "权限不足不应写入上下文");
        }

        @Test
        @DisplayName("角色为空同样按无权限处理（不能因缺字段就放行）")
        void nullRoleRejected() throws Exception {
            when(jwtUtil.parseToken("good")).thenReturn(claims(7L, "stu_demo", null));

            assertThrows(BusinessException.class,
                    () -> interceptor.preHandle(request("GET", "Bearer good"),
                            new MockHttpServletResponse(), handler("needAdmin")));
        }

        @Test
        @DisplayName("管理员（role=1）放行")
        void adminAllowed() throws Exception {
            when(jwtUtil.parseToken("good")).thenReturn(claims(1L, "admin", 1));

            assertTrue(interceptor.preHandle(request("GET", "Bearer good"), new MockHttpServletResponse(),
                    handler("needAdmin")));

            assertTrue(UserContext.get().isAdmin());
        }
    }

    @Nested
    @DisplayName("四、★ 上下文清理（线程池复用下最容易出的安全问题）")
    class ContextCleanup {

        @Test
        @DisplayName("afterCompletion 必须清空 ThreadLocal —— 否则下一个请求会继承上一个用户")
        void clearsContextAfterRequest() throws Exception {
            when(jwtUtil.parseToken("good")).thenReturn(claims(7L, "stu_demo", 0));
            interceptor.preHandle(request("GET", "Bearer good"), new MockHttpServletResponse(),
                    handler("needLogin"));
            assertEquals(7L, UserContext.get().userId());

            interceptor.afterCompletion(request("GET", "Bearer good"), new MockHttpServletResponse(),
                    handler("needLogin"), null);

            assertNull(UserContext.get(), "请求结束后必须清空，避免线程复用导致用户串号");
        }

        @Test
        @DisplayName("即使请求抛了异常，afterCompletion 也要清理")
        void clearsContextEvenOnException() throws Exception {
            UserContext.set(new LoginUser(7L, "stu_demo", 0));

            interceptor.afterCompletion(request("GET", null), new MockHttpServletResponse(),
                    handler("needLogin"), new RuntimeException("boom"));

            assertNull(UserContext.get());
        }
    }
}
