package com.campus.trade.common.cache;

import com.campus.trade.config.CacheProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 缓存 Key 命名规范（v0.12）。
 *
 * <p>统一用 {@code 前缀:业务:维度...} 的层级结构，好处：</p>
 * <ul>
 *   <li>redis-cli 里 {@code KEYS campus:product:*} 就能按业务批量排查；</li>
 *   <li>失效时可以按前缀精确删除（如商品变更只清 product 相关缓存）；</li>
 *   <li>Key 里带上查询条件，天然实现"按查询维度缓存"。</li>
 * </ul>
 *
 * <pre>
 *   campus:product:list:{条件指纹}        首页/搜索商品列表
 *   campus:product:detail:{id}            商品详情
 *   campus:category:list                  分类列表
 *   campus:recommend:{userId|anon}:{size}:{strategy}   推荐结果
 *   campus:hot:{yyyyMMdd}                 热门榜（ZSet）
 *   campus:view:dedup:{productId}:{访问者}  浏览量去重标记
 *   campus:view:counter:{productId}        浏览量待回写计数
 *   campus:ratelimit:{业务}:{维度}          限流计数器
 *   campus:jwt:blacklist:{tokenHash}       登出后的 Token 黑名单
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class CacheKeys {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CacheProperties properties;

    public String prefix() {
        return properties.getKeyPrefix();
    }

    public String productList(String condition) {
        return prefix() + ":product:list:" + condition;
    }

    public String productDetail(Long productId) {
        return prefix() + ":product:detail:" + productId;
    }

    public String categoryList() {
        return prefix() + ":category:list";
    }

    public String recommend(Long userId, int size, String strategy) {
        return prefix() + ":recommend:" + (userId == null ? "anon" : userId) + ":" + size + ":" + strategy;
    }

    public String hotList() {
        return prefix() + ":hot:" + LocalDate.now().format(DAY);
    }

    public String viewDedup(Long productId, String visitor) {
        return prefix() + ":view:dedup:" + productId + ":" + visitor;
    }

    public String viewCounter(Long productId) {
        return prefix() + ":view:counter:" + productId;
    }

    public String rateLimit(String biz, String dimension) {
        return prefix() + ":ratelimit:" + biz + ":" + dimension;
    }

    public String jwtBlacklist(String tokenHash) {
        return prefix() + ":jwt:blacklist:" + tokenHash;
    }

    /* ---------------- 按模式失效用的通配 Key ---------------- */

    /** 分类列表缓存（带 onlyTop 后缀）的通配模式 */
    public String categoryListPattern() {
        return prefix() + ":category:list*";
    }

    public String productListPattern() {
        return prefix() + ":product:list:*";
    }

    public String productDetailPattern() {
        return prefix() + ":product:detail:*";
    }

    public String recommendPattern() {
        return prefix() + ":recommend:*";
    }

    public String recommendUserPattern(Long userId) {
        return prefix() + ":recommend:" + userId + ":*";
    }

    public String hotListPattern() {
        return prefix() + ":hot:*";
    }

    /**
     * 把查询条件拼成稳定的"条件指纹"（用 TreeMap 保证参数顺序无关、剔除空值），
     * 作为列表缓存 Key 的后缀。
     */
    public String conditionOf(Map<String, Object> params) {
        Map<String, Object> sorted = new TreeMap<>(params == null ? Map.of() : params);
        String text = sorted.entrySet().stream()
                .filter(e -> e.getValue() != null && !"".equals(e.getValue()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"))
                .replace(" ", "_")
                .replace("/", "_");
        return text.isEmpty() ? "all" : text;
    }

    /** 便捷构造参数 Map：params("page", 1, "size", 12, "categoryId", 3) */
    public Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
