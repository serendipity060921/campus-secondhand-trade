package com.campus.trade.common.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.campus.trade.config.CacheProperties;
import com.campus.trade.service.CacheService;

/**
 * 缓存失效统一入口验证（v0.17）
 *
 * <p>缓存一致性最容易出问题的地方就是"写数据时忘了清缓存" —— 后果是用户看到旧数据，
 * 而且往往几天后才发现。{@link CacheEvictor} 把失效动作收敛成三个语义化方法，
 * 因此这里用 Mock 精确断言<b>每次业务变更到底清了哪些 Key / 模式</b>。</p>
 *
 * <p>其中 {@link #categoryPatternCoversTopAndAll()} 对应一次真实回归：
 * 分类列表缓存 Key 带 {@code :all / :top} 后缀，早期用具体 Key 删除会漏删
 * （v0.12 测试用例 C6 发现）。</p>
 */
class CacheEvictorTest {

    private CacheService cacheService;
    private CacheKeys keys;
    private CacheEvictor evictor;

    @BeforeEach
    void setUp() {
        CacheProperties props = new CacheProperties();
        props.setKeyPrefix("campus");
        keys = new CacheKeys(props);
        cacheService = mock(CacheService.class);
        evictor = new CacheEvictor(cacheService, keys);
    }

    @Nested
    @DisplayName("一、商品变更：详情 + 列表 + 推荐，且顺序固定")
    class ProductChanged {

        @Test
        @DisplayName("传入商品 id：先删详情键，再按模式清列表与推荐")
        void evictsDetailThenPatterns() {
            evictor.productChanged(42L);

            InOrder order = inOrder(cacheService);
            order.verify(cacheService).evict(eq("campus:product:detail:42"));
            order.verify(cacheService).evictByPattern(eq(keys.productListPattern()));
            order.verify(cacheService).evictByPattern(eq(keys.recommendPattern()));
            verifyNoMoreInteractions(cacheService);
        }

        @Test
        @DisplayName("id 为空时只清列表与推荐（不构造出 :product:detail:null 这种脏键）")
        void handlesNullId() {
            evictor.productChanged(null);

            verify(cacheService).evict(eq((String) null));
            verify(cacheService).evictByPattern(eq(keys.productListPattern()));
            verify(cacheService).evictByPattern(eq(keys.recommendPattern()));
            verifyNoMoreInteractions(cacheService);
        }

        @Test
        @DisplayName("不同商品清的是各自的详情键（不会误删别人的）")
        void differentProductDifferentKey() {
            evictor.productChanged(1L);
            verify(cacheService).evict(eq("campus:product:detail:1"));
            verify(cacheService, never()).evict(eq("campus:product:detail:2"));
        }
    }

    @Nested
    @DisplayName("二、分类变更：分类列表 + 商品列表 + 推荐")
    class CategoryChanged {

        @Test
        @DisplayName("三个模式都要清（列表里含分类名，推荐也可能受影响）")
        void evictsThreePatterns() {
            evictor.categoryChanged();

            verify(cacheService).evictByPattern(eq(keys.categoryListPattern()));
            verify(cacheService).evictByPattern(eq(keys.productListPattern()));
            verify(cacheService).evictByPattern(eq(keys.recommendPattern()));
            verifyNoMoreInteractions(cacheService);
            verify(cacheService, never()).evict(org.mockito.ArgumentMatchers.anyString());
        }

        /**
         * ★ 真实回归：分类列表键带 :all / :top 后缀，用具体键删除会漏删，必须按模式删。
         */
        @Test
        @DisplayName("分类模式能覆盖带后缀的具体键（防漏删）")
        void categoryPatternCoversTopAndAll() {
            String pattern = keys.categoryListPattern();
            String base = pattern.substring(0, pattern.length() - 1);

            assertEquals("campus:category:list", base);
            org.junit.jupiter.api.Assertions.assertTrue(
                    keys.categoryList().startsWith(base), "不带后缀的键也应被覆盖");
            org.junit.jupiter.api.Assertions.assertTrue(
                    (base + ":onlyTop=true").startsWith(base), "带 onlyTop 后缀的键必须被覆盖（C6 教训）");
        }
    }

    @Nested
    @DisplayName("三、用户行为变更：只清该用户的推荐缓存")
    class UserRecommendChanged {

        @Test
        @DisplayName("指定用户：只清该用户的推荐模式（不影响其他用户）")
        void evictsOnlyThatUser() {
            evictor.userRecommendChanged(9L);

            verify(cacheService).evictByPattern(eq(keys.recommendUserPattern(9L)));
            verifyNoMoreInteractions(cacheService);
        }

        @Test
        @DisplayName("userId 为空：什么都不做（避免清空全站推荐缓存）")
        void nullUserDoesNothing() {
            evictor.userRecommendChanged(null);

            verifyNoMoreInteractions(cacheService);
        }
    }
}
