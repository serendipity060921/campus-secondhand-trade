package com.campus.trade.common.cache;

import com.campus.trade.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 缓存失效统一入口（v0.12）。
 *
 * <p>为什么单独抽一个组件：缓存一致性最容易出问题的地方就是"写数据时忘了清缓存"。
 * 把所有失效动作收敛到三个语义化方法，业务代码只声明"什么变了"，
 * 具体清哪些 Key 由这里统一决定，避免各处重复、遗漏：</p>
 *
 * <table border="1">
 *   <caption>失效策略（Cache-Aside + 主动失效）</caption>
 *   <tr><th>业务动作</th><th>需要失效的缓存</th></tr>
 *   <tr><td>商品发布 / 上下架 / 成交</td><td>商品详情 + 商品列表 + 推荐结果</td></tr>
 *   <tr><td>收藏 / 取消收藏（影响 favorite_count）</td><td>商品详情 + 商品列表 + 推荐结果</td></tr>
 *   <tr><td>下单 / 订单状态变更（影响商品状态与浏览量口径）</td><td>商品详情 + 商品列表 + 推荐结果</td></tr>
 *   <tr><td>分类新增 / 修改</td><td>分类列表 + 商品列表（列表里有分类名） + 推荐结果</td></tr>
 *   <tr><td>用户新增行为</td><td>该用户的推荐结果（其他用户不受影响）</td></tr>
 *   <tr><td>用户改资料（昵称/头像会出现在商品卡片）</td><td>商品列表 + 商品详情（TTL 兜底，不做全量清理）</td></tr>
 * </table>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictor {

    private final CacheService cacheService;
    private final CacheKeys keys;

    /** 商品相关缓存失效（发布 / 上下架 / 收藏数变化 / 下单改状态） */
    public void productChanged(Long productId) {
        cacheService.evict(productId == null ? null : keys.productDetail(productId));
        cacheService.evictByPattern(keys.productListPattern());
        cacheService.evictByPattern(keys.recommendPattern());
    }

    /** 分类相关缓存失效 */
    public void categoryChanged() {
        // 注意：分类列表缓存 Key 带 onlyTop 后缀（:all / :top），
        // 必须按模式删除，否则会漏删（v0.12 测试用例 C6 发现过这个问题）
        cacheService.evictByPattern(keys.categoryListPattern());
        cacheService.evictByPattern(keys.productListPattern());
        cacheService.evictByPattern(keys.recommendPattern());
    }

    /** 只清理某个用户的推荐缓存（用户产生新行为时） */
    public void userRecommendChanged(Long userId) {
        if (userId != null) {
            cacheService.evictByPattern(keys.recommendUserPattern(userId));
        }
    }
}
