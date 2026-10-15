package com.campus.trade.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 统一响应结果 Result 验证（v0.17）
 *
 * <p>{@link Result} 是全站接口的对外契约：前端 axios 拦截器只认 {@code code}，
 * 端到端与接口测试也都按它断言。因此这里把三条约定固定下来：</p>
 * <ol>
 *   <li>成功态 code 恒为 200、message 恒为 success；</li>
 *   <li>失败态一律不携带 data（避免前端误用半截数据）；</li>
 *   <li>每个实例都带服务端时间戳，且可序列化（跨进程/缓存场景需要）。</li>
 * </ol>
 */
class ResultTest {

    @Nested
    @DisplayName("一、成功响应")
    class Success {

        @Test
        @DisplayName("success()：code=200、message=success、data 为空")
        void plainSuccess() {
            Result<Void> r = Result.success();
            assertEquals(ResultCode.SUCCESS.getCode(), r.getCode());
            assertEquals(200, r.getCode());
            assertEquals(ResultCode.SUCCESS.getMessage(), r.getMessage());
            assertEquals("success", r.getMessage());
            assertNull(r.getData());
        }

        @Test
        @DisplayName("success(data)：数据原样带回")
        void successWithData() {
            List<String> payload = List.of("a", "b");
            Result<List<String>> r = Result.success(payload);
            assertEquals(200, r.getCode());
            assertEquals(payload, r.getData());
        }

        @Test
        @DisplayName("success(message, data)：允许自定义提示（如「发布成功！」）")
        void successWithCustomMessage() {
            Result<Integer> r = Result.success("发布成功", 42);
            assertEquals(200, r.getCode());
            assertEquals("发布成功", r.getMessage());
            assertEquals(42, r.getData());
        }
    }

    @Nested
    @DisplayName("二、失败响应（不带 data）")
    class Error {

        @Test
        @DisplayName("error(message)：使用通用错误码 500")
        void errorWithMessage() {
            Result<Void> r = Result.error("服务器内部错误");
            assertEquals(ResultCode.ERROR.getCode(), r.getCode());
            assertEquals(500, r.getCode());
            assertEquals("服务器内部错误", r.getMessage());
            assertNull(r.getData());
        }

        @Test
        @DisplayName("error(code, message)：业务码原样返回")
        void errorWithCode() {
            Result<Void> r = Result.error(3001, "商品不存在或已被删除");
            assertEquals(3001, r.getCode());
            assertEquals("商品不存在或已被删除", r.getMessage());
            assertNull(r.getData());
        }

        @Test
        @DisplayName("error(ResultCode)：code 与 message 都取自枚举")
        void errorFromResultCode() {
            Result<Void> r = Result.error(ResultCode.NOT_FOUND);
            assertEquals(ResultCode.NOT_FOUND.getCode(), r.getCode());
            assertEquals(ResultCode.NOT_FOUND.getMessage(), r.getMessage());
            assertNull(r.getData());
        }

        @Test
        @DisplayName("业务错误码（如内容机审 3010）也能透传")
        void businessCodePassThrough() {
            Result<Void> r = Result.error(ProductResultCode.CONTENT_REJECTED.getCode(),
                    ProductResultCode.CONTENT_REJECTED.getMessage());
            assertEquals(3010, r.getCode());
            assertTrue(r.getMessage().contains("内容不符合平台规范"));
            assertNull(r.getData());
        }
    }

    @Nested
    @DisplayName("三、时间戳与序列化")
    class Meta {

        @Test
        @DisplayName("每个实例都带服务端时间戳，且落在合理区间")
        void timestampPresent() {
            long before = System.currentTimeMillis();
            Result<Void> r = Result.success();
            long after = System.currentTimeMillis();
            assertNotNull(r.getTimestamp(), "时间戳不应为空（前端用它排查时序问题）");
            assertTrue(r.getTimestamp() >= before && r.getTimestamp() <= after,
                    "时间戳应取自构造时刻：" + r.getTimestamp());
        }

        @Test
        @DisplayName("无参构造同样会写入时间戳")
        void noArgConstructorSetsTimestamp() {
            Result<Void> r = new Result<>();
            assertNotNull(r.getTimestamp());
        }

        @Test
        @DisplayName("可序列化（需要跨进程或落缓存时依赖它）")
        void serializable() {
            assertInstanceOf(Serializable.class, Result.success());
        }
    }
}
