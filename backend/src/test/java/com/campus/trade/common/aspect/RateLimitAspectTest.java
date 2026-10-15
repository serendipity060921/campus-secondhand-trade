package com.campus.trade.common.aspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.campus.trade.common.annotation.RateLimit;
import com.campus.trade.common.cache.CacheKeys;
import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.BusinessException;
import com.campus.trade.common.result.ResultCode;
import com.campus.trade.config.CacheProperties;

/**
 * 注解式限流切面验证（v0.17）
 *
 * <p>切面里有三条容易被改坏、但一旦改坏后果很严重的逻辑，本测试把它们固定下来：</p>
 * <ol>
 *   <li><b>本机跳过</b>：开发与压测时回环/内网地址默认不限流（否则本机压测会被自己的限流挡住）；</li>
 *   <li><b>超限即拒</b>：窗口内超过上限时抛出 429 业务异常，且提示里带出窗口与上限；</li>
 *   <li>★ <b>Redis 故障降级放行</b>：Redis 不可用时放弃限流而不是拒绝全部请求 ——
 *       这是"可用性优先于限流"的明确取舍，必须有测试守住，否则一次改动就可能让整个站点被限流打死。</li>
 * </ol>
 */
class RateLimitAspectTest {

    private StringRedisTemplate redis;
    private CacheProperties props;
    private CacheKeys keys;
    private RateLimitAspect aspect;
    private ProceedingJoinPoint joinPoint;
    private RateLimit rateLimit;

    @BeforeEach
    void setUp() throws Throwable {
        redis = mock(StringRedisTemplate.class);
        props = new CacheProperties();
        props.setKeyPrefix("campus");
        props.setRateLimitSkipLocal(true);      // 与生产默认一致，个别用例再单独关闭
        keys = new CacheKeys(props);
        aspect = new RateLimitAspect(redis, keys, props);

        joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("OK");

        rateLimit = mock(RateLimit.class);
        when(rateLimit.key()).thenReturn("login");
        when(rateLimit.limit()).thenReturn(10);
        when(rateLimit.window()).thenReturn(60);
        when(rateLimit.dimension()).thenReturn(RateLimit.Dimension.IP);
        when(rateLimit.message()).thenReturn("登录过于频繁");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        UserContext.clear();
    }

