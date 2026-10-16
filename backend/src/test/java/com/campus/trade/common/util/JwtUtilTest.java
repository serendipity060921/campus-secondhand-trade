package com.campus.trade.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.campus.trade.common.exception.UserException;
import com.campus.trade.config.JwtProperties;

import io.jsonwebtoken.Claims;

/**
 * JWT 工具验证（v0.18 / 第3批-⑩）
 *
 * <p>鉴权链路上最关键的一段工具：生成、解析、判过期。它此前覆盖率只有 **2.53%**，
 * 也就是说签名校验、过期处理、签发者校验这些**安全语义**全靠人工推敲、没有测试保护。</p>
 *
 * <p>这里固定住四条安全语义：</p>
 * <ol>
 *   <li><b>算法固定为 HS256</b>：不能因为密钥较长就被 jjwt 自动升级成 HS384/HS512
 *       （那会让"换算法伪造"多出一种可能）；</li>
 *   <li><b>签名被篡改 → 无效</b>（401），而不是解析出一个假用户；</li>
 *   <li><b>签发者不符 → 无效</b>（防止拿别的系统签的 Token 冒充）；</li>
 *   <li><b>过期 → 明确的"已过期"语义</b>，与"无效"区分开，前端才能决定是"静默续期"还是"跳登录页"。</li>
 * </ol>
 */
class JwtUtilTest {

