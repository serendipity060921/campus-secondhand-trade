package com.campus.trade.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分页结果 PageResult 验证（v0.17）
 *
 * <p>分页字段直接决定前端分页器的显示（共几页、当前第几页、每页几条），
 * 尤其是"空态"的取值容易被后续改动破坏，因此这里把两类约定锁定：</p>
 * <ol>
 *   <li>由 MyBatis-Plus 分页对象转换时，五个字段必须一一对应、不做额外加工；</li>
 *   <li>空分页的形状固定（total=0、pages=0、current=1、size=10、records 为空列表），
 *       前端据此渲染空态而不需要额外判断。</li>
 * </ol>
 */
class PageResultTest {

    @Nested
    @DisplayName("一、由分页对象转换")
    class FromPage {

        @Test
        @DisplayName("total / pages / current / size / records 一一对应")
        void mapsAllFields() {
            Page<String> page = new Page<>(2, 10);
            page.setTotal(25);
            page.setRecords(List.of("r1", "r2"));

            PageResult<String> r = PageResult.of(page);

            assertEquals(25L, r.getTotal());
            assertEquals(3L, r.getPages(), "25 条按每页 10 条应为 3 页（向上取整）");
            assertEquals(2L, r.getCurrent());
            assertEquals(10L, r.getSize());
            assertEquals(List.of("r1", "r2"), r.getRecords());
        }

        @Test
        @DisplayName("空结果集：total=0 时页数为 0，records 为空列表（不是 null）")
        void emptyRecords() {
            Page<String> page = new Page<>(1, 10);
            page.setTotal(0);
            page.setRecords(List.of());

            PageResult<String> r = PageResult.of(page);

            assertEquals(0L, r.getTotal());
            assertEquals(0L, r.getPages());
            assertNotNull(r.getRecords(), "records 应为空列表而不是 null（前端据此渲染空态）");
            assertTrue(r.getRecords().isEmpty());
        }

        @Test
        @DisplayName("总数为每页条数的整数倍时页数精确")
        void exactMultiple() {
            Page<String> page = new Page<>(1, 10);
            page.setTotal(30);
            assertEquals(3L, PageResult.of(page).getPages());
        }
    }

    @Nested
    @DisplayName("二、空分页约定")
    class EmptyShape {

        @Test
        @DisplayName("empty()：total=0、pages=0、current=1、size=10、records 为空")
        void emptyShapeFrozen() {
            PageResult<Object> r = PageResult.empty();
            assertEquals(0L, r.getTotal());
            assertEquals(0L, r.getPages());
            assertEquals(1L, r.getCurrent(), "空态也要给出第 1 页，前端分页器才不会显示出错");
            assertEquals(10L, r.getSize());
            assertNotNull(r.getRecords());
            assertTrue(r.getRecords().isEmpty());
        }

        @Test
        @DisplayName("empty() 的列表不可变（避免被调用方误改共享实例）")
        void emptyRecordsImmutable() {
            PageResult<Object> r = PageResult.empty();
            boolean threw = false;
            try {
                r.getRecords().add("x");
            } catch (UnsupportedOperationException e) {
                threw = true;
            }
            assertTrue(threw, "空分页的 records 应是不可变空列表");
        }

        @Test
        @DisplayName("可序列化（分页结果常被缓存或跨进程传递）")
        void serializable() {
            assertInstanceOf(Serializable.class, PageResult.empty());
        }
    }

    @Nested
    @DisplayName("三、普通构造与 getter/setter 可用")
    class PlainConstruction {

        @Test
        @DisplayName("全参构造与无参构造均可用（无参构造供反序列化使用）")
        void constructors() {
            PageResult<String> all = new PageResult<>(5L, 1L, 1L, 10L, List.of("a"));
            assertEquals(5L, all.getTotal());
            assertEquals(List.of("a"), all.getRecords());

            PageResult<String> plain = new PageResult<>();
            plain.setTotal(7L);
            plain.setCurrent(2L);
            plain.setRecords(List.of("b"));
            assertEquals(7L, plain.getTotal());
            assertEquals(2L, plain.getCurrent());
            assertEquals(List.of("b"), plain.getRecords());
        }
    }
}
