package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campus.trade.common.cache.CacheEvictor;
import com.campus.trade.common.cache.CacheKeys;
import com.campus.trade.common.constant.ProductStatus;
import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.moderation.ContentModerator;
import com.campus.trade.common.moderation.ModerationAction;
import com.campus.trade.common.moderation.ModerationResult;
import com.campus.trade.config.AuditProperties;
import com.campus.trade.config.CacheProperties;
import com.campus.trade.dto.ProductPublishDTO;
import com.campus.trade.entity.Category;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.ProductImage;
import com.campus.trade.service.CacheService;
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.ModerationLogService;
import com.campus.trade.service.ProductImageService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserBehaviorService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.ProductDetailVO;

/**
 * 商品模块验证（v0.18 / 收尾⑥）
 *
 * <p>这是服务层里最大、也是缓存逻辑最密的一个类。除了常规的发布与上下架校验，
 * 本测试重点守住 <b>几条用真实缺陷换来的缓存纪律</b>：</p>
 *
 * <ol>
 *   <li><b>待审核商品不写公共缓存</b> —— 缓存 Key 只按商品 ID 划分、不含访问者身份，
 *       而"待审核是否可见"取决于身份（卖家本人 / 管理员可见，其他人按不存在处理）。
 *       一旦把卖家视角写进公共缓存，匿名用户随后就能看到未审核内容；</li>
 *   <li><b>空值缓存只能用于"确实不存在"</b> —— 若把"对某访问者不可见"也缓存成空值，
 *       卖家与管理员随后会被误判为 3001；</li>
 *   <li><b>行为埋点必须同时挂在缓存命中路径上</b> —— 否则热门商品的重复浏览学不到，
 *       推荐数据被缓存"截断"；</li>
 *   <li><b>防击穿的降级链</b>：抢到锁 → 回源并写缓存；没抢到 → 短暂等待重读；
 *       仍没有 → 直接查库且<b>不写缓存</b>，绝不阻塞用户。</li>
 * </ol>
 */
class ProductModuleServiceImplTest {

    private ProductService productService;
    private ProductImageService productImageService;
    private CategoryService categoryService;
    private UserService userService;
    private UserBehaviorService userBehaviorService;
    private CacheService cacheService;
    private CacheEvictor cacheEvictor;
    private ContentModerator contentModerator;
    private ModerationLogService moderationLogService;
    private AuditProperties auditProperties;

    private CacheKeys cacheKeys;
    private CacheProperties cacheProperties;
    private ProductModuleServiceImpl service;

    private static final Long SELLER = 11L;
    private static final Long OTHER = 22L;
    private static final Long ADMIN = 33L;
    private static final Long PRODUCT_ID = 88L;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        // LambdaUpdateWrapper / LambdaQueryWrapper 解析"实体::getter"依赖 TableInfo，
        // 该元数据平时由 MyBatis 启动时注册，纯单测需显式初始化
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Product.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ProductImage.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Category.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, com.campus.trade.entity.User.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        productService = mock(ProductService.class);
        productImageService = mock(ProductImageService.class);
        categoryService = mock(CategoryService.class);
        userService = mock(UserService.class);
        userBehaviorService = mock(UserBehaviorService.class);
        cacheService = mock(CacheService.class);
        cacheEvictor = mock(CacheEvictor.class);
        contentModerator = mock(ContentModerator.class);
        moderationLogService = mock(ModerationLogService.class);
        auditProperties = mock(AuditProperties.class);

        // 用真实配置对象：lockTtl=10 / nullValueTtl=30 / productDetailTtl=120
        //                viewDedupSeconds=600 / viewFlushThreshold=20
        cacheProperties = new CacheProperties();
        cacheKeys = new CacheKeys(cacheProperties);

        when(cacheService.enabled()).thenReturn(true);
        // 注意：IService 同时存在 list(Wrapper) 与 list(IPage) 两个单参重载，
        // 直接写 list(any()) 会报"引用不明确"，这里显式指定匹配 Wrapper 重载。
        when(productImageService.list((Wrapper<ProductImage>) any())).thenReturn(Collections.emptyList());

