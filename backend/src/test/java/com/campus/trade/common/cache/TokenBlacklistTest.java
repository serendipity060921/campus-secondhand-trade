package com.campus.trade.common.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import com.campus.trade.common.util.JwtUtil;
import com.campus.trade.config.JwtProperties;
import com.campus.trade.service.CacheService;

/**
 * JWT 黑名单验证（v0.18 / 第3批-⑩）
 *
 * <p>"退出登录"在无状态 JWT 里是最容易被漏掉的一环：删掉前端 Token 并不等于服务端失效它。
 * 黑名单补上了这块，但它的实现细节全是安全语义，此前覆盖率只有 2.86%：</p>
 *
 * <ul>
 *   <li>★ <b>只存摘要、不存原 Token</b> —— 否则 Redis 里就等于躺着一批可直接使用的凭证；
 *       本测试直接断言写入的 key 是 64 位十六进制（SHA-256）；</li>
 *   <li>★ <b>TTL = 该 Token 的剩余有效期</b> —— 过期后自动清理，不会无限占内存；
 *       已过期的 Token 直接跳过（没必要拉黑）；</li>
 *   <li>空 Token / null 一律安全返回，不写 Redis、不抛异常；</li>
 *   <li>请求头解析兼容 {@code Authorization: Bearer xxx} 与 {@code token: xxx}。</li>
 * </ul>
 */
class TokenBlacklistTest {

    private CacheService cacheService;
    private CacheKeys keys;
    private JwtUtil jwtUtil;
    private TokenBlacklist blacklist;

    @BeforeEach
    void setUp() {
        cacheService = mock(CacheService.class);
        keys = mock(CacheKeys.class);
        // 让 key 里带上"摘要"本身，便于断言它确实是哈希而不是原 Token
        when(keys.jwtBlacklist(anyString())).thenAnswer(inv -> "campus:jwt:blacklist:" + inv.getArgument(0));

        JwtProperties props = new JwtProperties();
        jwtUtil = new JwtUtil(props);
        jwtUtil.init();

        blacklist = new TokenBlacklist(cacheService, keys, jwtUtil);
    }

    private String validToken() {
        return jwtUtil.generateToken(9L, "stu_demo", 0);
    }

    @Nested
    @DisplayName("一、加入黑名单")
    class Add {

        @Test
        @DisplayName("★ 写入的是 SHA-256 摘要（64 位十六进制），不是原 Token")
        void storesHashNotRawToken() {
            String token = validToken();
            blacklist.add(token);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
            verify(cacheService).setFlag(key.capture(), ttl.capture());

            String stored = key.getValue();
            assertTrue(stored.startsWith("campus:jwt:blacklist:"), "应使用统一的缓存键前缀");
            String digest = stored.substring("campus:jwt:blacklist:".length());
            assertEquals(64, digest.length(), "SHA-256 十六进制应为 64 位");
            assertTrue(digest.matches("[0-9a-f]{64}"), "应为十六进制小写：" + digest);
            assertFalse(stored.contains(token), "★ 绝不能把原 Token 写进 Redis");
            assertTrue(ttl.getValue() > 0, "TTL 应大于 0");
            assertTrue(ttl.getValue() <= 7200, "TTL 不应超过 Token 本身的有效期");
        }

        @Test
        @DisplayName("★ TTL 取的是「剩余有效期」，随 Token 变老而变小")
        void ttlIsRemainingLifetime() {
            JwtProperties shortLived = new JwtProperties();
            shortLived.setExpireSeconds(100);
            JwtUtil shortUtil = new JwtUtil(shortLived);
            shortUtil.init();
            TokenBlacklist bl = new TokenBlacklist(cacheService, keys, shortUtil);

            bl.add(shortUtil.generateToken(1L, "u", 0));

            ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
            verify(cacheService).setFlag(anyString(), ttl.capture());
            assertTrue(ttl.getValue() > 0 && ttl.getValue() <= 100,
                    "TTL 应在 (0, 100] 秒内，实际 " + ttl.getValue());
        }

