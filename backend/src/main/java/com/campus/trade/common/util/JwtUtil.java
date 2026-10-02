package com.campus.trade.common.util;

import com.campus.trade.common.exception.UserException;
import com.campus.trade.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（v0.04）。
 *
 * <p>提供三件事：<b>生成 Token</b>、<b>解析 Token</b>、<b>判断 Token 是否过期</b>。</p>
 *
 * <p>Token 载荷（payload）设计：</p>
 * <pre>
 * {
 *   "sub": "1",                 // 用户ID
 *   "username": "admin",        // 用户名
 *   "role": 1,                  // 角色：0学生 1管理员
 *   "iss": "campus-trade",      // 签发者
 *   "iat": 1717200000,          // 签发时间
 *   "exp": 1717207200           // 过期时间（默认 2 小时）
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties jwtProperties;

    /** HS256 签名密钥，由配置的 secret 派生 */
    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 长度不足 32 字节，HS256 算法要求至少 256 位密钥");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT 初始化完成，Token 有效期 {} 秒", jwtProperties.getExpireSeconds());
    }

    /* ==================== 1. 生成 Token ==================== */

    /**
     * 生成 Token。
     *
     * @param userId   用户ID
     * @param username 用户名
     * @param role     角色：0普通学生 1管理员
     * @return 带签名的 JWT 字符串
     */
    public String generateToken(Long userId, String username, Integer role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + jwtProperties.getExpireSeconds() * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiration(expiration)
                // 显式指定 HS256，避免 jjwt 因密钥较长而自动选择 HS384/HS512，保证算法固定
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /* ==================== 2. 解析 Token ==================== */

    /**
     * 解析 Token，返回载荷。
     *
     * @throws UserException Token 过期（code=401）或无效（code=401）
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(jwtProperties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("[JWT 过期] {}", e.getMessage());
            throw UserException.tokenExpired();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT 无效] {}", e.getMessage());
            throw UserException.tokenInvalid();
        }
    }

    /** 取用户ID */
    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    /** 取用户名 */
    public String getUsername(String token) {
        return parseToken(token).get(CLAIM_USERNAME, String.class);
    }

    /** 取角色 */
    public Integer getRole(String token) {
        return parseToken(token).get(CLAIM_ROLE, Integer.class);
    }

    /* ==================== 3. 判断 Token 是否过期 ==================== */

    /**
     * 判断 Token 是否已过期（或已不可用）。
     *
     * @return true = 已过期 / 无法解析；false = 仍在有效期内
     */
    public boolean isExpired(String token) {
        try {
            return parseToken(token).getExpiration().before(new Date());
        } catch (UserException e) {
            // Token 过期或无效，统一视为"不可用"
            return true;
        }
    }

    /**
     * 校验 Token 是否有效（不抛异常版本，便于在工具类/测试中直接使用）。
     *
     * @return true = 有效且未过期
     */
    public boolean validate(String token) {
        try {
            parseToken(token);
            return true;
        } catch (UserException e) {
            return false;
        }
    }

    /** Token 有效期（秒），返回给前端用于展示/自动续期判断 */
    public long getExpireSeconds() {
        return jwtProperties.getExpireSeconds();
    }

    /** 请求头中 Token 的前缀，例如 "Bearer " */
    public String getTokenPrefix() {
        return jwtProperties.getPrefix();
    }
}
