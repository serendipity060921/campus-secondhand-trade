package com.campus.trade.service.impl;

import com.campus.trade.config.CacheProperties;
import com.campus.trade.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务实现（v0.12）。
 *
 * <p>工程上做了四件"防坑"的事，也是论文缓存章节要写的点：</p>
 * <ol>
 *   <li><b>防缓存穿透</b>：查不到的数据写一个短 TTL 的空值标记（{@link #NULL_MARK}），
 *       避免恶意/无效 ID 每次都打到数据库；</li>
 *   <li><b>防缓存雪崩</b>：TTL 统一乘以 [1-jitter, 1+jitter] 的随机系数，
 *       避免大批 Key 在同一秒集体失效；</li>
 *   <li><b>防缓存击穿</b>：热点 Key 回源时用 {@link #tryLock}（SET NX EX）只放行一个线程查库，
 *       其余线程短暂等待后重试读缓存；</li>
 *   <li><b>降级容错</b>：任何 Redis 异常都吞掉并记 warn 日志，缓存不可用时业务自动退化为查库，
 *       不能让"缓存挂了"变成"网站挂了"。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {

    /** 空值标记：与真实数据区分，读到它视为"查过了，确实没有" */
    private static final String NULL_MARK = "__NULL__";
    /** 分布式锁的安全释放脚本：值匹配才删除 */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheProperties properties;

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public boolean exists(String key) {
        if (!enabled()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            warn("exists", key, e);
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        if (!enabled()) {
            return null;
        }
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            if (raw == null || NULL_MARK.equals(raw)) {
                return null;      // 未命中，或命中"空值缓存"
            }
            Object value = redisTemplate.opsForValue().get(key);
            return (T) value;
        } catch (Exception e) {
            warn("get", key, e);
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getList(String key, Class<T> elementType) {
        return (T) get(key, Object.class);
    }

    @Override
    public void set(String key, Object value, long ttlSeconds) {
        if (!enabled() || value == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, jitter(ttlSeconds), TimeUnit.SECONDS);
        } catch (Exception e) {
            warn("set", key, e);
        }
    }

    @Override
    public void setNull(String key, long ttlSeconds) {
        if (!enabled()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, NULL_MARK, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            warn("setNull", key, e);
        }
    }

    @Override
    public void evict(String... keys) {
        if (!enabled() || keys == null || keys.length == 0) {
            return;
        }
        try {
            for (String key : keys) {
                if (key != null) {
                    redisTemplate.delete(key);
                }
            }
        } catch (Exception e) {
            warn("evict", String.join(",", keys), e);
        }
    }

    @Override
    public long evictByPattern(String pattern) {
        if (!enabled()) {
            return 0;
        }
        try {
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                return 0;
            }
            Long removed = stringRedisTemplate.delete(keys);
            log.debug("[缓存失效] pattern={} 删除 {} 个 Key", pattern, removed);
            return removed == null ? 0 : removed;
        } catch (Exception e) {
            warn("evictByPattern", pattern, e);
            return 0;
        }
    }

    @Override
    public long increment(String key, long ttlSeconds) {
        return incrementBy(key, 1, ttlSeconds);
    }

    @Override
    public long incrementBy(String key, long delta, long ttlSeconds) {
        if (!enabled()) {
            return 0;
        }
        try {
            Long value = stringRedisTemplate.opsForValue().increment(key, delta);
            if (value != null && value == delta) {
                // 第一次创建该 Key 时设置过期时间（计数器不设过期会一直占内存）
                stringRedisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
            }
            return value == null ? 0 : value;
        } catch (Exception e) {
            warn("increment", key, e);
            return 0;
        }
    }

    @Override
    public long getCounter(String key) {
        if (!enabled()) {
            return 0;
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            warn("getCounter", key, e);
            return 0;
        }
    }

    @Override
    public void resetCounter(String key) {
        if (!enabled()) {
            return;
        }
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            warn("resetCounter", key, e);
        }
    }

    @Override
    public void setFlag(String key, long ttlSeconds) {
        if (!enabled()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, "1", Math.max(1, ttlSeconds), TimeUnit.SECONDS);
        } catch (Exception e) {
            warn("setFlag", key, e);
        }
    }

    @Override
    public boolean hasFlag(String key) {
        if (!enabled()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            warn("hasFlag", key, e);
            return false;
        }
    }

    @Override
    public boolean tryLock(String key, String value, long ttlSeconds) {
        if (!enabled()) {
            return true;      // 关闭缓存时不做互斥（等同于直接查库）
        }
        try {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            warn("tryLock", key, e);
            return true;
        }
    }

    @Override
    public void releaseLock(String key, String value) {
        if (!enabled()) {
            return;
        }
        try {
            stringRedisTemplate.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class),
                    List.of(key), value);
        } catch (Exception e) {
            warn("releaseLock", key, e);
        }
    }

    @Override
    public void addHotScore(String hotKey, Long productId, double score, long ttlDays) {
        if (!enabled() || productId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForZSet().incrementScore(hotKey, String.valueOf(productId), score);
            stringRedisTemplate.expire(hotKey, ttlDays, TimeUnit.DAYS);
        } catch (Exception e) {
            warn("addHotScore", hotKey, e);
        }
    }

    @Override
    public List<Long> topHot(String hotKey, int size) {
        List<Long> ids = new ArrayList<>();
        if (!enabled()) {
            return ids;
        }
        try {
            Set<String> members = stringRedisTemplate.opsForZSet()
                    .reverseRange(hotKey, 0, Math.max(0, size - 1L));
            if (members != null) {
                for (String m : members) {
                    try {
                        ids.add(Long.parseLong(m));
                    } catch (NumberFormatException ignored) {
                        // 忽略脏数据
                    }
                }
            }
        } catch (Exception e) {
            warn("topHot", hotKey, e);
        }
        return ids;
    }

    /** TTL 加随机抖动，防雪崩 */
    private long jitter(long ttlSeconds) {
        double jitter = Math.max(0, properties.getTtlJitter());
        if (jitter <= 0 || ttlSeconds <= 0) {
            return Math.max(1, ttlSeconds);
        }
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return Math.max(1, (long) (ttlSeconds * factor));
    }

    private void warn(String op, String key, Exception e) {
        log.warn("[缓存降级] {} key={} 失败，已退化为直接查库：{}", op, key, e.getMessage());
    }

    /** 供业务判断是否为热门榜成员集合（ZSet 分数读取） */
    public Set<ZSetOperations.TypedTuple<String>> topWithScores(String hotKey, int size) {
        if (!enabled()) {
            return Set.of();
        }
        try {
            return stringRedisTemplate.opsForZSet().reverseRangeWithScores(hotKey, 0, Math.max(0, size - 1L));
        } catch (Exception e) {
            warn("topWithScores", hotKey, e);
            return Set.of();
        }
    }
}