        service = new ProductModuleServiceImpl(productService, productImageService, categoryService,
                userService, userBehaviorService, cacheService, cacheKeys, cacheEvictor,
                cacheProperties, auditProperties, contentModerator, moderationLogService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ---------------- 夹具 ---------------- */

    private Product product(int status, Long sellerId) {
        Product p = new Product();
        p.setId(PRODUCT_ID);
        p.setTitle("九成新《数据结构》教材");
        p.setDescription("东校区自取");
        p.setCategoryId(3L);
        p.setSellerId(sellerId);
        p.setPrice(new BigDecimal("25.50"));
        p.setStatus(status);
        p.setViewCount(100);
        p.setFavoriteCount(2);
        return p;
    }

    private Category category() {
        Category c = new Category();
        c.setId(3L);
        c.setName("教材教辅");
        return c;
    }

    private ProductPublishDTO publishDTO(List<String> images) {
        ProductPublishDTO dto = new ProductPublishDTO();
        dto.setTitle("  九成新《数据结构》教材  ");
        dto.setDescription("东校区自取");
        dto.setCategoryId(3L);
        dto.setPrice(new BigDecimal("25.50"));
        dto.setConditionLevel(3);
        dto.setCampus("东校区");
        dto.setImageUrls(images);
        return dto;
    }

    private ModerationResult moderation(ModerationAction action) {
        if (action == ModerationAction.PASS) {
            return ModerationResult.pass();
        }
        return ModerationResult.of(action, List.of(new ModerationResult.Hit(
                action == ModerationAction.REJECT ? "R1-违禁词" : "R2-联系方式",
                "title", "代写", "标题包含违规内容「代写」", action)));
    }

    private void moderationRejects() {
        when(contentModerator.moderate(any(), any(), any(), any()))
                .thenReturn(moderation(ModerationAction.REJECT));
    }

    private void moderationPasses() {
        when(contentModerator.moderate(any(), any(), any(), any()))
                .thenReturn(moderation(ModerationAction.PASS));
    }

    private void moderationReviews() {
        when(contentModerator.moderate(any(), any(), any(), any()))
                .thenReturn(moderation(ModerationAction.REVIEW));
    }

    private ProductDetailVO cachedVo(Long sellerId) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(PRODUCT_ID);
        vo.setSellerId(sellerId);
        vo.setTitle("缓存里的商品");
        vo.setViewCount(7);
        return vo;
    }

    @Nested
    @DisplayName("一、发布：机审三种结论与审核开关")
    class Publish {

        @BeforeEach
        void categoryExists() {
            when(categoryService.getById(3L)).thenReturn(category());
            // 模拟 MyBatis-Plus 插入后回填自增主键：发布成功后会拿它做缓存失效，
            // 若不回填，cacheEvictor.productChanged 收到的会是 null（真实环境不会）。
            org.mockito.Mockito.doAnswer(inv -> {
                ((Product) inv.getArgument(0)).setId(PRODUCT_ID);
                return true;
            }).when(productService).save(any(Product.class));
        }

        @Test
        @DisplayName("分类不存在 → 报错，且不落任何数据")
        void categoryNotFound() {
            when(categoryService.getById(3L)).thenReturn(null);

            ProductException e = assertThrows(ProductException.class,
                    () -> service.publish(publishDTO(null), SELLER));

            assertEquals(ProductException.categoryNotFound().getCode(), e.getCode());
            verify(productService, never()).save(any());
        }

        @Test
        @DisplayName("★ 机审拒绝 → 抛 3010 且不落商品，但机审记录必须落库（拒绝也留痕）")
        void rejectedByModeration() {
            moderationRejects();

            ProductException e = assertThrows(ProductException.class,
                    () -> service.publish(publishDTO(null), SELLER));

            assertEquals(ProductException.contentRejected("x").getCode(), e.getCode());
            verify(productService, never()).save(any());
            // 落库在独立事务中，且 auditRequired=false
            verify(moderationLogService).record(eq(SELLER), isNull(), anyString(),
                    any(ModerationResult.class), eq(false));
        }

        @Test
        @DisplayName("★ 机审转人工复核 → 状态置「待审核」，并标记需要人审")
        void reviewedByModeration() {
            moderationReviews();

            service.publish(publishDTO(null), SELLER);

            Product saved = captureSaved();
            assertEquals(ProductStatus.PENDING.getCode(), saved.getStatus());
            assertNull(saved.getShelfTime(), "待审核商品不应有上架时间");
            verify(moderationLogService).record(eq(SELLER), isNull(), anyString(),
                    any(ModerationResult.class), eq(true));
        }

