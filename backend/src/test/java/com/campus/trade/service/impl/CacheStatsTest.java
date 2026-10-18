package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.campus.trade.config.CacheProperties;

/**
 * 缓存命中率监控验证（v0.18 / 第4批-⑯）
 *
 * <p>"开了缓存"不等于"缓存有效"——命中率必须能被测出来，否则压测报告里的
 * "缓存让 QPS 提升 N 倍"就没有归因依据。这里固定三个口径问题：</p>
 * <ul>
 *   <li>★ <b>命中 / 未命中的判定</b>：Redis 有值且反序列化成功才算命中；
 *       命中"空值缓存"（防穿透的 NULL 标记）算<b>未命中业务数据</b>；</li>
 *   <li>★ <b>Redis 异常单独计数</b>，不计入命中率分母 —— 把故障算成"未命中"
 *       会拉低命中率，让人误以为"缓存没配好"，实际是 Redis 挂了；</li>
 *   <li>★ <b>关闭缓存时完全不计数</b>，否则做"开/关缓存对照实验"时，
 *       关闭组的计数会混进开启组，导致结论不可信。</li>
 * </ul>
 */
class CacheStatsTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> objectValues = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> stringValues = mock(ValueOperations.class);

    private final CacheProperties properties = new CacheProperties();
    private CacheServiceImpl service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(redisTemplate.opsForValue()).thenReturn(objectValues);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValues);
        service = new CacheServiceImpl(redisTemplate, stringRedisTemplate, properties);
        service.resetStats();
    }

    @Nested
    @DisplayName("一、★ 命中与未命中的判定")
    class Counting {

        @Test
        @DisplayName("有值且能反序列化 → 记一次命中，命中率 100%")
        void hit() {
            when(stringValues.get("k")).thenReturn("{\"id\":1}");
            when(objectValues.get("k")).thenReturn("SOME_OBJECT");

            assertEquals("SOME_OBJECT", service.get("k", String.class));

            Map<String, Object> s = service.stats();
            assertEquals(1L, s.get("hit"));
            assertEquals(0L, s.get("miss"));
            assertEquals(100.0, s.get("hitRate"));
        }

        @Test
        @DisplayName("key 不存在 → 记一次未命中，命中率 0%")
        void miss() {
            when(stringValues.get("k")).thenReturn(null);

            assertNull(service.get("k", String.class));

            Map<String, Object> s = service.stats();
            assertEquals(0L, s.get("hit"));
            assertEquals(1L, s.get("miss"));
            assertEquals(0.0, s.get("hitRate"));
        }

        @Test
        @DisplayName("★ 命中「空值缓存」（防穿透的 NULL 标记）算未命中业务数据")
        void nullMarkCountsAsMiss() {
            // NULL_MARK 的具体字面量不重要：只要 raw 非空但反序列化不到对象，就应记 miss
            when(stringValues.get("k")).thenReturn("__NULL__");
            when(objectValues.get("k")).thenReturn(null);

            assertNull(service.get("k", String.class));

            Map<String, Object> s = service.stats();
            assertEquals(1L, s.get("miss"));
            assertEquals(0L, s.get("hit"));
        }

        @Test
        @DisplayName("★ 命中率按 hit/(hit+miss) 计算：3 命中 1 未命中 = 75%")
        void hitRate() {
            when(stringValues.get("hit")).thenReturn("v");
            when(objectValues.get("hit")).thenReturn("OBJ");
            when(stringValues.get("miss")).thenReturn(null);

            service.get("hit", String.class);
            service.get("hit", String.class);
            service.get("hit", String.class);
            service.get("miss", String.class);

            Map<String, Object> s = service.stats();
            assertEquals(3L, s.get("hit"));
            assertEquals(1L, s.get("miss"));
            assertEquals(4L, s.get("total"));
            assertEquals(75.0, s.get("hitRate"));
        }
    }

    @Nested
    @DisplayName("二、★ 异常与关闭缓存时的口径")
    class EdgeCases {

        @Test
        @DisplayName("★ Redis 抛异常 → 记到 error，不计入命中率分母")
        void errorNotCountedAsMiss() {
            when(stringValues.get("k")).thenThrow(new RuntimeException("Redis 挂了"));

            assertNull(service.get("k", String.class));

            Map<String, Object> s = service.stats();
            assertEquals(1L, s.get("error"));
            assertEquals(0L, s.get("miss"), "故障不能算未命中");
            assertEquals(0L, s.get("total"));
            assertEquals(0.0, s.get("hitRate"));
        }

        @Test
        @DisplayName("★ 关闭缓存时完全不计数（否则开/关对照实验的数据会被污染）")
        void disabledDoesNotCount() {
            properties.setEnabled(false);

            assertNull(service.get("k", String.class));

            Map<String, Object> s = service.stats();
            assertEquals(0L, s.get("hit"));
            assertEquals(0L, s.get("miss"));
            assertEquals(0L, s.get("error"));
            assertEquals(0L, s.get("total"));
        }

        @Test
        @DisplayName("resetStats 后计数清零（压测前调用）")
        void reset() {
            when(stringValues.get("k")).thenReturn("v");
            when(objectValues.get("k")).thenReturn("OBJ");
            service.get("k", String.class);
            assertNotNull(service.stats().get("hit"));

            service.resetStats();

            Map<String, Object> s = service.stats();
            assertEquals(0L, s.get("hit"));
            assertEquals(0L, s.get("total"));
        }

        @Test
        @DisplayName("getList 委托 get，只计一次（不能重复计数）")
        void getListDelegatesOnce() {
            when(stringValues.get("k")).thenReturn("v");
            when(objectValues.get("k")).thenReturn("OBJ");

            service.getList("k", String.class);

            Map<String, Object> s = service.stats();
            assertEquals(1L, s.get("hit"), "一次调用只能记一次命中");
        }
    }
}