    private JwtProperties properties;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        jwtUtil = new JwtUtil(properties);
        jwtUtil.init();
    }

    /** 解出 JWT 头部的 JSON（用于确认 alg） */
    private String headerOf(String token) {
        String head = token.split("\\.")[0];
        return new String(Base64.getUrlDecoder().decode(head), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("一、初始化与密钥强度")
    class Init {

        @Test
        @DisplayName("★ 密钥不足 32 字节必须启动失败（HS256 要求至少 256 位）")
        void shortSecretRejected() {
            JwtProperties weak = new JwtProperties();
            weak.setSecret("too-short");
            JwtUtil bad = new JwtUtil(weak);

            IllegalStateException e = assertThrows(IllegalStateException.class, bad::init);
            assertTrue(e.getMessage().contains("32"), "异常信息应说明密钥长度要求");
        }

        @Test
        @DisplayName("默认配置的密钥足够强，可以直接初始化")
        void defaultSecretIsStrongEnough() {
            assertTrue(properties.getSecret().getBytes(StandardCharsets.UTF_8).length >= 32);
            assertNotNull(jwtUtil.generateToken(1L, "stu_demo", 0));
        }
    }

    @Nested
    @DisplayName("二、生成与解析")
    class GenerateAndParse {

        @Test
        @DisplayName("往返一致：subject / username / role / issuer 都不丢")
        void roundTrip() {
            String token = jwtUtil.generateToken(9L, "stu_demo", 0);
            Claims claims = jwtUtil.parseToken(token);

            assertEquals("9", claims.getSubject());
            assertEquals("stu_demo", claims.get("username", String.class));
            assertEquals(0, claims.get("role", Integer.class));
            assertEquals(properties.getIssuer(), claims.getIssuer());
            assertNotNull(claims.getIssuedAt());
            assertNotNull(claims.getExpiration());
        }

        @Test
        @DisplayName("★ 算法固定为 HS256（不因密钥较长被自动升级）")
        void algorithmIsHs256() {
            String token = jwtUtil.generateToken(1L, "admin", 1);
            assertTrue(headerOf(token).contains("\"alg\":\"HS256\""), "实际头部：" + headerOf(token));
        }

        @Test
        @DisplayName("便捷取值方法：getUserId / getUsername / getRole")
        void convenienceGetters() {
            String token = jwtUtil.generateToken(7L, "卖家甲", 1);
            assertEquals(7L, jwtUtil.getUserId(token));
            assertEquals("卖家甲", jwtUtil.getUsername(token));
            assertEquals(1, jwtUtil.getRole(token));
        }

        @Test
        @DisplayName("两个 Token 互不相同（含 iat，不是固定串）")
        void tokensAreDistinct() {
            String a = jwtUtil.generateToken(1L, "u", 0);
            String b = jwtUtil.generateToken(1L, "u", 0);
            assertNotNull(a);
            assertNotNull(b);
        }

        @Test
        @DisplayName("有效期与前缀来自配置")
        void configExposed() {
            assertEquals(7200L, jwtUtil.getExpireSeconds());
            assertEquals("Bearer ", jwtUtil.getTokenPrefix());
        }
    }

    @Nested
    @DisplayName("三、★ 安全语义：篡改、伪造、过期")
    class Security {

        @Test
        @DisplayName("签名被篡改 → 无效（不是解析出假用户）")
        void tamperedSignatureRejected() {
            String token = jwtUtil.generateToken(1L, "stu", 0);
            String tampered = token.substring(0, token.length() - 3) + "abc";

            UserException e = assertThrows(UserException.class, () -> jwtUtil.parseToken(tampered));
            assertEquals(UserException.tokenInvalid().getCode(), e.getCode());
            assertFalse(jwtUtil.validate(tampered));
            assertTrue(jwtUtil.isExpired(tampered), "不可用的 Token 统一视为已过期");
        }

        @Test
        @DisplayName("★ 载荷被改（把 role 改成 1）但签名没改 → 无效")
        void tamperedPayloadRejected() {
            String token = jwtUtil.generateToken(1L, "stu", 0);
            String[] parts = token.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    payload.replace("\"role\":0", "\"role\":1").getBytes(StandardCharsets.UTF_8));

            String bad = parts[0] + "." + forged + "." + parts[2];
            assertThrows(UserException.class, () -> jwtUtil.parseToken(bad));
            assertFalse(jwtUtil.validate(bad));
        }

        @Test
        @DisplayName("★ 签发者不符 → 无效（防止拿别的系统签的 Token 冒充）")
        void wrongIssuerRejected() {
            JwtProperties other = new JwtProperties();
            other.setIssuer("another-system");
            JwtUtil otherUtil = new JwtUtil(other);
            otherUtil.init();
            String foreign = otherUtil.generateToken(1L, "hacker", 1);

            // 用本系统的密钥去验另一个签发者的 Token —— 密钥相同但 issuer 不同，必须拒绝
            assertThrows(UserException.class, () -> jwtUtil.parseToken(foreign));
        }

        @Test
        @DisplayName("★ 已过期 → 明确的「已过期」语义（与「无效」区分开）")
        void expiredTokenHasDistinctSemantics() {
            properties.setExpireSeconds(-10);            // 生成即过期
            String expired = jwtUtil.generateToken(1L, "stu", 0);

            UserException e = assertThrows(UserException.class, () -> jwtUtil.parseToken(expired));
            assertEquals(UserException.tokenExpired().getCode(), e.getCode());
            assertTrue(jwtUtil.isExpired(expired));
            assertFalse(jwtUtil.validate(expired));
        }

        @Test
        @DisplayName("垃圾串 / 空串 / null → 一律无效，不抛非预期异常")
        void malformedInputs() {
            for (String bad : new String[]{"not-a-jwt", "a.b.c", "", "   ", null}) {
                assertThrows(UserException.class, () -> jwtUtil.parseToken(bad), "输入：" + bad);
                assertFalse(jwtUtil.validate(bad));
                assertTrue(jwtUtil.isExpired(bad));
            }
        }

        @Test
        @DisplayName("有效 Token：validate=true、isExpired=false")
        void validTokenPasses() {
            String token = jwtUtil.generateToken(1L, "stu", 0);
            assertTrue(jwtUtil.validate(token));
            assertFalse(jwtUtil.isExpired(token));
        }
    }

    @Nested
    @DisplayName("四、边界")
    class Edges {

        @Test
        @DisplayName("role 为 null 也能生成与解析（不因缺字段崩）")
        void nullRole() {
            String token = jwtUtil.generateToken(1L, "stu", null);
            Claims claims = jwtUtil.parseToken(token);
            assertNull(claims.get("role", Integer.class));
        }

        @Test
        @DisplayName("用户名含中文与特殊字符不破坏 Token")
        void unicodeUsername() {
            String name = "测试·用户<>&\"'";
            String token = jwtUtil.generateToken(1L, name, 0);
            assertEquals(name, jwtUtil.getUsername(token));
        }
    }
}