        @Test
        @DisplayName("机审放行 + 审核开关关闭 → 直接上架，记录上架时间")
        void passedAndAuditDisabled() {
            moderationPasses();
            when(auditProperties.isEnabled()).thenReturn(false);

            service.publish(publishDTO(null), SELLER);

            Product saved = captureSaved();
            assertEquals(ProductStatus.ON_SALE.getCode(), saved.getStatus());
            assertNotNull(saved.getShelfTime(), "上架商品必须记录上架时间");
            verify(moderationLogService).record(eq(SELLER), isNull(), anyString(),
                    any(ModerationResult.class), eq(false));
        }

        @Test
        @DisplayName("机审放行但全局审核开关打开 → 所有商品都要人审（待审核）")
        void passedWithAuditEnabled() {
            moderationPasses();
            when(auditProperties.isEnabled()).thenReturn(true);

            service.publish(publishDTO(null), SELLER);

            Product saved = captureSaved();
            assertEquals(ProductStatus.PENDING.getCode(), saved.getStatus());
            assertNull(saved.getShelfTime());
        }

        @Test
        @DisplayName("发布成功：标题去空格、计数归零、首图作封面、触发缓存失效")
        void publishFillsFieldsAndEvictsCache() {
            moderationPasses();
            when(auditProperties.isEnabled()).thenReturn(false);

            service.publish(publishDTO(List.of("/upload/a.png", "/upload/b.png")), SELLER);

            Product saved = captureSaved();
            assertEquals("九成新《数据结构》教材", saved.getTitle(), "标题首尾空格应被去掉");
            assertEquals(SELLER, saved.getSellerId());
            assertEquals(0, saved.getViewCount());
            assertEquals(0, saved.getFavoriteCount());
            assertEquals(0, saved.getDeleted());
            assertEquals("/upload/a.png", saved.getCoverImage(), "没有封面时用第一张图");
            verify(cacheEvictor).productChanged(PRODUCT_ID);
        }

        private Product captureSaved() {
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(productService).save(captor.capture());
            return captor.getValue();
        }
    }

    @Nested
    @DisplayName("二、★ 商品详情的缓存纪律（几条用真实缺陷换来的规则）")
    class DetailCache {

        @Test
        @DisplayName("缓存命中：直接返回，完全不查库")
        void cacheHitSkipsDatabase() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.exists(key)).thenReturn(true);
            when(cacheService.get(key, ProductDetailVO.class)).thenReturn(cachedVo(SELLER));

            ProductDetailVO vo = service.detail(PRODUCT_ID);

