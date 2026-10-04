package com.campus.trade.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 消息信封（v0.14）。
 *
 * <p>统一的 JSON 协议，客户端与服务端都按这个结构收发：</p>
 *
 * <pre>
 * // 服务端 → 客户端
 * { "type": "chat", "data": { ...MessageVO }, "ts": 1791042411981 }
 * { "type": "read", "data": { "readerId": 5, "peerId": 6 }, "ts": ... }
 * { "type": "online", "data": { "userId": 5, "online": true }, "ts": ... }
 * { "type": "welcome", "data": { "userId": 6, "unreadTotal": 3 }, "ts": ... }
 * { "type": "pong", "data": null, "ts": ... }
 * { "type": "error", "code": 5001, "message": "...", "ts": ... }
 *
 * // 客户端 → 服务端
 * { "type": "chat", "toUserId": 5, "content": "你好", "productId": 8 }
 * { "type": "read", "peerId": 5 }            // 或 { "type":"read", "messageIds":[1,2] }
 * { "type": "ping" }
 * </pre>
 *
 * <p>用"一个信封 + type 字段"而不是为每种消息建一个类，是因为协议很小、
 * 前端解析也简单（{@code switch (msg.type)}），属于该场景下更实用的取舍。</p>
 */
@Data
@NoArgsConstructor
public class WsEnvelope {

    /* ---------------- 消息类型常量 ---------------- */

    /** 服务端 → 客户端：新私信 */
    public static final String TYPE_CHAT = "chat";
    /** 服务端 → 客户端：对方已读回执 */
    public static final String TYPE_READ = "read";
    /** 服务端 → 客户端：在线状态变化 */
    public static final String TYPE_ONLINE = "online";
    /** 服务端 → 客户端：连接建立后的欢迎包（含未读总数） */
    public static final String TYPE_WELCOME = "welcome";
    /** 服务端 → 客户端：心跳响应 */
    public static final String TYPE_PONG = "pong";
    /** 服务端 → 客户端：错误 */
    public static final String TYPE_ERROR = "error";
    /** 客户端 → 服务端：心跳 */
    public static final String TYPE_PING = "ping";
    /** 客户端 → 服务端：批量查询在线状态 */
    public static final String TYPE_QUERY_ONLINE = "queryOnline";

    /** 消息类型 */
    private String type;
    /** 业务数据（结构随 type 变化） */
    private Object data;
    /** 错误码（type=error 时有效，复用全局业务状态码） */
    private Integer code;
    /** 提示信息（type=error 时有效） */
    private String message;
    /** 时间戳（毫秒） */
    private Long ts = System.currentTimeMillis();

    public WsEnvelope(String type, Object data) {
        this.type = type;
        this.data = data;
        this.ts = System.currentTimeMillis();
    }

    public static WsEnvelope of(String type, Object data) {
        return new WsEnvelope(type, data);
    }

    public static WsEnvelope pong() {
        return new WsEnvelope(TYPE_PONG, null);
    }

    public static WsEnvelope error(Integer code, String message) {
        WsEnvelope envelope = new WsEnvelope(TYPE_ERROR, null);
        envelope.setCode(code);
        envelope.setMessage(message);
        return envelope;
    }

    public static WsEnvelope online(Long userId, boolean online) {
        Map<String, Object> data = new HashMap<>(2);
        data.put("userId", userId);
        data.put("online", online);
        return new WsEnvelope(TYPE_ONLINE, data);
    }

    public static WsEnvelope read(Long readerId, Long peerId) {
        Map<String, Object> data = new HashMap<>(2);
        data.put("readerId", readerId);
        data.put("peerId", peerId);
        return new WsEnvelope(TYPE_READ, data);
    }

    public static WsEnvelope welcome(Long userId, long unreadTotal) {
        Map<String, Object> data = new HashMap<>(3);
        data.put("userId", userId);
        data.put("unreadTotal", unreadTotal);
        return new WsEnvelope(TYPE_WELCOME, data);
    }

    /** 序列化为 JSON 字符串（失败时返回一个 error 帧，避免把异常抛到连接线程） */
    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"code\":500,\"message\":\"消息序列化失败\"}";
        }
    }
}
