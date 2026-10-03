package com.campus.trade.service;

import com.campus.trade.vo.RecommendResultVO;

/**
 * 推荐服务（v0.11）。
 *
 * <p>算法：<b>多路召回 + 融合排序</b></p>
 * <ol>
 *   <li><b>协同过滤（Item-CF）</b>：用户行为 → 物品共现矩阵 → 物品相似度
 *       {@code sim(i,j) = co(i,j) / sqrt(pop(i) * pop(j))}，按用户历史兴趣加权预测；</li>
 *   <li><b>内容匹配</b>：用户偏好的分类 + 价格区间 + 校区，与候选商品打分；</li>
 *   <li><b>热门度</b>：浏览量 / 收藏量 / 行为人数，并做时间衰减；</li>
 *   <li>三路归一化后按配置权重融合，再做<b>分类多样性控制</b>与<b>已交互过滤</b>；</li>
 *   <li>输出可解释的推荐理由（协同过滤 / 内容匹配 / 热门），便于前端展示与论文消融分析。</li>
 * </ol>
 */
public interface RecommendService {

    /**
     * 个性化推荐（首页"猜你喜欢"）。
     *
     * @param userId   当前登录用户ID，可为 null（未登录 → 热门冷启动）
     * @param size     返回条数，为空取默认值
     * @param strategy 召回策略：{@code auto}（默认，按配置融合权重）/
     *                 {@code hot}（纯热门）/ {@code content}（纯内容匹配）/
     *                 {@code cf}（纯协同过滤）/ {@code hybrid}（融合）。
     *                 显式指定策略主要用于<b>离线对比实验</b>与线上 A/B 测试；
     *                 无论哪种策略，过滤规则（自己发布 / 已收藏 / 已下单）都保持一致，保证可比性。
     */
    RecommendResultVO recommend(Long userId, Integer size, String strategy);

    /**
     * 相似商品（详情页"相关推荐"），基于物品共现相似度，不足时用同分类热门补齐。
     *
     * @param productId 当前商品ID
     * @param size      返回条数
     */
    RecommendResultVO similar(Long productId, Integer size);

    /**
     * 热门榜（v0.12）：直接读 Redis ZSet（由用户行为实时加分），不查数据库；
     * 榜单为空时退化为热门度排序。
     *
     * @param size 返回条数
     */
    java.util.List<com.campus.trade.vo.RecommendItemVO> hotList(Integer size);
}
