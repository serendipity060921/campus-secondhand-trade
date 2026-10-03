package com.campus.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推荐算法可调参数（v0.11）。
 *
 * <p>把权重、融合系数、多样性上限等做成配置项而不是硬编码，好处是：</p>
 * <ul>
 *   <li>论文里可以直接给出"参数配置表"，并说明调参过程；</li>
 *   <li>离线评测脚本可以批量改参数做对比实验（无需改代码）。</li>
 * </ul>
 *
 * <p>对应 application.yml 中的 {@code campus.recommend.*}</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "campus.recommend")
public class RecommendProperties {

    /** 是否启用个性化推荐（关闭后所有场景退化为热门推荐，便于做 A/B 对比） */
    private boolean enabled = true;

    /** 各行为的兴趣权重：1浏览 2收藏 3私信 4下单 */
    private Map<Integer, Double> behaviorWeights = new LinkedHashMap<Integer, Double>() {{
        put(1, 1.0);    // 浏览
        put(2, 3.0);    // 收藏
        put(3, 2.0);    // 私信
        put(4, 5.0);    // 下单
    }};

    /** 融合权重：协同过滤 / 内容匹配 / 热门度（三者之和建议为 1） */
    private double cfWeight = 0.50;
    private double contentWeight = 0.35;
    private double hotWeight = 0.15;

    /** 时间衰减半衰期（天）：行为越久远影响越小，weight(t) = 0.5 ^ (days / halfLifeDays) */
    private double halfLifeDays = 30.0;

    /** 候选池大小（先按热门度取前 N 个在售商品作为候选，再做精排） */
    private int candidatePoolSize = 200;

    /** 默认返回条数 */
    private int defaultSize = 8;

    /** 单次请求最大条数 */
    private int maxSize = 30;

    /** 同一分类最多出现几条（保证推荐结果的多样性） */
    private int categoryCap = 3;

    /** 相似商品的默认条数 */
    private int similarSize = 6;

    /** 物品相似度阈值，低于该值视为不相似 */
    private double similarThreshold = 0.02;

    /** 已浏览过但未收藏/未下单的商品，最终得分乘以该系数（不完全排除，用户可能仍想买） */
    private double seenPenalty = 0.55;

    /** 与用户所在校区一致时的内容加成 */
    private double campusBonus = 0.15;

    /** 冷启动（未登录 / 无行为）时返回的热门商品条数 */
    private int coldStartSize = 12;
}