            assertEquals(7, vo.getViewCount());
            verify(productService, never()).getById(anyLong());
        }

        @Test
        @DisplayName("★ 命中「空值缓存」→ 按商品不存在处理（防穿透）")
        void nullValueCacheMeansNotFound() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.exists(key)).thenReturn(true);
            when(cacheService.get(key, ProductDetailVO.class)).thenReturn(null);

            ProductException e = assertThrows(ProductException.class, () -> service.detail(PRODUCT_ID));

            assertEquals(ProductException.notFound().getCode(), e.getCode());
            verify(productService, never()).getById(anyLong());
        }

        @Test
        @DisplayName("★ 缓存命中路径也要做行为埋点（否则重复浏览学不到）")
        void cacheHitStillRecordsBehavior() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.exists(key)).thenReturn(true);
            when(cacheService.get(key, ProductDetailVO.class)).thenReturn(cachedVo(SELLER));
            UserContext.set(new LoginUser(OTHER, "buyer", 0));

            service.detail(PRODUCT_ID);

            verify(userBehaviorService).record(eq(OTHER), eq(PRODUCT_ID), eq(UserBehaviorService.TYPE_VIEW));
        }

        @Test
        @DisplayName("缓存未命中且抢到锁：回源、写缓存、释放锁")
        void cacheMissWithLock() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            ProductDetailVO vo = service.detail(PRODUCT_ID);

            assertNotNull(vo);
            verify(cacheService).set(eq(key), any(ProductDetailVO.class), eq(cacheProperties.getProductDetailTtl()));
            verify(cacheService).releaseLock(anyString(), anyString());
        }

        @Test
        @DisplayName("★ 没抢到锁：短暂等待后重读缓存命中，不查库（防击穿）")
        void lockNotAcquiredThenCacheAppears() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(false);
            when(cacheService.get(key, ProductDetailVO.class)).thenReturn(cachedVo(SELLER));

            ProductDetailVO vo = service.detail(PRODUCT_ID);

            assertEquals("缓存里的商品", vo.getTitle());
            verify(productService, never()).getById(anyLong());
            verify(cacheService, never()).releaseLock(anyString(), anyString());
        }

        @Test
        @DisplayName("★ 没抢到锁且仍未命中：降级直接查库，但绝不写缓存（避免与持锁线程竞争）")
        void lockNotAcquiredDegradesWithoutCaching() {
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(false);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            ProductDetailVO vo = service.detail(PRODUCT_ID);

            assertNotNull(vo);
            verify(cacheService, never()).set(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("商品确实不存在 → 写「空值缓存」防穿透，并抛 3001")
        void productMissingWritesNullCache() {
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
            when(productService.getById(PRODUCT_ID)).thenReturn(null);

            ProductException e = assertThrows(ProductException.class, () -> service.detail(PRODUCT_ID));

            assertEquals(ProductException.notFound().getCode(), e.getCode());
            verify(cacheService).setNull(eq(cacheKeys.productDetail(PRODUCT_ID)),
                    eq(cacheProperties.getNullValueTtl()));
        }

        @Test
        @DisplayName("★★ 待审核商品：匿名访问按不存在处理，且【不能】写空值缓存")
        void pendingHiddenFromAnonymousWithoutNullCache() {
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.PENDING.getCode(), SELLER));
            UserContext.clear();

            assertThrows(ProductException.class, () -> service.detail(PRODUCT_ID));

            // 若写了空值缓存，卖家与管理员随后会被误判为 3001
            verify(cacheService, never()).setNull(anyString(), anyLong());
            verify(cacheService, never()).set(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("★★ 待审核商品：卖家本人可见，但【绝不】写入公共缓存（Key 不含身份维度）")
        void pendingVisibleToOwnerButNeverCached() {
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.PENDING.getCode(), SELLER));
            when(categoryService.getById(3L)).thenReturn(category());
            UserContext.set(new LoginUser(SELLER, "seller", 0));

            ProductDetailVO vo = service.detail(PRODUCT_ID);

            assertNotNull(vo);
            verify(cacheService, never()).set(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("★★ 待审核商品：管理员同样可见，同样不写缓存")
        void pendingVisibleToAdmin() {
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.PENDING.getCode(), SELLER));
            when(categoryService.getById(3L)).thenReturn(category());
            UserContext.set(new LoginUser(ADMIN, "admin", 1));

            assertNotNull(service.detail(PRODUCT_ID));
            verify(cacheService, never()).set(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("在售商品写缓存时：浏览量 = 数据库值 + Redis 待回写值")
        void cachedViewCountMergesPendingCounter() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));
            when(cacheService.getCounter(cacheKeys.viewCounter(PRODUCT_ID))).thenReturn(5L);

            ArgumentCaptor<ProductDetailVO> captor = ArgumentCaptor.forClass(ProductDetailVO.class);
            service.detail(PRODUCT_ID);
            verify(cacheService).set(eq(key), captor.capture(), anyLong());

            assertEquals(105, captor.getValue().getViewCount(), "100（库）+ 5（Redis 待回写）");
        }
    }

    @Nested
    @DisplayName("三、浏览量统计：去重、批量回写与降级")
    class ViewStat {

        @Test
        @DisplayName("缓存关闭时直接写库（保证浏览量不丢）")
        void disabledCacheWritesThrough() {
            when(cacheService.enabled()).thenReturn(false);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            service.detail(PRODUCT_ID);

            verify(productService).update(any(Wrapper.class));
            verify(cacheService, never()).increment(anyString(), anyLong());
        }

        @Test
        @DisplayName("★ 窗口内重复访问：去重锁拿不到，不计数")
        void duplicateVisitNotCounted() {
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(false);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            service.detail(PRODUCT_ID);

            verify(cacheService, never()).increment(anyString(), anyLong());
        }

        @Test
        @DisplayName("首次访问且未到阈值：只在 Redis 计数，不回写数据库")
        void firstVisitCountsInRedisOnly() {
            when(cacheService.enabled()).thenReturn(true);
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
            when(cacheService.increment(anyString(), anyLong())).thenReturn(3L);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            service.detail(PRODUCT_ID);

            verify(cacheService).increment(eq(cacheKeys.viewCounter(PRODUCT_ID)), anyLong());
            verify(cacheService, never()).resetCounter(anyString());
        }

        @Test
        @DisplayName("★ 计数达到阈值：一次性回写数据库并清零计数（把 N 次写库压成 1 次）")
        void thresholdTriggersBatchFlush() {
            when(cacheService.enabled()).thenReturn(true);
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
            when(cacheService.increment(anyString(), anyLong()))
                    .thenReturn((long) cacheProperties.getViewFlushThreshold());
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            service.detail(PRODUCT_ID);

            verify(productService).update(any(Wrapper.class));
            verify(cacheService).resetCounter(eq(cacheKeys.viewCounter(PRODUCT_ID)));
        }

        @Test
        @DisplayName("未登录访问：去重维度退化为 IP（避免所有人共用一个维度）")
        void anonymousUsesIpDimension() {
            when(cacheService.enabled()).thenReturn(true);
            when(cacheService.tryLock(anyString(), anyString(), anyLong())).thenReturn(false);
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));
            UserContext.clear();

            service.detail(PRODUCT_ID);

            verify(cacheService).tryLock(eq(cacheKeys.viewDedup(PRODUCT_ID, "ipunknown")), eq("1"),
                    eq(cacheProperties.getViewDedupSeconds()));
        }
    }

    @Nested
    @DisplayName("四、上下架：参数、归属、状态与缓存失效")
    class ChangeStatus {

        @Test
        @DisplayName("status 只允许 1（上架）或 3（下架）")
        void onlyOnSaleOrOffShelf() {
            for (Integer bad : new Integer[]{null, 0, 2, 4, 5, 9}) {
                ProductException e = assertThrows(ProductException.class,
                        () -> service.changeStatus(PRODUCT_ID, bad, SELLER), "status=" + bad);
                assertEquals(ProductException.paramIllegal("x").getCode(), e.getCode());
            }
            verify(productService, never()).updateById(any());
        }

        @Test
        @DisplayName("商品不存在 → 3001")
        void productNotFound() {
            when(productService.getById(PRODUCT_ID)).thenReturn(null);
            assertEquals(ProductException.notFound().getCode(),
                    assertThrows(ProductException.class,
                            () -> service.changeStatus(PRODUCT_ID, 1, SELLER)).getCode());
        }

        @Test
        @DisplayName("越权下架他人商品 → 3002，且不改数据、不清缓存")
        void cannotChangeOthersProduct() {
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            ProductException e = assertThrows(ProductException.class,
                    () -> service.changeStatus(PRODUCT_ID, 3, OTHER));

            assertEquals(ProductException.noPermission().getCode(), e.getCode());
            verify(productService, never()).updateById(any());
            verify(cacheEvictor, never()).productChanged(anyLong());
        }

        @Test
        @DisplayName("★ 交易中 / 已售出的商品不允许再上下架")
        void tradingOrSoldCannotChangeStatus() {
            for (Integer locked : new Integer[]{ProductStatus.TRADING.getCode(), ProductStatus.SOLD.getCode()}) {
                when(productService.getById(PRODUCT_ID)).thenReturn(product(locked, SELLER));
                ProductException e = assertThrows(ProductException.class,
                        () -> service.changeStatus(PRODUCT_ID, 3, SELLER), "status=" + locked);
                assertEquals(ProductException.statusIllegal().getCode(), e.getCode());
            }
            verify(productService, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 状态未变化时静默返回，不做无意义的更新与缓存清理")
        void sameStatusIgnored() {
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            service.changeStatus(PRODUCT_ID, ProductStatus.ON_SALE.getCode(), SELLER);

            verify(productService, never()).updateById(any());
            verify(cacheEvictor, never()).productChanged(anyLong());
        }

        @Test
        @DisplayName("上架：写入 status=1 并刷新上架时间，同时清理缓存")
        void onSaleRefreshesShelfTime() {
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.OFF_SHELF.getCode(), SELLER));

            service.changeStatus(PRODUCT_ID, ProductStatus.ON_SALE.getCode(), SELLER);

            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(productService).updateById(captor.capture());
            assertEquals(ProductStatus.ON_SALE.getCode(), captor.getValue().getStatus());
            assertNotNull(captor.getValue().getShelfTime());
            verify(cacheEvictor).productChanged(PRODUCT_ID);
        }

        @Test
        @DisplayName("下架：写入 status=3，不改上架时间，同样清理缓存")
        void offShelfKeepsShelfTime() {
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));

            service.changeStatus(PRODUCT_ID, ProductStatus.OFF_SHELF.getCode(), SELLER);

            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(productService).updateById(captor.capture());
            assertEquals(ProductStatus.OFF_SHELF.getCode(), captor.getValue().getStatus());
            assertNull(captor.getValue().getShelfTime(), "下架不应写上架时间");
            verify(cacheEvictor).productChanged(PRODUCT_ID);
        }
    }

    @Nested
    @DisplayName("五、追加图片：上限、归属与封面补齐")
    class AddImages {

        @Test
        @DisplayName("空列表直接返回，不查库")
        void emptyUrlsNoop() {
            service.addImages(PRODUCT_ID, Collections.emptyList(), SELLER);
            verify(productService, never()).getById(anyLong());
        }

        @Test
        @DisplayName("商品不存在 → 3001；越权 → 3002")
        void notFoundAndNoPermission() {
            when(productService.getById(PRODUCT_ID)).thenReturn(null);
            assertEquals(ProductException.notFound().getCode(),
                    assertThrows(ProductException.class,
                            () -> service.addImages(PRODUCT_ID, List.of("/a.png"), SELLER)).getCode());

            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));
            assertEquals(ProductException.noPermission().getCode(),
                    assertThrows(ProductException.class,
                            () -> service.addImages(PRODUCT_ID, List.of("/a.png"), OTHER)).getCode());
        }

        @Test
        @DisplayName("★ 超过图片数量上限 → 报错，不写入")
        void imageCountExceeded() {
            when(productService.getById(PRODUCT_ID)).thenReturn(product(ProductStatus.ON_SALE.getCode(), SELLER));
            when(productImageService.count(any())).thenReturn(999L);

            ProductException e = assertThrows(ProductException.class,
                    () -> service.addImages(PRODUCT_ID, List.of("/a.png"), SELLER));

            assertEquals(ProductException.imageCountExceeded().getCode(), e.getCode());
            verify(productImageService, never()).saveBatch(any());
        }

        @Test
        @DisplayName("★ 本来就已有封面时不覆盖，只追加图片并清缓存")
        void existingCoverNotOverwritten() {
            Product p = product(ProductStatus.ON_SALE.getCode(), SELLER);
            p.setCoverImage("/upload/old.png");
            when(productService.getById(PRODUCT_ID)).thenReturn(p);
            when(productImageService.count(any())).thenReturn(1L);

            service.addImages(PRODUCT_ID, List.of("/upload/new.png"), SELLER);

            verify(productImageService).saveBatch(any());
            verify(productService, never()).updateById(any());
            verify(cacheEvictor).productChanged(PRODUCT_ID);
        }
    }

    @Nested
    @DisplayName("六、行为埋点的三条纪律")
    class BehaviorDiscipline {

        @Test
        @DisplayName("未登录不记录")
        void anonymousNotRecorded() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.exists(key)).thenReturn(true);
            when(cacheService.get(key, ProductDetailVO.class)).thenReturn(cachedVo(SELLER));
            UserContext.clear();

            service.detail(PRODUCT_ID);

            verify(userBehaviorService, never()).record(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("卖家浏览自己的商品不计入兴趣（避免自我强化）")
        void sellerSelfViewNotRecorded() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.exists(key)).thenReturn(true);
            when(cacheService.get(key, ProductDetailVO.class)).thenReturn(cachedVo(SELLER));
            UserContext.set(new LoginUser(SELLER, "seller", 0));

            service.detail(PRODUCT_ID);

            verify(userBehaviorService, never()).record(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("★ 埋点失败不影响详情返回（异常被吞掉）")
        void behaviorFailureSwallowed() {
            String key = cacheKeys.productDetail(PRODUCT_ID);
            when(cacheService.exists(key)).thenReturn(true);
            when(cacheService.get(key, ProductDetailVO.class)).thenReturn(cachedVo(SELLER));
            UserContext.set(new LoginUser(OTHER, "buyer", 0));
            doThrow(new RuntimeException("埋点服务不可用")).when(userBehaviorService)
                    .record(anyLong(), anyLong(), anyInt());

            ProductDetailVO vo = service.detail(PRODUCT_ID);

            assertNotNull(vo, "埋点异常不应影响详情返回");
        }
    }
}
