package com.campus.trade.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WebSocket 信封验证（v0.18 / 第3批-⑩）
 *
 * <p>信封是长连接上的"协议"：服务端发的每一帧都必须带 <code>type</code>，
 * 前端靠它分发。此前覆盖率只有 25.99%，等于协议本身没有测试保护。</p>
 *
 * <p>本测试把每个工厂方法产出的 <b>type 与 data 字段</b> 固定下来 ——
 * 这些字段名前端是硬编码使用的，改一个就会让界面静默失效。</p>
 */
class WsEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(WsEnvelope envelope) throws Exception {
        return objectMapper.readTree(envelope.toJson(objectMapper));
    }

    @Test
    @DisplayName("pong：心跳应答的类型")
    void pong() throws Exception {
        JsonNode node = json(WsEnvelope.pong());
        assertEquals(WsEnvelope.TYPE_PONG, node.get("type").asText());
        assertTrue(node.has("ts"), "每帧都应带时间戳");
    }

    @Test
    @DisplayName("★ error：带业务码与提示（前端按 code 决定是否重连）")
    void error() throws Exception {
        JsonNode node = json(WsEnvelope.error(400, "消息格式不对"));
        assertEquals(WsEnvelope.TYPE_ERROR, node.get("type").asText());
        assertEquals(400, node.get("code").asInt());
        assertEquals("消息格式不对", node.get("message").asText());
    }

    @Test
    @DisplayName("online：userId + online 两个字段（前端渲染在线小圆点）")
    void online() throws Exception {
        JsonNode node = json(WsEnvelope.online(7L, true));
        assertEquals(WsEnvelope.TYPE_ONLINE, node.get("type").asText());
        assertEquals(7L, node.get("data").get("userId").asLong());
        assertTrue(node.get("data").get("online").asBoolean());
    }

    @Test
    @DisplayName("read：readerId + peerId（对方据此把消息标记为已读）")
    void read() throws Exception {
        JsonNode node = json(WsEnvelope.read(7L, 8L));
        assertEquals(WsEnvelope.TYPE_READ, node.get("type").asText());
        assertEquals(7L, node.get("data").get("readerId").asLong());
        assertEquals(8L, node.get("data").get("peerId").asLong());
    }

    @Test
    @DisplayName("★ welcome：带 userId 与 unreadTotal（连上就刷新未读角标）")
    void welcome() throws Exception {
        JsonNode node = json(WsEnvelope.welcome(9L, 3L));
        assertEquals(WsEnvelope.TYPE_WELCOME, node.get("type").asText());
        assertEquals(9L, node.get("data").get("userId").asLong());
        assertEquals(3L, node.get("data").get("unreadTotal").asLong(),
                "字段名是 unreadTotal —— 前端硬编码使用，改名会让角标静默失效");
    }

    @Test
    @DisplayName("of：自定义类型与任意 data")
    void of() throws Exception {
        JsonNode node = json(WsEnvelope.of("chat", java.util.Map.of("id", 1L)));
        assertEquals("chat", node.get("type").asText());
        assertEquals(1L, node.get("data").get("id").asLong());
    }

    @Test
    @DisplayName("of：data 为 null 时不报错，data 为空节点")
    void ofNullData() throws Exception {
        JsonNode node = json(WsEnvelope.of("ping", null));
        assertEquals("ping", node.get("type").asText());
        assertTrue(node.has("data"));
        assertTrue(node.get("data").isNull());
    }

    @Test
    @DisplayName("★ toJson 输出可被再次解析（前后端共用同一套 JSON 约定）")
    void jsonIsWellFormed() throws Exception {
        String raw = WsEnvelope.welcome(1L, 2L).toJson(objectMapper);
        assertNotNull(raw);
        assertTrue(raw.contains("\"type\""), "实际输出：" + raw);
        // 能原样解析回来，说明没有多包一层或漏转义
        assertEquals(2L, objectMapper.readTree(raw).get("data").get("unreadTotal").asLong());
    }

    @Test
    @DisplayName("类型常量与协议一致（改动即破坏前端分发）")
    void typeConstants() {
        assertEquals("chat", WsEnvelope.TYPE_CHAT);
        assertEquals("read", WsEnvelope.TYPE_READ);
        assertEquals("online", WsEnvelope.TYPE_ONLINE);
        assertEquals("welcome", WsEnvelope.TYPE_WELCOME);
        assertEquals("pong", WsEnvelope.TYPE_PONG);
        assertEquals("error", WsEnvelope.TYPE_ERROR);
        assertEquals("ping", WsEnvelope.TYPE_PING);
        assertEquals("queryOnline", WsEnvelope.TYPE_QUERY_ONLINE);
    }
}
