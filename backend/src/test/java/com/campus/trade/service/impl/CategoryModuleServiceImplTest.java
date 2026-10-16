package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.trade.common.cache.CacheEvictor;
import com.campus.trade.common.cache.CacheKeys;
import com.campus.trade.common.exception.CategoryException;
import com.campus.trade.config.CacheProperties;
import com.campus.trade.dto.CategoryDTO;
import com.campus.trade.entity.Category;
import com.campus.trade.mapper.ProductMapper;
import com.campus.trade.service.CacheService;
import com.campus.trade.service.CategoryService;
import com.campus.trade.vo.CategoryVO;

/**
 * 分类模块业务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>这个类里有两条值得单独拿出来说的规则：</p>
 * <ol>
 *   <li>★ <b>缓存键必须区分「一级分类」与「全部分类」</b>：键名带 <code>:top</code> / <code>:all</code> 后缀。
 *       这里正是缺陷 BUG-001 的现场 —— 当初用"具体键"删除只能删掉其中一种，
 *       另一种会一直留在缓存里导致前台看不到更新；本测试把两种键都断言到；</li>
 *   <li>★ <b>一级分类的在售件数 = 自身 + 所有后代的件数</b>（首页色带要展示"这一类一共有多少件"），
 *       并且带<b>层级护栏</b>防止脏数据成环；统计失败时件数按 0 返回，不影响分类列表本身。</li>
 * </ol>
 *
 * <p>新增/修改的校验也很密：名称必填（新增时）、上级必须存在、同一上级下名称唯一、
 * 修改时不能把自己设成自己的上级、未传字段等于"不修改"。</p>
 */
class CategoryModuleServiceImplTest {

    private CategoryService categoryService;
    private ProductMapper productMapper;
    private CacheService cacheService;
    private CacheKeys cacheKeys;
    private CacheEvictor cacheEvictor;

    private CategoryModuleServiceImpl service;

