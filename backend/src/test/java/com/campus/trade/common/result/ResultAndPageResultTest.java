package com.campus.trade.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 统一响应结构验证（v0.18 / 第3批-⑩）
 *
 * <p><code>Result</code> 与 <code>PageResult</code> 是前端拦截器唯一认得的报文结构，
 * 它们的字段名与语义是**跨端契约**。此前两者覆盖率只有 32% 与 27%。</p>
 *
 * <p>本测试固定：成功/失败工厂产出的 code 与 message、时间戳一定存在、
 * 以及 PageResult 的分页字段映射（前端"上一页/下一页"直接依赖 pages/current/size）。</p>
 */
class ResultAndPageResultTest {

    @Nested
    @DisplayName("一、Result：统一响应")
    class ResultTest {

        @Test
        @DisplayName("success() 默认 200 + 通用提示")
        void successDefault() {
            Result<Void> r = Result.success();

            assertEquals(200, r.getCode());
            assertNotNull(r.getMessage());
            assertNull(r.getData());
            assertNotNull(r.getTimestamp(), "★ 每一条响应都必须带时间戳");
            assertTrue(r.getTimestamp() > 0);
        }

        @Test
        @DisplayName("success(data) 携带数据")
        void successWithData() {
            Result<String> r = Result.success("hello");

            assertEquals(200, r.getCode());
            assertEquals("hello", r.getData());
        }

        @Test
        @DisplayName("success(message, data) 自定义提示（例如「发布成功」）")
        void successWithMessage() {
            Result<Integer> r = Result.success("发布成功", 42);

            assertEquals(200, r.getCode());
            assertEquals("发布成功", r.getMessage());
            assertEquals(42, r.getData());
        }

        @Test
        @DisplayName("error(message) 默认业务码（不是 200！）")
        void errorWithMessage() {
            Result<Void> r = Result.error("参数不对");

            assertEquals("参数不对", r.getMessage());
            assertTrue(r.getCode() != null && r.getCode() != 200, "失败响应不能用 200 业务码");
        }

        @Test
        @DisplayName("error(code, message) 指定业务码（前端按码分支的关键）")
        void errorWithCode() {
            Result<Void> r = Result.error(3001, "商品不存在");

            assertEquals(3001, r.getCode());
            assertEquals("商品不存在", r.getMessage());
            assertNull(r.getData());
        }

        @Test
        @DisplayName("error(ResultCode) 用通用错误码枚举构造")
        void errorWithResultCode() {
            // 注意：ResultCode 本身就是一个枚举（通用码），模块各自还有独立的 XxxResultCode 枚举
            Result<Void> r = Result.error(ResultCode.UNAUTHORIZED);

            assertEquals(ResultCode.UNAUTHORIZED.getCode(), r.getCode());
            assertEquals(ResultCode.UNAUTHORIZED.getMessage(), r.getMessage());
        }

        @Test
        @DisplayName("三参构造器同样带时间戳（内部直接 new 的场景）")
        void explicitConstructor() {
            Result<String> r = new Result<>(200, "ok", "d");

            assertEquals(200, r.getCode());
            assertEquals("ok", r.getMessage());
            assertEquals("d", r.getData());
            assertNotNull(r.getTimestamp());
        }

        @Test
        @DisplayName("data 为 null 时不报错（Result.success(null) 常见于无返回值的写操作）")
        void nullData() {
            Result<Object> r = Result.success("删除成功", null);

            assertEquals(200, r.getCode());
            assertNull(r.getData());
        }
    }

    @Nested
    @DisplayName("二、PageResult：分页结构")
    class PageResultTest {

        @Test
        @DisplayName("五参构造器按 (total, pages, current, size, records) 映射")
        void explicitConstructor() {
            PageResult<String> p = new PageResult<>(25L, 3L, 2L, 12L, List.of("a", "b"));

            assertEquals(25L, p.getTotal());
            assertEquals(3L, p.getPages());
            assertEquals(2L, p.getCurrent());
            assertEquals(12L, p.getSize());
            assertEquals(2, p.getRecords().size());
        }

        @Test
        @DisplayName("★ of(IPage)：从 MyBatis-Plus 分页对象转换（列表接口都走这条）")
        void ofIPage() {
            IPage<String> page = mock(IPage.class);
            when(page.getTotal()).thenReturn(30L);
            when(page.getPages()).thenReturn(3L);
            when(page.getCurrent()).thenReturn(2L);
            when(page.getSize()).thenReturn(10L);
            when(page.getRecords()).thenReturn(List.of("x"));

            PageResult<String> p = PageResult.of(page);

            assertEquals(30L, p.getTotal());
            assertEquals(3L, p.getPages());
            assertEquals(2L, p.getCurrent());
            assertEquals(10L, p.getSize());
            assertEquals(List.of("x"), p.getRecords());
        }

        @Test
        @DisplayName("empty()：空结果也要给出可用的分页字段（前端不能崩）")
        void empty() {
            PageResult<Object> p = PageResult.empty();

            assertEquals(0L, p.getTotal());
            assertEquals(0L, p.getPages());
            assertNotNull(p.getRecords());
            assertTrue(p.getRecords().isEmpty());
            assertEquals(1L, p.getCurrent(), "空结果当前页应为 1，否则前端算不出页码");
        }

        @Test
        @DisplayName("记录为 null 时 of() 不报错（分页对象可能返回 null 集合）")
        void ofNullRecords() {
            IPage<String> page = mock(IPage.class);
            when(page.getTotal()).thenReturn(0L);
            when(page.getPages()).thenReturn(0L);
            when(page.getCurrent()).thenReturn(1L);
            when(page.getSize()).thenReturn(10L);
            when(page.getRecords()).thenReturn(null);

            PageResult<String> p = PageResult.of(page);
            assertNotNull(p.getRecords(), "null 集合应被兜底为空集合");
            assertTrue(p.getRecords().isEmpty());
        }

        @Test
        @DisplayName("无参构造器可用（JSON 反序列化需要）")
        void noArgConstructor() {
            PageResult<String> p = new PageResult<>();
            p.setTotal(1L);
            p.setRecords(List.of("a"));

            assertEquals(1L, p.getTotal());
            assertEquals(1, p.getRecords().size());
        }
    }
}
