package com.campus.trade.websocket;

import com.campus.trade.common.util.JwtUtil;
import com.campus.trade.common.cache.TokenBlacklist;
import com.campus.trade.entity.User;
import com.campus.trade.service.UserService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器（v0.14）。
 *
 * <p><b>为什么 Token 放在 URL 参数里？</b>
 * 浏览器原生的 {@code WebSocket} 构造函数<b>不支持自定义请求头</b>，
 * 因此无法像 axios 那样带 {@code Authorization: Bearer xxx}，
 * 业界通行做法是把 Token 放在查询参数里：{@code ws://host/ws/chat?token=xxx}。</p>
 *
 * <p>安全上的补充措施：</p>
 * <ol>
 *   <li>服务端在握手阶段就校验 Token（签名、有效期、黑名单、账号状态），不合法直接拒绝连接（HTTP 401）；</li>
 *   <li>Token 只在建立连接时使用一次，握手完成后即从会话属性里只保留 userId / nickname，不在后续逻辑中传递；</li>
 *   <li>已退出登录（v0.12 黑名单）或被禁用的账号无法建立连接。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    /** 会话属性键：用户ID */
    public static final String ATTR_USER_ID = "userId";
    /** 会话属性键：用户昵称 */
    public static final String ATTR_NICKNAME = "nickname";
    /** 会话属性键：用户名 */
    public static final String ATTR_USERNAME = "username";

    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;
    private final UserService userService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            log.warn("[WS 握手失败] 缺少 token 参数，来自 {}", request.getRemoteAddress());
            // 明确返回 401，前端据此停止重连并跳登录页（否则默认是 200，客户端只能看到"连接被拒"）
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (Exception e) {
            log.warn("[WS 握手失败] Token 无效或已过期：{}", e.getMessage());
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        // v0.12 黑名单：退出登录后的 Token 不能再建立实时连接
        if (tokenBlacklist.contains(token)) {
            log.warn("[WS 握手失败] Token 已退出登录（黑名单）");
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        Long userId = Long.valueOf(claims.getSubject());
        User user = userService.getById(userId);
        if (user == null) {
            log.warn("[WS 握手失败] 用户不存在 userId={}", userId);
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            log.warn("[WS 握手失败] 账号已被禁用 userId={}", userId);
            response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_NICKNAME, user.getNickname());
        attributes.put(ATTR_USERNAME, user.getUsername());
        log.info("[WS 握手成功] userId={} username={}", userId, user.getUsername());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.warn("[WS 握手异常] {}", exception.getMessage());
        }
    }

    /** 从查询参数 token 或 Sec-WebSocket-Protocol 头中取 Token */
    private String resolveToken(ServerHttpRequest request) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        // 兼容部分客户端把 token 放在子协议头里的写法
        String protocol = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
        return protocol == null ? null : protocol.trim();
    }
}
