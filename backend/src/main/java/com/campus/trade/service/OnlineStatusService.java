package com.campus.trade.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

/**
 * 在线状态服务（v0.14，基于 Redis ZSet）。
 *
 * <p><b>为什么要放 Redis，而不是只用内存里的会话表？</b></p>
 * <ol>
 *   <li>内存会话表（{@link com.campus.trade.websocket.ChatSessionRegistry}）只在本进程内有效，
 *       单机部署时它是"权威答案"；一旦将来多实例部署（负载均衡），
 *       用户 A 连在实例 1、用户 B 连在实例 2，内存表就无法互查 —— Redis 是共享的；</li>
 *   <li>心跳时间戳存 Redis 后，即使 TCP 连接"假死"（拔网线、进程被 kill），
 *       超过 TTL 的条目会自动被排除，不会出现"人已走但显示在线"；</li>
 *   <li>管理后台的"当前在线人数"可以直接读 Redis，不需要遍历所有连接。</li>
 * </ol>
 *
 * <p>数据结构：ZSet {@code campus:online:zset}，member = userId，score = 最近心跳的秒级时间戳。</p>
 * <ul>
 *   <li>上线/心跳：{@code ZADD}</li>
 *   <li>判断在线：{@code ZSCORE} 且分数在有效窗口内</li>
 *   <li>统计在线人数：先 {@code ZREMRANGEBYSCORE} 清掉过期成员，再 {@code ZCARD}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineStatusService {

    /** 在线判定窗口（秒）：超过该时间没有心跳即视为离线 */
    public static final long ONLINE_TTL_SECONDS = 90;

    private static final String ONLINE_ZSET_KEY = "campus:online:zset";
    /** 单用户在线标记 Key 前缀（带 TTL，便于直接查看"某人是否在线"） */
    private static final String ONLINE_FLAG_PREFIX = "campus:online:user:";

    private final StringRedisTemplate stringRedisTemplate;

    /** 标记在线 / 刷新心跳 */
    public void heartbeat(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis() / 1000;
            stringRedisTemplate.opsForZSet().add(ONLINE_ZSET_KEY, String.valueOf(userId), now);
            stringRedisTemplate.opsForValue().set(ONLINE_FLAG_PREFIX + userId, String.valueOf(now),
                    Duration.ofSeconds(ONLINE_TTL_SECONDS));
        } catch (Exception e) {
            // 在线状态属于旁路信息，Redis 故障时降级为"未知"，不影响聊天主流程
            log.warn("[在线状态] 心跳写入失败 userId={} 原因={}", userId, e.getMessage());
        }
    }

    /** 标记离线 */
    public void offline(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForZSet().remove(ONLINE_ZSET_KEY, String.valueOf(userId));
            stringRedisTemplate.delete(ONLINE_FLAG_PREFIX + userId);
        } catch (Exception e) {
            log.warn("[在线状态] 离线写入失败 userId={} 原因={}", userId, e.getMessage());
        }
    }

    /** 某人是否在线（依据最近心跳时间） */
    public boolean isOnline(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            Double score = stringRedisTemplate.opsForZSet().score(ONLINE_ZSET_KEY, String.valueOf(userId));
            return score != null && (System.currentTimeMillis() / 1000 - score) < ONLINE_TTL_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    /** 批量查询在线状态（用于会话列表一次性拿到所有对象的在线情况） */
    public Set<Long> filterOnline(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            // 用一次 ZRANGEBYSCORE 取回"在线窗口内的所有用户"，再与入参求交集。
            //
            // 为什么不用 ZMSCORE 批量取分？ZMSCORE 是 Redis 6.2 才引入的命令，
            // 本项目环境是 Redis 5.0（Windows 版），调用会返回 "unknown command"，
            // 而异常被兜底吞掉后会表现为"批量查询全部显示离线"这种极难发现的 bug
            // （v0.14 联调时真实踩到：REST 单查是在线，WS 批量查却全离线）。
            // ZRANGEBYSCORE 自 Redis 1.0 就有，兼容性最好。
            long deadline = System.currentTimeMillis() / 1000 - ONLINE_TTL_SECONDS;
            Set<String> onlineMembers = stringRedisTemplate.opsForZSet()
                    .rangeByScore(ONLINE_ZSET_KEY, deadline, Double.MAX_VALUE);
            if (onlineMembers == null || onlineMembers.isEmpty()) {
                return Collections.emptySet();
            }
            Set<Long> online = new HashSet<>();
            for (Long userId : userIds) {
                if (onlineMembers.contains(String.valueOf(userId))) {
                    online.add(userId);
                }
            }
            return online;
        } catch (Exception e) {
            log.warn("[在线状态] 批量查询失败：{}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /** 当前在线人数（先清理过期成员，统计才准确） */
    public long onlineCount() {
        try {
            long deadline = System.currentTimeMillis() / 1000 - ONLINE_TTL_SECONDS;
            stringRedisTemplate.opsForZSet().removeRangeByScore(ONLINE_ZSET_KEY, 0, deadline);
            Long count = stringRedisTemplate.opsForZSet().zCard(ONLINE_ZSET_KEY);
            return count == null ? 0 : count;
        } catch (Exception e) {
            return 0;
        }
    }
}
