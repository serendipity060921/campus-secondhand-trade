package com.campus.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存与限流参数（v0.12），对应 application.yml 的 {@code campus.cache.*}。
 *
 * <p>把 TTL、限流阈值、浏览量去重窗口等做成配置项：既便于按环境调优，
 * 也便于压测时通过 {@code campus.cache.enabled=false} 关掉缓存做对照实验。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "campus.cache")
public class CacheProperties {

    /** 总开关：关闭后所有缓存读写与浏览量去重都退化为直接访问数据库（用于压测对比） */
    private boolean enabled = true;

    /** Key 统一前缀，避免与其它应用共用 Redis 时冲突 */
    private String keyPrefix = "campus";

    /** 首页/搜索商品列表缓存 TTL（秒） */
    private long productListTtl = 60;

    /** 商品详情缓存 TTL（秒） */
    private long productDetailTtl = 120;

    /** 分类列表缓存 TTL（秒） */
    private long categoryTtl = 600;

    /** 个性化推荐结果缓存 TTL（秒） */
    private long recommendTtl = 300;

    /** 空值缓存 TTL（秒）：防止缓存穿透（查询不存在的商品时也缓存一个短 TTL 的空标记） */
    private long nullValueTtl = 30;

    /** TTL 随机抖动比例（0.2 表示实际 TTL 在 [0.8T, 1.2T] 之间）：防止缓存同时失效造成雪崩 */
    private double ttlJitter = 0.2;

    /** 浏览量去重窗口（秒）：同一用户/IP 在该窗口内重复访问详情只计一次浏览量 */
    private long viewDedupSeconds = 600;

    /** 浏览量批量回写阈值：Redis 累积到该值才写库（减少数据库写压力） */
    private long viewFlushThreshold = 20;

    /** 热门榜 Key 后缀（按天分桶：hot:20261003） */
    private String hotListKey = "hot";

    /** 热门榜保留天数 */
    private long hotListTtlDays = 3;

    /** 热门榜行为加分：浏览 / 收藏 / 私信 / 下单 */
    private double hotScoreView = 1.0;
    private double hotScoreFavorite = 5.0;
    private double hotScoreMessage = 3.0;
    private double hotScoreOrder = 10.0;

    /** 分布式锁 TTL（秒）：热点 Key 回源时只让一个线程查库，避免缓存击穿 */
    private long lockTtl = 10;

    /**
     * 限流是否跳过本机回环地址（默认 true）。
     *
     * <p>开发/压测场景下，所有请求都来自 127.0.0.1，如果对本机也限流，
     * 会让自动化测试与压测脚本相互干扰；线上经 Nginx 转发后
     * {@code X-Forwarded-For} 携带的是真实客户端 IP，因此不会受影响。
     * 如需在本地验证限流效果，可把该值设为 false，或用 X-Forwarded-For 伪造外部 IP。</p>
     */
    private boolean rateLimitSkipLocal = true;
}
