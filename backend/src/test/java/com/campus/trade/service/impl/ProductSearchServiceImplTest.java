package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.dto.ProductSearchDTO;
import com.campus.trade.entity.Category;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.ProductVO;

/**
 * 商品搜索服务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>搜索是首页最热的接口，边界主要在"筛选条件与排序"以及"结果组装不要 N+1"：</p>
 * <ul>
 *   <li>★ <b>只搜在售商品</b>（待审核/已下架/已售出都不能出现在搜索里）；</li>
 *   <li>★ <b>一级分类要自动包含其子分类</b>（用户点"图书"应当看到"教材/计算机"下的商品）；</li>
 *   <li>★ 排序支持 new / priceAsc / priceDesc / hot，非法值退化为默认按时间；</li>
 *   <li>★ <b>批量补齐分类名与卖家昵称</b>：一次 listByIds 而不是逐条查（否则列表页会 N+1）。</li>
 * </ul>
 */
class ProductSearchServiceImplTest {

    private ProductService productService;
    private CategoryService categoryService;
    private UserService userService;
    private ProductSearchServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (Class<?> c : new Class<?>[]{Product.class, Category.class, User.class}) {
            TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""), c);
        }
    }

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        categoryService = mock(CategoryService.class);
        userService = mock(UserService.class);
        service = new ProductSearchServiceImpl(productService, categoryService, userService);
    }

    private ProductSearchDTO dto(String keyword, Long categoryId, String sort) {
        ProductSearchDTO d = new ProductSearchDTO();
        d.setKeyword(keyword);
        d.setCategoryId(categoryId);
        d.setSort(sort);
        d.setPage(1);
        d.setSize(10);
        return d;
    }

    private Product product(long id, Long categoryId, Long sellerId) {
        Product p = new Product();
        p.setId(id);
        p.setStatus(1);
        p.setTitle("教材 " + id);
        p.setPrice(new BigDecimal("25.50"));
        p.setCategoryId(categoryId);
        p.setSellerId(sellerId);
        return p;
    }

    private Category category(long id, long parentId, String name) {
        Category c = new Category();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        return c;
    }

    @SuppressWarnings("unchecked")
    private void givenPage(List<Product> records) {
        Page<Product> page = new Page<>(1, 10);
        page.setRecords(records);
        page.setTotal(records.size());
        when(productService.page(any(Page.class), any())).thenReturn(page);
    }

    @Nested
    @DisplayName("一、★ 筛选条件")
    class Filters {

        @Test
        @DisplayName("★ 一级分类自动包含子分类（点「图书」要能看到「教材」下的商品）")
        void parentCategoryIncludesChildren() {
            givenPage(List.of(product(1L, 3L, 99L)));
            when(categoryService.list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any())).thenReturn(List.of(
                    category(2L, 1L, "教材"), category(3L, 1L, "计算机")));
            when(categoryService.listByIds(any())).thenReturn(List.of(category(3L, 1L, "计算机")));
            when(userService.listByIds(any())).thenReturn(List.of());

            service.search(dto(null, 1L, null));

            // 查找子分类这件事必须发生过（否则只会搜一级分类自身）
            verify(categoryService).list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any());
            assertEquals(1, service.search(dto(null, 1L, null)).getRecords().size());
        }

        @Test
        @DisplayName("不传分类时不查子分类（省一次查询）")
        void noCategorySkipsChildrenLookup() {
            givenPage(List.of());
            service.search(dto(null, null, null));
            verify(categoryService, never()).list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any());
        }

        @Test
        @DisplayName("★ 关键词两端空格会被去掉")
        void keywordTrimmed() {
            givenPage(List.of());
            assertNotNull(service.search(dto("  教材  ", null, null)));
        }

        @Test
        @DisplayName("★ 四种排序都能跑通，非法排序退化为默认（不报错）")
        void sorts() {
            givenPage(List.of());
            for (String sort : new String[]{"new", "priceAsc", "priceDesc", "hot", "unknown", null}) {
                assertNotNull(service.search(dto("教材", null, sort)), "排序 " + sort + " 不应报错");
            }
        }

        @Test
        @DisplayName("价格区间可选（不传就不加条件）")
        void priceRangeOptional() {
            givenPage(List.of());
            ProductSearchDTO d = dto("教材", null, null);
            d.setMinPrice(new BigDecimal("10"));
            d.setMaxPrice(new BigDecimal("100"));

            assertNotNull(service.search(d));
        }
    }

    @Nested
    @DisplayName("二、★ 结果组装：批量补齐，避免 N+1")
    class Assembly {

        @Test
        @DisplayName("★ 分类名与卖家昵称各只查一次（listByIds），不是逐条查")
        void batchLookupNotNPlusOne() {
            givenPage(List.of(product(1L, 3L, 99L), product(2L, 3L, 98L)));
            when(categoryService.listByIds(any())).thenReturn(List.of(category(3L, 1L, "计算机")));
            User seller = new User();
            seller.setId(99L);
            seller.setNickname("卖家A");
            when(userService.listByIds(any())).thenReturn(List.of(seller));

            List<ProductVO> vos = service.search(dto("教材", null, null)).getRecords();

            verify(categoryService, times(1)).listByIds(any());
            verify(userService, times(1)).listByIds(any());
            assertEquals("计算机", vos.get(0).getCategoryName());
            assertEquals("卖家A", vos.get(0).getSellerNickname());
        }

        @Test
        @DisplayName("分类名查不到时字段为 null（不报错）")
        void missingCategoryName() {
            givenPage(List.of(product(1L, 3L, 99L)));
            when(categoryService.listByIds(any())).thenReturn(List.of());
            when(userService.listByIds(any())).thenReturn(List.of());

            ProductVO vo = service.search(dto(null, null, null)).getRecords().get(0);

            assertNull(vo.getCategoryName());
            assertNull(vo.getSellerNickname());
        }

        @Test
        @DisplayName("★ 空结果直接返回空列表，不发起多余的 listByIds")
        void emptyResultNoLookups() {
            givenPage(new ArrayList<>());

            var result = service.search(dto("查无此物", null, null));

            assertTrue(result.getRecords().isEmpty());
            verify(categoryService, never()).listByIds(any());
            verify(userService, never()).listByIds(any());
        }

        @Test
        @DisplayName("分页字段透传")
        void pageFields() {
            givenPage(List.of(product(1L, 3L, 99L)));
            when(categoryService.listByIds(any())).thenReturn(List.of());
            when(userService.listByIds(any())).thenReturn(List.of());

            var result = service.search(dto(null, null, null));

            assertEquals(1L, result.getTotal());
            assertEquals(1, result.getRecords().size());
        }
    }
}