    /** 指定客户端 IP（通过 Mock 请求 + X-Forwarded-For 控制） */
    private void withClientIp(String ip, String forwardedFor) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(ip);
        if (forwardedFor != null) {
            req.addHeader("X-Forwarded-For", forwardedFor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private void redisReturns(Long value) {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), any(List.class), any()))
                .thenReturn(value);
    }

    @Nested
    @DisplayName("一、本机跳过限流（开发与压测友好）")
    class LocalSkip {

        @Test
        @DisplayName("回环地址 + 开关打开 → 直接放行，且完全不访问 Redis")
        void loopbackSkipsRedis() throws Throwable {
            withClientIp("127.0.0.1", null);

            Object result = aspect.around(joinPoint, rateLimit);

            assertEquals("OK", result);
            verify(redis, never()).execute(ArgumentMatchers.<RedisScript<Long>>any(), any(List.class), any());
        }

        @Test
        @DisplayName("内网地址（192.168./10./172.16.）同样跳过")
        void privateRangesSkip() throws Throwable {
            withClientIp("192.168.1.20", null);
            aspect.around(joinPoint, rateLimit);
            withClientIp("10.0.0.5", null);
            aspect.around(joinPoint, rateLimit);
            withClientIp("172.16.9.9", null);
            aspect.around(joinPoint, rateLimit);

            verify(redis, never()).execute(ArgumentMatchers.<RedisScript<Long>>any(), any(List.class), any());
        }

        @Test
        @DisplayName("公网地址不会被跳过（限流真正生效）")
        void publicIpIsLimited() throws Throwable {
            props.setRateLimitSkipLocal(true);
            withClientIp("8.8.8.8", null);
            redisReturns(1L);

            aspect.around(joinPoint, rateLimit);

            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:login:ip8.8.8.8")), eq("60"));
        }

        @Test
        @DisplayName("关闭「本机跳过」后，回环地址也参与限流")
        void skipDisabledLimitsLoopback() throws Throwable {
            props.setRateLimitSkipLocal(false);
            withClientIp("127.0.0.1", null);
            redisReturns(1L);

            aspect.around(joinPoint, rateLimit);

            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:login:ip127.0.0.1")), eq("60"));
        }
    }

    @Nested
    @DisplayName("二、窗口计数与超限拒绝")
    class CountingAndRejection {

        @BeforeEach
        void usePublicIp() {
            withClientIp("8.8.8.8", null);
        }

        @Test
        @DisplayName("计数未超上限：正常放行并执行目标方法")
        void underLimitProceeds() throws Throwable {
            redisReturns(10L);
            assertSame("OK", aspect.around(joinPoint, rateLimit));
            verify(joinPoint).proceed();
        }

        @Test
        @DisplayName("计数超过上限：抛 429，提示里带出窗口与上限")
        void overLimitRejected() {
            redisReturns(11L);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> aspect.around(joinPoint, rateLimit));

            assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
            assertEquals(429, e.getCode());
            assertTrue(e.getMessage().contains("登录过于频繁"));
            assertTrue(e.getMessage().contains("60"), "提示应说明窗口秒数：" + e.getMessage());
            assertTrue(e.getMessage().contains("10"), "提示应说明上限次数：" + e.getMessage());
        }

        @Test
        @DisplayName("计数为 null 时不误判为超限（放行）")
        void nullCounterProceeds() throws Throwable {
            redisReturns(null);
            assertSame("OK", aspect.around(joinPoint, rateLimit));
        }

        @Test
        @DisplayName("Redis 故障 → 降级放行（可用性优先于限流）")
        void redisFailureFailsOpen() throws Throwable {
            when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), any(List.class), any()))
                    .thenThrow(new RedisConnectionFailureException("redis down"));

            Object result = aspect.around(joinPoint, rateLimit);

            assertEquals("OK", result, "Redis 不可用时应放行请求，而不是拒绝全部流量");
            verify(joinPoint).proceed();
        }
    }

    @Nested
    @DisplayName("三、限流维度解析")
    class Dimensions {

        @BeforeEach
        void usePublicIp() {
            withClientIp("8.8.8.8", null);
            redisReturns(1L);
        }

        @Test
        @DisplayName("按用户维度：已登录时用用户 id（同一 IP 的不同用户互不影响）")
        void userDimensionUsesUserId() throws Throwable {
            when(rateLimit.dimension()).thenReturn(RateLimit.Dimension.USER);
            when(rateLimit.key()).thenReturn("publish");
            UserContext.set(new LoginUser(7L, "stu_demo", 0));

            aspect.around(joinPoint, rateLimit);

            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:publish:u7")), any());
        }

        @Test
        @DisplayName("按用户维度：未登录时退化为 IP")
        void userDimensionFallsBackToIp() throws Throwable {
            when(rateLimit.dimension()).thenReturn(RateLimit.Dimension.USER);
            UserContext.clear();

            aspect.around(joinPoint, rateLimit);

            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:login:ip8.8.8.8")), any());
        }

        @Test
        @DisplayName("按参数维度：直接取字符串参数值（如注册时的 username）")
        void paramDimensionUsesArgument() throws Throwable {
            when(rateLimit.dimension()).thenReturn(RateLimit.Dimension.PARAM);
            when(rateLimit.key()).thenReturn("register");
            when(rateLimit.paramName()).thenReturn("username");

            MethodSignature signature = mock(MethodSignature.class);
            when(signature.getParameterNames()).thenReturn(new String[]{"username"});
            when(joinPoint.getSignature()).thenReturn(signature);
            when(joinPoint.getArgs()).thenReturn(new Object[]{"stu_demo"});

            aspect.around(joinPoint, rateLimit);

            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:register:pstu_demo")), any());
        }

        @Test
        @DisplayName("按参数维度：参数是对象时反射取同名 getter")
        void paramDimensionReadsGetter() throws Throwable {
            when(rateLimit.dimension()).thenReturn(RateLimit.Dimension.PARAM);
            when(rateLimit.key()).thenReturn("register");
            when(rateLimit.paramName()).thenReturn("username");

            MethodSignature signature = mock(MethodSignature.class);
            when(signature.getParameterNames()).thenReturn(new String[]{"username"});
            when(joinPoint.getSignature()).thenReturn(signature);
            when(joinPoint.getArgs()).thenReturn(new Object[]{new UsernameHolder("stu_test01")});

            aspect.around(joinPoint, rateLimit);

            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:register:pstu_test01")), any());
        }

        @Test
        @DisplayName("按参数维度：取不到参数值时退化为 IP（不会变成无限流）")
        void paramMissingFallsBackToIp() throws Throwable {
            when(rateLimit.dimension()).thenReturn(RateLimit.Dimension.PARAM);
            when(rateLimit.paramName()).thenReturn("username");

            MethodSignature signature = mock(MethodSignature.class);
            when(signature.getParameterNames()).thenReturn(new String[]{"other"});
            when(joinPoint.getSignature()).thenReturn(signature);
            when(joinPoint.getArgs()).thenReturn(new Object[]{"x"});

            aspect.around(joinPoint, rateLimit);

            // 注意：这里是 "p" + ip（沿用 PARAM 维度的前缀），而不是 "ip" + ip。
            // 也就是说 PARAM 维度退化后与 IP 维度各自计数、互不干扰 —— 这是当前的真实行为，
            // 用测试固定下来，避免以后有人"顺手统一前缀"改变限流语义。
            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:login:p8.8.8.8")), any());
        }
    }

    @Nested
    @DisplayName("四、反向代理下的客户端 IP")
    class ClientIp {

        @Test
        @DisplayName("X-Forwarded-For 只取第一个地址（避免伪造整串）")
        void takesFirstForwardedIp() throws Throwable {
            withClientIp("127.0.0.1", "203.0.113.9, 70.41.3.18");
            redisReturns(1L);

            aspect.around(joinPoint, rateLimit);

            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:login:ip203.0.113.9")), any());
        }

        @Test
        @DisplayName("拿不到请求上下文时用 unknown，不会抛异常")
        void withoutRequestContext() throws Throwable {
            RequestContextHolder.resetRequestAttributes();
            props.setRateLimitSkipLocal(false);
            redisReturns(1L);

            aspect.around(joinPoint, rateLimit);

            verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                    eq(List.of("campus:ratelimit:login:ipunknown")), any());
        }
    }

    /** 供"反射取 getter"用例使用的参数对象 */
    static class UsernameHolder {
        private final String username;

        UsernameHolder(String username) {
            this.username = username;
        }

        public String getUsername() {
            return username;
        }
    }
}
