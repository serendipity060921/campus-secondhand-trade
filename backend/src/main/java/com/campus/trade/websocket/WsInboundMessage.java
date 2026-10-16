package com.campus.trade.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket 入站消息（v0.14，客户端 → 服务端）。
 *
 * <p>字段按需使用：{@code chat} 用 toUserId/content/productId，
 * {@code read} 用 peerId/messageIds，{@code queryOnline} 用 userIds。</p>
 */
@Data
@NoArgsConstructor
public class WsInboundMessage {

    private String type;
    private Long toUserId;
    private String content;
    private Long productId;
    private Long peerId;
    private List<Long> messageIds;
    private List<Long> userIds;

    /**
     * 解析前端发来的帧。
     *
     * <p><b>对外承诺的语义</b>：<b>绝不抛异常</b>。JSON 语法错误时返回 {@code null}；
     * 而空串等会被 Jackson 解析成 MissingNode 的情况，会返回一个<b>字段全为 null</b> 的对象。
     * 调用方只需判断「{@code null} 或 {@code type} 为空」→ 回 error 帧并保留连接
     * （见 {@code ChatWebSocketHandler#handleTextMessage}）。</p>
     */
    public static WsInboundMessage parse(String payload, ObjectMapper objectMapper) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            WsInboundMessage message = new WsInboundMessage();
            message.setType(node.path("type").asText(null));
            if (node.hasNonNull("toUserId")) {
                message.setToUserId(node.get("toUserId").asLong());
            }
            if (node.hasNonNull("content")) {
                message.setContent(node.get("content").asText());
            }
            if (node.hasNonNull("productId")) {
                message.setProductId(node.get("productId").asLong());
            }
            if (node.hasNonNull("peerId")) {
                message.setPeerId(node.get("peerId").asLong());
            }
            if (node.has("messageIds") && node.get("messageIds").isArray()) {
                List<Long> ids = new ArrayList<>();
                node.get("messageIds").forEach(id -> ids.add(id.asLong()));
                message.setMessageIds(ids);
            }
            if (node.has("userIds") && node.get("userIds").isArray()) {
                List<Long> ids = new ArrayList<>();
                node.get("userIds").forEach(id -> ids.add(id.asLong()));
                message.setUserIds(ids);
            }
            return message;
        } catch (Exception e) {
            return null;
        }
    }
}