    private static final String KEY_BASE = "campus:category:list";

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Category.class);
    }

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        productMapper = mock(ProductMapper.class);
        cacheService = mock(CacheService.class);
        cacheKeys = mock(CacheKeys.class);
        cacheEvictor = mock(CacheEvictor.class);

        when(cacheKeys.categoryList()).thenReturn(KEY_BASE);
        when(productMapper.countOnSaleGroupByCategory()).thenReturn(new ArrayList<>());

        service = new CategoryModuleServiceImpl(categoryService, productMapper, cacheService,
                cacheKeys, cacheEvictor, new CacheProperties());
    }

    private Category category(long id, Long parentId, String name) {
        Category c = new Category();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        c.setStatus(1);
        c.setSortOrder(1);
        c.setDeleted(0);
        return c;
    }

    private CategoryDTO dto(Long id, Long parentId, String name) {
        CategoryDTO d = new CategoryDTO();
        d.setId(id);
        d.setParentId(parentId);
        d.setName(name);
        return d;
    }

    @Nested
    @DisplayName("一、★ 分类列表与缓存键（BUG-001 的现场）")
    class ListAndCache {

        @Test
        @DisplayName("★ 一级分类用 :top 键、全部分类用 :all 键 —— 两种键必须区分")
        void cacheKeysDifferByOnlyTop() {
            when(cacheService.exists(anyString())).thenReturn(false);
            when(categoryService.list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any()))
                    .thenReturn(List.of());

            service.list(true);
            service.list(false);

            // 写入时用的键必须分别是 :top 与 :all（不能是同一个键）
            verify(cacheService).set(eq(KEY_BASE + ":top"), any(), anyLong());
            verify(cacheService).set(eq(KEY_BASE + ":all"), any(), anyLong());
        }

        @Test
        @DisplayName("★ 命中缓存时直接返回，不再查库")
        void cacheHit() {
            List<CategoryVO> cached = List.of(new CategoryVO());
            when(cacheService.exists(KEY_BASE + ":top")).thenReturn(true);
            when(cacheService.get(KEY_BASE + ":top", List.class)).thenReturn(cached);

            List<CategoryVO> result = service.list(true);

            assertEquals(1, result.size());
            verify(categoryService, never()).list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any());
        }

        @Test
        @DisplayName("键存在但取值为空 → 重新查库并回填")
        void keyExistsButNull() {
            when(cacheService.exists(anyString())).thenReturn(true);
            when(cacheService.get(anyString(), eq(List.class))).thenReturn(null);
            when(categoryService.list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any()))
                    .thenReturn(List.of(category(1L, 0L, "教材")));

            List<CategoryVO> result = service.list(true);

            assertEquals(1, result.size());
            verify(cacheService).set(eq(KEY_BASE + ":top"), any(), anyLong());
        }

        @Test
        @DisplayName("空结果也会写缓存（避免每次都穿透到数据库）")
        void emptyResultCached() {
            when(cacheService.exists(anyString())).thenReturn(false);
            when(categoryService.list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any()))
                    .thenReturn(List.of());

            assertTrue(service.list(false).isEmpty());
            verify(cacheService).set(eq(KEY_BASE + ":all"), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("二、★ 在售件数：一级分类要汇总后代")
    class ProductCounts {

        /** 造一行 countOnSaleGroupByCategory 的结果（键名可能是驼峰或下划线，实现两种都兼容） */
        private Map<String, Object> countRow(long categoryId, long cnt, boolean snakeCase) {
            Map<String, Object> row = new HashMap<>();
            row.put(snakeCase ? "category_id" : "categoryId", categoryId);
            row.put(snakeCase ? "CNT" : "cnt", cnt);
            return row;
        }

        @Test
        @DisplayName("★ 一级分类件数 = 自身 + 所有后代（首页色带展示用）")
        void rollsUpDescendants() {
            // 分类结构：1(一级) ├─ 2 ├─ 3 ；4 是另一个一级
            when(categoryService.list()).thenReturn(List.of(
                    category(1L, 0L, "图书"), category(2L, 1L, "教材"),
                    category(3L, 2L, "计算机"), category(4L, 0L, "数码")));
            when(productMapper.countOnSaleGroupByCategory()).thenReturn(List.of(
                    countRow(2L, 5, false), countRow(3L, 7, false), countRow(4L, 2, false)));
            when(cacheService.exists(anyString())).thenReturn(false);
            when(categoryService.list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any()))
                    .thenReturn(List.of(category(1L, 0L, "图书"), category(4L, 0L, "数码")));

            List<CategoryVO> result = service.list(true);

            Map<Long, Long> byId = new HashMap<>();
            result.forEach(v -> byId.put(v.getId(), v.getProductCount()));
            assertEquals(12L, byId.get(1L), "一级分类应汇总 5 + 7");
            assertEquals(2L, byId.get(4L), "无后代的一级分类只算自己");
        }

        @Test
        @DisplayName("★ 兼容下划线键名（category_id / CNT）")
        void snakeCaseKeysSupported() {
            when(categoryService.list()).thenReturn(List.of(category(1L, 0L, "图书")));
            when(productMapper.countOnSaleGroupByCategory())
                    .thenReturn(List.of(countRow(1L, 9, true)));
            when(cacheService.exists(anyString())).thenReturn(false);
            when(categoryService.list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any()))
                    .thenReturn(List.of(category(1L, 0L, "图书")));

            assertEquals(9L, service.list(false).get(0).getProductCount());
        }

        @Test
        @DisplayName("★ 统计失败 → 件数按 0 返回，分类列表本身仍可用（降级不影响主功能）")
        void statFailureDegradesToZero() {
            when(categoryService.list()).thenThrow(new RuntimeException("统计表挂了"));
            when(cacheService.exists(anyString())).thenReturn(false);
            when(categoryService.list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any()))
                    .thenReturn(List.of(category(1L, 0L, "图书")));

            List<CategoryVO> result = service.list(false);

            assertEquals(1, result.size(), "分类列表仍应返回");
            assertEquals(0L, result.get(0).getProductCount(), "件数退化为 0");
        }

        @Test
        @DisplayName("★ 脏数据成环不会死循环（层级护栏 8 层）")
        void cycleGuard() {
            // 1 → 2 → 1 互为父子（脏数据）
            when(categoryService.list()).thenReturn(List.of(
                    category(1L, 2L, "A"), category(2L, 1L, "B")));
            when(productMapper.countOnSaleGroupByCategory()).thenReturn(List.of(countRow(1L, 1, false)));
            when(cacheService.exists(anyString())).thenReturn(false);
            when(categoryService.list(org.mockito.ArgumentMatchers.<Wrapper<Category>>any()))
                    .thenReturn(List.of(category(1L, 2L, "A")));

            assertNotNull(service.list(false), "成环时必须能正常返回而不是卡死");
        }
    }

    @Nested
    @DisplayName("三、新增分类")
    class Add {

        @Test
        @DisplayName("★ 名称必填（@Size 只校验长度，非空判断在服务层）")
        void nameRequired() {
            assertThrows(CategoryException.class, () -> service.add(dto(null, 0L, "   ")));
        }

        @Test
        @DisplayName("parentId 不传 → 视为一级分类（0）")
        void defaultRootParent() {
            when(categoryService.count(org.mockito.ArgumentMatchers.<Wrapper<Category>>any())).thenReturn(0L);
            when(categoryService.save(any())).thenAnswer(inv -> {
                ((Category) inv.getArgument(0)).setId(9L);
                return true;
            });
            when(categoryService.getById(9L)).thenReturn(category(9L, 0L, "新分类"));

            CategoryVO vo = service.add(dto(null, null, "新分类"));

            assertNotNull(vo);
            verify(cacheEvictor).categoryChanged();
        }

        @Test
        @DisplayName("★ 上级分类不存在 → 报错（不能挂在空气上）")
        void parentMustExist() {
            when(categoryService.getById(77L)).thenReturn(null);

            assertThrows(CategoryException.class, () -> service.add(dto(null, 77L, "子分类")));
            verify(categoryService, never()).save(any());
        }

        @Test
        @DisplayName("★ 同一上级下名称重复 → 报错")
        void nameMustBeUnique() {
            when(categoryService.getById(1L)).thenReturn(category(1L, 0L, "图书"));
            when(categoryService.count(org.mockito.ArgumentMatchers.<Wrapper<Category>>any())).thenReturn(1L);

            assertThrows(CategoryException.class, () -> service.add(dto(null, 1L, "教材")));
            verify(categoryService, never()).save(any());
        }

        @Test
        @DisplayName("名称两端空格会被去掉")
        void nameTrimmed() {
            when(categoryService.count(org.mockito.ArgumentMatchers.<Wrapper<Category>>any())).thenReturn(0L);
            when(categoryService.save(any())).thenAnswer(inv -> {
                Category c = inv.getArgument(0);
                assertEquals("教材", c.getName(), "保存的应是去掉空格后的名称");
                c.setId(9L);
                return true;
            });
            when(categoryService.getById(9L)).thenReturn(category(9L, 0L, "教材"));

            service.add(dto(null, null, "  教材  "));
        }

        @Test
        @DisplayName("★ 新增成功后必须让分类缓存失效（否则前台看不到新分类）")
        void evictsCacheOnAdd() {
            when(categoryService.count(org.mockito.ArgumentMatchers.<Wrapper<Category>>any())).thenReturn(0L);
            when(categoryService.save(any())).thenAnswer(inv -> {
                ((Category) inv.getArgument(0)).setId(9L);
                return true;
            });
            when(categoryService.getById(9L)).thenReturn(category(9L, 0L, "新分类"));

            service.add(dto(null, null, "新分类"));

            verify(cacheEvictor).categoryChanged();
        }
    }

    @Nested
    @DisplayName("四、修改分类")
    class Update {

        @Test
        @DisplayName("必须传 id")
        void idRequired() {
            assertThrows(CategoryException.class, () -> service.update(dto(null, 0L, "x")));
        }

        @Test
        @DisplayName("★ 分类不存在 → 报错")
        void mustExist() {
            when(categoryService.getById(9L)).thenReturn(null);

            assertThrows(CategoryException.class, () -> service.update(dto(9L, 0L, "x")));
            verify(categoryService, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 不能把自己设成自己的上级")
        void cannotBeOwnParent() {
            when(categoryService.getById(9L)).thenReturn(category(9L, 0L, "图书"));

            assertThrows(CategoryException.class, () -> service.update(dto(9L, 9L, "图书")));
            verify(categoryService, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 未传名称 → 保持原名称（未传字段等于不修改）")
        void nullNameKeepsExisting() {
            when(categoryService.getById(9L)).thenReturn(category(9L, 0L, "原名"));
            when(categoryService.count(org.mockito.ArgumentMatchers.<Wrapper<Category>>any())).thenReturn(0L);

            service.update(dto(9L, null, null));

            verify(categoryService).updateById(argThat(c -> "原名".equals(c.getName())));
        }

        @Test
        @DisplayName("★ 修改成功后缓存失效")
        void evictsCacheOnUpdate() {
            when(categoryService.getById(9L)).thenReturn(category(9L, 0L, "原名"));
            when(categoryService.count(org.mockito.ArgumentMatchers.<Wrapper<Category>>any())).thenReturn(0L);

            service.update(dto(9L, null, "新名"));

            verify(cacheEvictor).categoryChanged();
        }

        @Test
        @DisplayName("★ 重名校验要排除自己（改成同名不应报重复）")
        void uniqueExcludesSelf() {
            when(categoryService.getById(9L)).thenReturn(category(9L, 0L, "图书"));
            // 排除自己后 count=0，应当放行
            when(categoryService.count(org.mockito.ArgumentMatchers.<Wrapper<Category>>any())).thenReturn(0L);

            assertNotNull(service.update(dto(9L, 0L, "图书")));
            verify(categoryService).updateById(any());
        }
    }

    /** 断言辅助：取出传给 updateById 的实体 */
    private static Category argThat(java.util.function.Predicate<Category> p) {
        return org.mockito.ArgumentMatchers.argThat(c -> c != null && p.test(c));
    }
}
