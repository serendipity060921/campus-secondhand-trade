package com.campus.trade.config;

import com.campus.trade.websocket.ChatHandshakeInterceptor;
import com.campus.trade.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * WebSocket 配置（v0.14）。
 *
 * <p>使用 Spring 原生的 {@code WebSocketHandler} 而不是 STOMP：
 * 本项目只有"私信 + 已读回执 + 在线状态"三种消息，自定义 JSON 协议（type 字段区分）
 * 已经足够，且不需要前端引入 stompjs/sockjs 依赖，代码更少、更容易讲清楚。</p>
 *
 * <table border="1">
 *   <caption>连接信息</caption>
 *   <tr><th>项</th><th>值</th></tr>
 *   <tr><td>地址</td><td>{@code ws://localhost:8080/ws/chat?token=<JWT>}</td></tr>
 *   <tr><td>鉴权</td><td>握手阶段校验 token（签名/有效期/黑名单/账号状态），不合法直接拒绝</td></tr>
 *   <tr><td>跨域</td><td>允许 dev 配置里的前端来源（默认 5173）</td></tr>
 * </table>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ChatHandshakeInterceptor chatHandshakeInterceptor;

    /** 允许握手的前端来源，与 app.cors.allowed-origins 保持一致 */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .addInterceptors(chatHandshakeInterceptor)
                .setAllowedOrigins(origins);
    }
}
