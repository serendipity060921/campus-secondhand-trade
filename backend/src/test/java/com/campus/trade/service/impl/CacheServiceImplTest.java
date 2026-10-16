package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.campus.trade.config.CacheProperties;

/**
 * 缓存服务实现验证（v0.18 / 收尾⑦）
 *
 * <p>{@code CacheServiceImpl} 是"缓存能力"的唯一出口，18 个方法被整个系统共享。
 * 它最容易被改坏、后果最严重的不是某个具体方法，而是三条<b>全局语义</b>：</p>
 *
 * <ol>
 *   <li><b>总开关降级</b>：{@code campus.cache.enabled=false} 时所有方法都必须"什么都不做"，
 *       读方法返回 0/空、{@code hasFlag} 返回 false。这是压测做"开/关缓存对照实验"的前提 ——
 *       只要有一个方法漏了判断，对照实验的数据就不可信；</li>
 *   <li>★ <b>关闭缓存时 {@code tryLock} 返回 true</b>：不做互斥，等同于放行去直接查库。
 *       若返回 false，业务会以为"别人正在回源"而走等待分支，请求被无故拖慢甚至拿不到数据；</li>
 *   <li><b>Redis 异常一律降级</b>：任何方法抛异常都必须被吞掉、退化为直接查库，
 *       绝不能因为缓存故障把业务带崩。</li>
 * </ol>
 */
class CacheServiceImplTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> objectValues = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> stringValues = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);

    private CacheProperties properties;
    private CacheServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new CacheProperties();     // 真实配置：ttlJitter=0.2、nullValueTtl=30、enabled=true
        service = new CacheServiceImpl(redisTemplate, stringRedisTemplate, properties);
        when(redisTemplate.opsForValue()).thenReturn(objectValues);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValues);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
    }

    @Nested
    @DisplayName("一、总开关：关闭时必须完全退化为空实现")
    class Disabled {

        @BeforeEach
        void disable() {
            properties.setEnabled(false);
        }

        @Test
        @DisplayName("enabled() 跟随配置")
        void enabledFollowsProperties() {
            assertFalse(service.enabled());
            properties.setEnabled(true);
            assertTrue(service.enabled());
        }

        @Test
        @DisplayName("★ 写类方法一律不动 Redis（这是压测对照实验可信的前提）")
        void writeMethodsDoNothing() {
            // setUp 里为了打桩调用过 opsForValue()/opsForZSet()，那些调用也会被记录；
            // 这里先清空调用记录（桩仍然有效），确保下面 verify 只看被测方法产生的交互。
            org.mockito.Mockito.clearInvocations(redisTemplate, stringRedisTemplate);

            service.set("k", "v", 60);
            service.setNull("k", 30);
            service.evict("k1", "k2");
            service.resetCounter("k");
            service.setFlag("k", 60);
            service.releaseLock("k", "v");
            service.addHotScore("hot", 1L, 2.0, 3);

            verifyNoInteractions(redisTemplate);
            verify(stringRedisTemplate, never()).delete(anyString());
            verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any());
            verify(stringRedisTemplate, never()).opsForZSet();
        }

        @Test
        @DisplayName("读类方法返回 0 / 空，而不是抛异常")
        void readMethodsReturnDefaults() {
            assertEquals(0, service.evictByPattern("campus:*"));
            assertEquals(0, service.increment("k", 60));
            assertEquals(0, service.incrementBy("k", 5, 60));
            assertEquals(0, service.getCounter("k"));
            assertTrue(service.topHot("hot", 10).isEmpty());
            assertTrue(service.topWithScores("hot", 10).isEmpty());
        }

        @Test
        @DisplayName("hasFlag 返回 false")
        void hasFlagFalse() {
            assertFalse(service.hasFlag("k"));
        }

        @Test
        @DisplayName("★ tryLock 返回 true —— 不做互斥，等同于放行去直接查库")
        void tryLockReturnsTrue() {
            assertTrue(service.tryLock("k", "v", 10),
                    "关闭缓存时必须放行；返回 false 会让业务误以为别人在回源而走等待分支");
        }
    }

    @Nested
    @DisplayName("二、TTL 抖动（防雪崩）")
    class Jitter {

        @Test
        @DisplayName("★ 写入的过期时间落在 ±20% 区间内，而不是固定值")
        void ttlWithinJitterRange() {
            service.set("k", "v", 100);

            ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
            verify(objectValues).set(eq("k"), eq("v"), ttl.capture(), eq(TimeUnit.SECONDS));

            long actual = ttl.getValue();
            assertTrue(actual >= 80 && actual <= 120, "TTL 应在 100×[0.8,1.2] 内，实际 " + actual);
        }

        @Test
        @DisplayName("★ 多次写入的 TTL 不完全相同（否则同一批缓存在同一秒集体过期 → 雪崩）")
        void ttlIsRandomized() {
            Set<Long> seen = new LinkedHashSet<>();
            for (int i = 0; i < 30; i++) {
                service.set("k" + i, "v", 100);
            }
            ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
            verify(objectValues, times(30)).set(anyString(), any(), ttl.capture(), eq(TimeUnit.SECONDS));
            seen.addAll(ttl.getAllValues());

            assertTrue(seen.size() > 1, "30 次写入应出现多种 TTL，实际只有 " + seen);
        }

        @Test
        @DisplayName("抖动系数为 0 时用原值；TTL 为 0 时至少给 1 秒")
        void zeroJitterAndZeroTtl() {
            properties.setTtlJitter(0);

            service.set("k1", "v", 100);
            service.set("k2", "v", 0);

            ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
            verify(objectValues, times(2)).set(anyString(), any(), ttl.capture(), eq(TimeUnit.SECONDS));
            assertEquals(100L, ttl.getAllValues().get(0));
            assertEquals(1L, ttl.getAllValues().get(1), "TTL 不允许为 0（会被 Redis 拒绝）");
        }

        @Test
        @DisplayName("空值缓存用固定 TTL（它只是防穿透标记，不需要抖动）")
        void nullMarkUsesFixedTtl() {
            service.setNull("k", properties.getNullValueTtl());

            verify(stringValues).set(eq("k"), anyString(), eq(properties.getNullValueTtl()),
                    eq(TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("三、写与失效")
    class WriteAndEvict {

        @Test
        @DisplayName("set：值为 null 时不写（避免缓存里出现无意义空对象）")
        void setIgnoresNullValue() {
            service.set("k", null, 60);
            verifyNoInteractions(objectValues);
        }

        @Test
        @DisplayName("evict：逐个删除，且跳过 null；空参数不报错")
        void evictSkipsNullKeys() {
            service.evict("a", null, "b");
            verify(redisTemplate).delete("a");
            verify(redisTemplate).delete("b");
            verify(redisTemplate, never()).delete((String) null);

            service.evict();
            verify(redisTemplate, times(2)).delete(anyString());
        }

        @Test
        @DisplayName("evictByPattern：返回实际删除数量；没有匹配返回 0")
        void evictByPattern() {
            when(stringRedisTemplate.keys("campus:product:list:*")).thenReturn(Set.of("a", "b"));
            when(stringRedisTemplate.delete(any(Set.class))).thenReturn(2L);

            assertEquals(2, service.evictByPattern("campus:product:list:*"));

            when(stringRedisTemplate.keys("none:*")).thenReturn(Set.of());
            assertEquals(0, service.evictByPattern("none:*"));
        }

        @Test
        @DisplayName("resetCounter：直接删除计数键")
        void resetCounterDeletes() {
            service.resetCounter("campus:view:1");
            verify(stringRedisTemplate).delete("campus:view:1");
        }

        @Test
        @DisplayName("setFlag / hasFlag：标记位写入 1，TTL 至少 1 秒")
        void flagRoundTrip() {
            service.setFlag("campus:jwt:black:abc", 0);
            verify(stringValues).set(eq("campus:jwt:black:abc"), eq("1"), eq(1L), eq(TimeUnit.SECONDS));

            when(stringRedisTemplate.hasKey("k")).thenReturn(true);
            assertTrue(service.hasFlag("k"));
            when(stringRedisTemplate.hasKey("nope")).thenReturn(false);
            assertFalse(service.hasFlag("nope"));
        }
    }

    @Nested
    @DisplayName("四、计数器：首次创建才设过期")
    class Counters {

        @Test
        @DisplayName("★ 首次自增（返回值等于增量）时设置过期时间，避免计数器永久占内存")
        void firstIncrementSetsExpire() {
            when(stringValues.increment("campus:view:1", 1L)).thenReturn(1L);

            assertEquals(1, service.increment("campus:view:1", 3600));

            verify(stringRedisTemplate).expire("campus:view:1", 3600, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("★ 非首次自增不再重复设置过期（否则每次访问都刷新 TTL，计数器永不过期）")
        void subsequentIncrementDoesNotResetExpire() {
            when(stringValues.increment("campus:view:1", 1L)).thenReturn(7L);

            assertEquals(7, service.increment("campus:view:1", 3600));

            verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("incrementBy：按增量自增，返回值原样透出")
        void incrementByDelta() {
            when(stringValues.increment("k", 5L)).thenReturn(5L);

            assertEquals(5, service.incrementBy("k", 5, 60));
            verify(stringRedisTemplate).expire("k", 60, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("getCounter：空值返回 0；非数字脏数据也降级为 0 而不是抛异常")
        void getCounterTolerant() {
            when(stringValues.get("none")).thenReturn(null);
            assertEquals(0, service.getCounter("none"));

            when(stringValues.get("bad")).thenReturn("not-a-number");
            assertEquals(0, service.getCounter("bad"));

            when(stringValues.get("ok")).thenReturn("42");
            assertEquals(42, service.getCounter("ok"));
        }
    }

    @Nested
    @DisplayName("五、分布式锁：只删自己的锁")
    class Locks {

        @Test
        @DisplayName("tryLock：setIfAbsent 成功返回 true，失败返回 false")
        void tryLockUsesSetIfAbsent() {
            when(stringValues.setIfAbsent("lock", "v1", 10L, TimeUnit.SECONDS)).thenReturn(true);
            assertTrue(service.tryLock("lock", "v1", 10));

            when(stringValues.setIfAbsent("lock", "v2", 10L, TimeUnit.SECONDS)).thenReturn(false);
            assertFalse(service.tryLock("lock", "v2", 10));
        }

        @Test
        @DisplayName("★ 解锁走 Lua 脚本并把「自己的值」传进去 —— 只有值匹配才会删，避免误删别人的锁")
        void releaseLockIsValueAware() {
            service.releaseLock("lock", "my-uuid");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
            verify(stringRedisTemplate).execute(script.capture(), eq(List.of("lock")), eq("my-uuid"));

            String lua = script.getValue().getScriptAsString();
            assertTrue(lua.contains("get") && lua.contains("del"),
                    "解锁脚本应先比较值再删除：" + lua);
        }
    }

    @Nested
    @DisplayName("六、热门榜（ZSet）")
    class HotList {

        @Test
        @DisplayName("addHotScore：累加分值并设置天数过期；商品 ID 为空时不写")
        void addScore() {
            service.addHotScore("campus:hot:20261016", 7L, 3.0, 3);

            verify(zSetOps).incrementScore("campus:hot:20261016", "7", 3.0);
            verify(stringRedisTemplate).expire("campus:hot:20261016", 3, TimeUnit.DAYS);

            service.addHotScore("campus:hot:20261016", null, 3.0, 3);
            verify(zSetOps, times(1)).incrementScore(anyString(), anyString(), anyDouble());
        }

        @Test
        @DisplayName("★ topHot：按分数倒序取前 N 个；脏数据（非数字成员）被忽略而不是抛异常")
        void topHotIgnoresDirtyMembers() {
            when(zSetOps.reverseRange("hot", 0, 2)).thenReturn(new LinkedHashSet<>(List.of("7", "oops", "9")));

            List<Long> ids = service.topHot("hot", 3);

            assertEquals(List.of(7L, 9L), ids, "脏成员应被跳过，其余顺序保持不变");
            verify(zSetOps).reverseRange("hot", 0, 2);
        }

        @Test
        @DisplayName("topWithScores：带分数读取，供业务判断热门榜成员")
        void topWithScores() {
            Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
            when(zSetOps.reverseRangeWithScores("hot", 0, 4)).thenReturn(tuples);

            assertNotNull(service.topWithScores("hot", 5));
            verify(zSetOps).reverseRangeWithScores("hot", 0, 4);
        }
    }

    @Nested
    @DisplayName("七、★ Redis 故障一律降级，绝不把业务带崩")
    class FailSafe {

        private void redisDown() {
            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
            when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
            when(stringRedisTemplate.opsForZSet()).thenThrow(new RuntimeException("redis down"));
        }

        @Test
        @DisplayName("写方法：异常被吞掉，调用方无感")
        void writeMethodsSwallowException() {
            redisDown();

            service.set("k", "v", 60);
            service.setNull("k", 30);
            service.evict("k");
            service.resetCounter("k");
            service.setFlag("k", 60);
            service.releaseLock("k", "v");
            service.addHotScore("hot", 1L, 1.0, 1);
            // 走到这里没抛异常即通过
            assertTrue(true);
        }

        @Test
        @DisplayName("读方法：异常时返回安全默认值（0 / 空）")
        void readMethodsReturnSafeDefaults() {
            redisDown();

            assertEquals(0, service.getCounter("k"));
            assertEquals(0, service.increment("k", 60));
            assertFalse(service.hasFlag("k"));
            assertTrue(service.topHot("hot", 5).isEmpty());
        }

        @Test
        @DisplayName("★ tryLock：Redis 异常时返回 true（放弃互斥，放行查库而不是拒绝服务）")
        void tryLockFailsOpen() {
            redisDown();

            assertTrue(service.tryLock("k", "v", 10),
                    "锁服务不可用时必须放行 —— 否则所有详情请求都会失败");
        }

        @Test
        @DisplayName("evictByPattern 内部抛异常时返回 0")
        void evictByPatternSwallows() {
            when(stringRedisTemplate.keys(anyString())).thenThrow(new RuntimeException("boom"));

            assertEquals(0, service.evictByPattern("campus:*"));
        }
    }
}
