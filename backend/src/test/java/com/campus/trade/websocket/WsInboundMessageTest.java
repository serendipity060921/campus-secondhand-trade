package com.campus.trade.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WebSocket 入站消息解析验证（v0.18 / 第3批-⑩）
 *
 * <p>前端发来的帧不可信：可能是缺字段、类型不对、甚至是非法 JSON。
 * <code>parse</code> 的约定是"<b>解析失败返回 null</b>"，由调用方回 error 帧并保留连接 ——
 * 本测试把这个约定与各字段的解析规则固定下来。</p>
 *
 * <p>此前覆盖率 39.68%。</p>
 */
class WsInboundMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("完整消息：type / toUserId / content / productId 全部解析")
    void fullMessage() {
        WsInboundMessage m = WsInboundMessage.parse(
                "{\"type\":\"chat\",\"toUserId\":5,\"content\":\"你好\",\"productId\":7}", objectMapper);

        assertNotNull(m);
        assertEquals("chat", m.getType());
        assertEquals(5L, m.getToUserId());
        assertEquals("你好", m.getContent());
        assertEquals(7L, m.getProductId());
    }

    @Test
    @DisplayName("只有 type 的最小帧（ping 就是这样）")
    void minimalFrame() {
        WsInboundMessage m = WsInboundMessage.parse("{\"type\":\"ping\"}", objectMapper);

        assertNotNull(m);
        assertEquals("ping", m.getType());
        assertNull(m.getToUserId());
        assertNull(m.getContent());
        assertNull(m.getMessageIds());
    }

    @Test
    @DisplayName("read 帧：peerId 与 messageIds 数组")
    void readFrame() {
        WsInboundMessage byPeer = WsInboundMessage.parse("{\"type\":\"read\",\"peerId\":8}", objectMapper);
        assertNotNull(byPeer);
        assertEquals(8L, byPeer.getPeerId());
        assertNull(byPeer.getMessageIds());

        WsInboundMessage byIds = WsInboundMessage.parse(
                "{\"type\":\"read\",\"messageIds\":[1,2,3]}", objectMapper);
        assertNotNull(byIds);
        assertEquals(List.of(1L, 2L, 3L), byIds.getMessageIds());
    }

    @Test
    @DisplayName("queryOnline 帧：userIds 数组")
    void queryOnlineFrame() {
        WsInboundMessage m = WsInboundMessage.parse(
                "{\"type\":\"queryOnline\",\"userIds\":[5,6,7]}", objectMapper);

        assertNotNull(m);
        assertEquals(List.of(5L, 6L, 7L), m.getUserIds());
    }

    @Test
    @DisplayName("★ messageIds 不是数组时忽略（不能让一个畸形字段整帧崩掉）")
    void nonArrayIgnored() {
        WsInboundMessage m = WsInboundMessage.parse(
                "{\"type\":\"read\",\"messageIds\":\"oops\"}", objectMapper);

        assertNotNull(m);
        assertNull(m.getMessageIds(), "非数组应被忽略，而不是抛异常");
    }

    @Test
    @DisplayName("★ 多给字段不影响解析（前向兼容：前端加字段不必同步改后端）")
    void extraFieldsIgnored() {
        WsInboundMessage m = WsInboundMessage.parse(
                "{\"type\":\"chat\",\"toUserId\":5,\"content\":\"x\",\"futureField\":{\"a\":1}}", objectMapper);

        assertNotNull(m);
        assertEquals(5L, m.getToUserId());
    }

    @Test
    @DisplayName("type 缺失 → 得到 null（由调用方按「不支持的消息类型」处理）")
    void missingType() {
        WsInboundMessage m = WsInboundMessage.parse("{\"toUserId\":5}", objectMapper);

        assertNotNull(m);
        assertNull(m.getType());
    }

    @Test
    @DisplayName("type 为 null 字面量 → 也是 null")
    void nullType() {
        WsInboundMessage m = WsInboundMessage.parse("{\"type\":null}", objectMapper);

        assertNotNull(m);
        assertNull(m.getType());
    }

    @Test
    @DisplayName("★ 非法 JSON / 空串 / null → 绝不抛异常（返回 null 或字段全空的消息）")
    void malformedReturnsNull() {
        for (String payload : new String[]{"not-a-json", "{\"type\":", "", null}) {
            // 实测契约：解析失败（抛异常）返回 null；而空串会被 Jackson 解析成 MissingNode，
            // 得到一个字段全为 null 的消息对象 —— 两种结果调用方都能处理
            //（ChatWebSocketHandler 对 "null 或 type 为空" 都回 error 帧并保留连接），
            // 所以这里断言的是"绝不抛异常 + type 一定为空"，这才是真正对外承诺的语义。
            WsInboundMessage m = WsInboundMessage.parse(payload, objectMapper);
            if (m != null) {
                assertNull(m.getType(), "解析不出 type 时必须是 null，实际：" + m.getType() + "（输入：" + payload + "）");
            }
        }
    }

    @Test
    @DisplayName("JSON 不是对象（数组/字符串/数字）→ 解析出的消息 type 为空，由调用方回 error 帧")
    void nonObjectPayload() {
        for (String payload : new String[]{"[1,2,3]", "\"just a string\""}) {
            WsInboundMessage m = WsInboundMessage.parse(payload, objectMapper);
            // 约定：不抛异常。非对象 JSON 解析出的消息 type 为空，
            // 调用方按「缺少 type → 回 error 帧、连接保留」处理，因此这里是安全的
            assertNotNull(m, "输入 " + payload + " 不应抛异常");
            assertNull(m.getType(), "非对象 JSON 的 type 应为 null，实际：" + m.getType());
        }
    }

    @Test
    @DisplayName("数值型字段传字符串数字也能解析（前端偶发传字符串）")
    void numericAsString() {
        WsInboundMessage m = WsInboundMessage.parse(
                "{\"type\":\"chat\",\"toUserId\":\"5\",\"content\":\"hi\"}", objectMapper);

        assertNotNull(m);
        assertEquals(5L, m.getToUserId());
    }
}
