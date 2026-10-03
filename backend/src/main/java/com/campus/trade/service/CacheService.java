package com.campus.trade.service;

import java.util.List;

/**
 * 缓存服务（v0.12）。
 *
 * <p>把缓存相关的通用能力收敛到一个服务里，业务代码只调用语义化方法：</p>
 * <ul>
 *   <li><b>读</b>：{@link #exists} + {@link #get} 组合使用，可区分"未命中"与"缓存了空值"；</li>
 *   <li><b>写</b>：{@link #set} / {@link #setNull}，TTL 自动加随机抖动（防雪崩）；</li>
 *   <li><b>失效</b>：{@link #evict} / {@link #evictByPattern}（Cache-Aside 的主动失效）；</li>
 *   <li><b>计数与锁</b>：{@link #increment}（限流、浏览量）、{@link #tryLock}（防缓存击穿）；</li>
 *   <li><b>热门榜</b>：{@link #addHotScore} / {@link #topHot}（ZSet）。</li>
 * </ul>
 *
 * <p>所有方法在 {@code campus.cache.enabled=false} 时都要退化为"什么都不做"，
 * 这样同一份代码既能在生产开启缓存，也能在压测时一键关闭做对照实验。</p>
 */
public interface CacheService {

    /** 缓存总开关是否打开 */
    boolean enabled();

    /** Key 是否存在（含"空值缓存"标记） */
    boolean exists(String key);

    /** 读缓存；未命中或空值缓存返回 null */
    <T> T get(String key, Class<T> type);

    /** 写缓存（TTL 秒，自动加抖动） */
    void set(String key, Object value, long ttlSeconds);

    /** 写"空值缓存"（防缓存穿透） */
    void setNull(String key, long ttlSeconds);

    /** 删除指定 Key */
    void evict(String... keys);

    /** 按通配符批量删除（KEYS + DEL，仅供低频的失效场景使用） */
    long evictByPattern(String pattern);

    /** 计数器 +1 并设置过期时间，返回自增后的值（用于限流、浏览量） */
    long increment(String key, long ttlSeconds);

    /** 计数器 +N */
    long incrementBy(String key, long delta, long ttlSeconds);

    /** 读取计数器当前值（不存在返回 0） */
    long getCounter(String key);

    /** 重置计数器 */
    void resetCounter(String key);

    /** 写入一个标记位（值为 1），用于 Token 黑名单等场景 */
    void setFlag(String key, long ttlSeconds);

    /** 标记位是否存在 */
    boolean hasFlag(String key);

    /** 尝试获取分布式锁（SET NX EX），成功返回 true */
    boolean tryLock(String key, String value, long ttlSeconds);

    /** 释放锁（仅当值匹配时才删除，避免误删别人的锁） */
    void releaseLock(String key, String value);

    /** 热门榜加分 */
    void addHotScore(String hotKey, Long productId, double score, long ttlDays);

    /** 取热门榜前 N 名商品ID（按分数从高到低） */
    List<Long> topHot(String hotKey, int size);

    /** 读取列表缓存（带泛型类型，供业务直接使用） */
    <T> T getList(String key, Class<T> elementType);
}
