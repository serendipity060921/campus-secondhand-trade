package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.trade.common.cache.CacheEvictor;
import com.campus.trade.common.exception.FavoriteException;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.entity.Favorite;
import com.campus.trade.entity.Product;
import com.campus.trade.mapper.FavoriteModuleMapper;
import com.campus.trade.service.FavoriteService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserBehaviorService;
import com.campus.trade.vo.FavoriteStatusVO;
import com.campus.trade.vo.FavoriteVO;

/**
 * 收藏模块业务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>这个类里挤了七条业务规则，其中三条是"数据一致性"级别的：</p>
 * <ol>
 *   <li>商品必须存在（3001）、不能收藏自己的（4002）、不能重复收藏（4001）、取消必须已收藏（4003）；</li>
 *   <li>★ <b>并发重复收藏由数据库唯一索引兜底</b>，且要把 <code>DuplicateKeyException</code>
 *       转成业务码 4001 —— 否则用户会看到通用的"数据已存在"，前端也没法做差异化提示；</li>
 *   <li>★ <b>收藏数用"查真实条数再回写"，不做 ±1 累加</b> —— 累加在并发或异常下一定会漂移；</li>
 *   <li>★ 每次操作都要触发缓存失效（收藏数会出现在详情/列表/推荐里）。</li>
 * </ol>
 */
class FavoriteModuleServiceImplTest {

    private FavoriteService favoriteService;
    private FavoriteModuleMapper favoriteModuleMapper;
    private ProductService productService;
    private UserBehaviorService userBehaviorService;
    private CacheEvictor cacheEvictor;

    private FavoriteModuleServiceImpl service;

    private static final Long UID = 11L;
    private static final Long PID = 5L;

