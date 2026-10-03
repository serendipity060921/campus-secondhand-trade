package com.campus.trade.common.cache;

import com.campus.trade.common.util.JwtUtil;
import com.campus.trade.service.CacheService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;

/**
 * JWT 黑名单（v0.12）。
 *
 * <p>JWT 是无状态的，服务端不保存会话，因此"退出登录"原本只是前端删掉 Token ——
 * 如果 Token 被复制走，在过期前依然可用。这里补上黑名单：</p>
 * <ol>
 *   <li>退出登录时，把 Token 的 SHA-256 摘要写入 Redis，TTL 设为该 Token 的<b>剩余有效期</b>
 *       （过期后自动清理，不会无限占内存）；</li>
 *   <li>拦截器每次校验 Token 后查一次黑名单，命中即视为未登录（401）；</li>
 *   <li>只存摘要不存原 Token，避免 Redis 里出现可直接使用的凭证。</li>
 * </ol>
 *
 * <p>这样既保留了 JWT 无状态的好处，又实现了可撤销的会话，属于"够用且不过度设计"的折中。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    private final CacheService cacheService;
    private final CacheKeys keys;
    private final JwtUtil jwtUtil;

    /** 把 Token 加入黑名单（TTL = 剩余有效期） */
    public void add(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        long ttl = remainingSeconds(token);
        if (ttl <= 0) {
            return;      // 已过期，无需拉黑
        }
        cacheService.setFlag(keys.jwtBlacklist(hash(token)), ttl);
        log.info("[退出登录] Token 已加入黑名单，剩余有效期 {} 秒", ttl);
    }

    /** Token 是否已被拉黑 */
    public boolean contains(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return cacheService.hasFlag(keys.jwtBlacklist(hash(token)));
    }

    /**
     * 从请求头解析 Token（兼容 {@code Authorization: Bearer xxx} 与 {@code token: xxx} 两种写法）。
     * 供退出登录接口使用。
     */
    public static String resolveToken(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String fallback = request.getHeader("token");
        return fallback == null ? null : fallback.trim();
    }

    private long remainingSeconds(String token) {
        try {
            Claims claims = jwtUtil.parseToken(token);
            Date expiration = claims.getExpiration();
            if (expiration == null) {
                return 0;
            }
            return (expiration.getTime() - System.currentTimeMillis()) / 1000;
        } catch (Exception e) {
            return 0;      // 解析失败（含已过期）→ 无需拉黑
        }
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.valueOf(token.hashCode());
        }
    }
}