        @Test
        @DisplayName("★ 已过期的 Token 直接跳过（无需拉黑，更不该写 Redis）")
        void expiredTokenSkipped() {
            JwtProperties expired = new JwtProperties();
            expired.setExpireSeconds(-10);
            JwtUtil expiredUtil = new JwtUtil(expired);
            expiredUtil.init();

            new TokenBlacklist(cacheService, keys, expiredUtil)
                    .add(expiredUtil.generateToken(1L, "u", 0));

            verify(cacheService, never()).setFlag(anyString(), anyLong());
        }

        @Test
        @DisplayName("空 Token / null → 安全返回，不写 Redis")
        void blankTokenIgnored() {
            blacklist.add(null);
            blacklist.add("");
            blacklist.add("   ");

            verify(cacheService, never()).setFlag(anyString(), anyLong());
        }

        @Test
        @DisplayName("无法解析的垃圾串 → 视为不可用，不写 Redis（remainingSeconds 返回 0）")
        void malformedTokenIgnored() {
            blacklist.add("not.a.jwt");

            verify(cacheService, never()).setFlag(anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("二、查询是否已拉黑")
    class Contains {

        @Test
        @DisplayName("命中 → true")
        void hit() {
            when(cacheService.hasFlag(anyString())).thenReturn(true);
            assertTrue(blacklist.contains(validToken()));
        }

        @Test
        @DisplayName("未命中 → false")
        void miss() {
            when(cacheService.hasFlag(anyString())).thenReturn(false);
            assertFalse(blacklist.contains(validToken()));
        }

        @Test
        @DisplayName("空 Token / null → false，且不查 Redis")
        void blankIsNotBlacklisted() {
            assertFalse(blacklist.contains(null));
            assertFalse(blacklist.contains(""));
            assertFalse(blacklist.contains("  "));

            verify(cacheService, never()).hasFlag(anyString());
        }

        @Test
        @DisplayName("★ 查询用的 key 与写入时一致（同一条 Token 才能对上）")
        void sameKeyForAddAndContains() {
            String token = validToken();
            blacklist.add(token);
            blacklist.contains(token);

            ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
            verify(cacheService).setFlag(written.capture(), anyLong());
            ArgumentCaptor<String> queried = ArgumentCaptor.forClass(String.class);
            verify(cacheService).hasFlag(queried.capture());

            assertEquals(written.getValue(), queried.getValue());
        }

        @Test
        @DisplayName("★ 如实记录：Redis 故障时异常会向上抛（fail-closed，不会误判为「未拉黑」）")
        void redisFailurePropagates() {
            when(cacheService.hasFlag(anyString())).thenThrow(new RuntimeException("redis down"));

            assertThrows(RuntimeException.class, () -> blacklist.contains(validToken()),
                    "宁可让请求失败，也不能把已拉黑的 Token 当成有效");
        }
    }

    @Nested
    @DisplayName("三、从请求头解析 Token")
    class ResolveToken {

        @Test
        @DisplayName("标准写法：Authorization: Bearer xxx")
        void bearerPrefix() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", "Bearer abc.def.ghi");
            assertEquals("abc.def.ghi", TokenBlacklist.resolveToken(req));
        }

        @Test
        @DisplayName("容忍不带前缀的 Authorization")
        void rawAuthorization() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", "abc.def.ghi");
            assertEquals("abc.def.ghi", TokenBlacklist.resolveToken(req));
        }

        @Test
        @DisplayName("兜底写法：token 头")
        void tokenHeader() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("token", "abc.def.ghi");
            assertEquals("abc.def.ghi", TokenBlacklist.resolveToken(req));
        }

        @Test
        @DisplayName("两端空格会被去掉")
        void trimmed() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", "Bearer   abc.def.ghi  ");
            assertEquals("abc.def.ghi", TokenBlacklist.resolveToken(req));
        }

        @Test
        @DisplayName("什么都没有 → null；request 本身为 null → null")
        void emptyCases() {
            assertNull(TokenBlacklist.resolveToken(new MockHttpServletRequest()));
            assertNull(TokenBlacklist.resolveToken(null));
        }
    }
}