    /**
     * ★ 纯单测环境下必须先初始化 TableInfo（缺陷跟踪表 BUG-009 记录过）：
     * 实现里用 LambdaQueryWrapper 的 `Favorite::getUserId` 这类方法引用，
     * MyBatis-Plus 需要靠 TableInfo 把 getter 解析成列名，而该元数据原本由 MyBatis **启动时**注册。
     * 不初始化的话，构造条件时会直接抛异常（本轮实测 6 个用例报 ERROR）。
     */
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""),
                com.campus.trade.entity.Favorite.class);
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""),
                com.campus.trade.entity.Product.class);
    }

    @BeforeEach
    void setUp() {
        favoriteService = mock(FavoriteService.class);
        favoriteModuleMapper = mock(FavoriteModuleMapper.class);
        productService = mock(ProductService.class);
        userBehaviorService = mock(UserBehaviorService.class);
        cacheEvictor = mock(CacheEvictor.class);

        service = new FavoriteModuleServiceImpl(favoriteService, favoriteModuleMapper,
                productService, userBehaviorService, cacheEvictor);
    }

    private Product productOf(Long sellerId) {
        Product p = new Product();
        p.setId(PID);
        p.setSellerId(sellerId);
        p.setTitle("九成新教材");
        return p;
    }

    /** ★ MyBatis-Plus 的 count 有重载，必须显式指定 Wrapper 类型，否则 any() 会歧义 */
    private void givenFavoriteCount(long count) {
        when(favoriteService.count(org.mockito.ArgumentMatchers.<Wrapper<Favorite>>any())).thenReturn(count);
    }

    @Nested
    @DisplayName("一、参数与前置校验")
    class Validation {

        @Test
        @DisplayName("★ type 只能是 1 或 2，其他值明确报错")
        void typeMustBeOneOrTwo() {
            FavoriteException e = assertThrows(FavoriteException.class,
                    () -> service.operate(PID, 9, UID));
            assertEquals(FavoriteException.typeIllegal("x").getCode(), e.getCode());
        }

        @Test
        @DisplayName("type 为 null 时按「收藏」处理（前端不传即默认收藏）")
        void nullTypeMeansFavorite() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            givenFavoriteCount(0);

            FavoriteStatusVO vo = service.operate(PID, null, UID);

            assertTrue(vo.getFavorited(), "null 应等价于收藏");
        }

        @Test
        @DisplayName("★ 商品不存在 → 3001（不写任何收藏记录）")
        void productMustExist() {
            when(productService.getById(PID)).thenReturn(null);

            ProductException e = assertThrows(ProductException.class,
                    () -> service.operate(PID, 1, UID));
            assertEquals(ProductException.notFound().getCode(), e.getCode());
            verify(favoriteService, never()).save(any());
        }
    }

    @Nested
    @DisplayName("二、收藏")
    class FavoriteOps {
        // ★ 注意：这个嵌套类**不能**叫 Favorite —— 那会遮蔽实体类 com.campus.trade.entity.Favorite，
        //   导致 `Wrapper<Favorite>` 被解析成 `Wrapper<FavoriteModuleServiceImplTest.Favorite>`，
        //   编译报"无法转换"而看不出原因（本轮实测踩到）。

        @Test
        @DisplayName("正常收藏：落库 + 行为埋点 + 缓存失效 + 返回状态")
        void favoriteSuccess() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            when(favoriteService.count(org.mockito.ArgumentMatchers.<Wrapper<Favorite>>any())).thenReturn(0L, 1L);

            FavoriteStatusVO vo = service.operate(PID, 1, UID);

            assertTrue(vo.getFavorited());
            assertEquals(PID, vo.getProductId());
            assertEquals(1, vo.getFavoriteCount());
            verify(favoriteService).save(any(Favorite.class));
            verify(userBehaviorService).record(UID, PID, UserBehaviorService.TYPE_FAVORITE);
            verify(cacheEvictor).productChanged(PID);
        }

        @Test
        @DisplayName("★ 不能收藏自己发布的商品（4002），且不落库")
        void cannotFavoriteOwn() {
            when(productService.getById(PID)).thenReturn(productOf(UID));   // 卖家就是自己
            givenFavoriteCount(0);

            FavoriteException e = assertThrows(FavoriteException.class,
                    () -> service.operate(PID, 1, UID));
            assertEquals(FavoriteException.cannotFavoriteOwn().getCode(), e.getCode());
            verify(favoriteService, never()).save(any());
        }

        @Test
        @DisplayName("★ 重复收藏 → 4001，且不落库")
        void cannotFavoriteTwice() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            givenFavoriteCount(1);      // 已经收藏过

            FavoriteException e = assertThrows(FavoriteException.class,
                    () -> service.operate(PID, 1, UID));
            assertEquals(FavoriteException.alreadyFavorited().getCode(), e.getCode());
            verify(favoriteService, never()).save(any());
        }

        @Test
        @DisplayName("★ 并发重复收藏：唯一索引抛 DuplicateKeyException → 转成业务码 4001")
        void duplicateKeyConvertedToBusinessCode() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            givenFavoriteCount(0);      // 查重时还没收藏（并发窗口）
            doThrow(new DuplicateKeyException("uk_user_product"))
                    .when(favoriteService).save(any(Favorite.class));

            FavoriteException e = assertThrows(FavoriteException.class,
                    () -> service.operate(PID, 1, UID));
            assertEquals(FavoriteException.alreadyFavorited().getCode(), e.getCode(),
                    "★ 不能把通用的 1002 抛给用户，必须是 4001");
        }

        @Test
        @DisplayName("★ 行为埋点失败不能影响收藏主流程（旁路逻辑要吞异常）")
        void behaviorFailureDoesNotBreakFavorite() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            // 第一次查重 0（未收藏，放行）→ 同步计数时查得 1
            when(favoriteService.count(org.mockito.ArgumentMatchers.<Wrapper<Favorite>>any()))
                    .thenReturn(0L, 1L);
            doThrow(new RuntimeException("埋点表挂了"))
                    .when(userBehaviorService).record(anyLong(), anyLong(), any(Integer.class));

            FavoriteStatusVO vo = service.operate(PID, 1, UID);

            assertTrue(vo.getFavorited(), "埋点失败时收藏仍应成功");
            verify(favoriteService).save(any(Favorite.class));
        }
    }

    @Nested
    @DisplayName("三、取消收藏")
    class Cancel {

        @Test
        @DisplayName("正常取消：物理删除 + 缓存失效 + favorited=false")
        void cancelSuccess() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            givenFavoriteCount(0);      // 删除后同步计数为 0
            // ① 查是否已收藏 → 1；② syncFavoriteCount → 0
            when(favoriteService.count(org.mockito.ArgumentMatchers.<Wrapper<Favorite>>any())).thenReturn(1L, 0L);

            FavoriteStatusVO vo = service.operate(PID, 2, UID);

            assertFalse(vo.getFavorited());
            assertEquals(0, vo.getFavoriteCount());
            verify(favoriteModuleMapper).physicalDelete(UID, PID);
            verify(cacheEvictor).productChanged(PID);
            verify(favoriteService, never()).save(any());
        }

        @Test
        @DisplayName("★ 取消未收藏的商品 → 4003（不能静默成功）")
        void cannotCancelNotFavorited() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            givenFavoriteCount(0);

            FavoriteException e = assertThrows(FavoriteException.class,
                    () -> service.operate(PID, 2, UID));
            assertEquals(FavoriteException.notFavorited().getCode(), e.getCode());
            verify(favoriteModuleMapper, never()).physicalDelete(anyLong(), anyLong());
        }

        @Test
        @DisplayName("取消收藏不做行为埋点（只有收藏是兴趣信号）")
        void cancelDoesNotRecordBehavior() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            when(favoriteService.count(org.mockito.ArgumentMatchers.<Wrapper<Favorite>>any())).thenReturn(1L, 0L);

            service.operate(PID, 2, UID);

            verify(userBehaviorService, never()).record(anyLong(), anyLong(), any(Integer.class));
        }
    }

    @Nested
    @DisplayName("四、★ 计数与查询")
    class Counting {

        @Test
        @DisplayName("★ 收藏数用「查真实条数再回写」，而不是 ±1 累加")
        void syncsCountByQueryNotIncrement() {
            when(productService.getById(PID)).thenReturn(productOf(99L));
            // 第一次是查是否已收藏（0），第二次是同步计数（7）
            when(favoriteService.count(org.mockito.ArgumentMatchers.<Wrapper<Favorite>>any())).thenReturn(0L, 7L);

            FavoriteStatusVO vo = service.operate(PID, 1, UID);

            assertEquals(7, vo.getFavoriteCount(), "应回写真实条数 7");
            verify(productService).update(any());
        }

        @Test
        @DisplayName("hasFavorite：返回是否已收藏与全站收藏数")
        void hasFavorite() {
            when(favoriteService.count(org.mockito.ArgumentMatchers.<Wrapper<Favorite>>any())).thenReturn(1L, 42L);

            FavoriteStatusVO vo = service.hasFavorite(PID, UID);

            assertTrue(vo.getFavorited());
            assertEquals(42, vo.getFavoriteCount());
        }

        @Test
        @DisplayName("hasFavorite：未收藏时 favorited=false")
        void hasFavoriteFalse() {
            when(favoriteService.count(org.mockito.ArgumentMatchers.<Wrapper<Favorite>>any())).thenReturn(0L, 0L);

            assertFalse(service.hasFavorite(PID, UID).getFavorited());
        }

        @Test
        @DisplayName("我的收藏分页：字段从 IPage 映射并按当前用户查询")
        void pageMine() {
            IPage<FavoriteVO> page = mock(IPage.class);
            when(page.getTotal()).thenReturn(3L);
            when(page.getPages()).thenReturn(1L);
            when(page.getCurrent()).thenReturn(1L);
            when(page.getSize()).thenReturn(10L);
            when(page.getRecords()).thenReturn(List.of(new FavoriteVO()));
            when(favoriteModuleMapper.selectFavoritePage(any(), eq(UID))).thenReturn(page);

            var result = service.pageMine(1, 10, UID);

            assertEquals(3L, result.getTotal());
            assertEquals(1, result.getRecords().size());
            assertNotNull(result.getRecords());
        }
    }
}
