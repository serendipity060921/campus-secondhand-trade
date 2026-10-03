package com.campus.trade.common.aspect;

import com.campus.trade.common.annotation.RateLimit;
import com.campus.trade.common.cache.CacheKeys;
import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.BusinessException;
import com.campus.trade.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 注解式限流切面（v0.12）。
 *
 * <p><b>为什么用 Lua 脚本</b>：限流要"自增 + 首次设置过期时间"两个动作原子完成，
 * 否则并发下可能出现"自增成功但过期没设上"导致计数器永不过期（用户被永久限流）。
 * 用 Lua 让 Redis 单线程内一次执行完，天然原子。</p>
 *
 * <pre>
 *   local current = redis.call('incr', KEYS[1])
 *   if tonumber(current) == 1 then redis.call('expire', KEYS[1], ARGV[1]) end
 *   return current
 * </pre>
 *
 * <p><b>降级策略</b>：Redis 不可用时不做限流（放行），保证可用性优先于限流 ——
 * 这是限流组件常见的取舍：宁可短时限流失效，也不能因为 Redis 故障把所有请求拒掉。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /** 固定窗口计数脚本：返回当前窗口内的调用次数 */
    private static final String LIMIT_SCRIPT =
            "local current = redis.call('incr', KEYS[1]) "
                    + "if tonumber(current) == 1 then redis.call('expire', KEYS[1], ARGV[1]) end "
                    + "return current";

    private final StringRedisTemplate stringRedisTemplate;
    private final CacheKeys cacheKeys;
    private final com.campus.trade.config.CacheProperties cacheProperties;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String ip = clientIp();
        // 开发/压测友好：本机回环地址默认跳过限流（线上经代理后拿到的是真实客户端 IP）
        if (cacheProperties.isRateLimitSkipLocal() && isLocal(ip)) {
            return joinPoint.proceed();
        }
        String dimension = resolveDimension(joinPoint, rateLimit, ip);
        String key = cacheKeys.rateLimit(rateLimit.key(), dimension);
        try {
            Long current = stringRedisTemplate.execute(
                    new DefaultRedisScript<>(LIMIT_SCRIPT, Long.class),
                    List.of(key), String.valueOf(rateLimit.window()));
            if (current != null && current > rateLimit.limit()) {
                log.warn("[限流] key={} 维度={} 窗口={}s 上限={} 当前={}",
                        rateLimit.key(), dimension, rateLimit.window(), rateLimit.limit(), current);
                throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(),
                        rateLimit.message() + "（" + rateLimit.window() + " 秒内最多 "
                                + rateLimit.limit() + " 次）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis 异常 → 放弃限流，放行请求（可用性优先）
            log.warn("[限流降级] Redis 不可用，本次不做限流：{}", e.getMessage());
        }
        return joinPoint.proceed();
    }

    /** 解析限流维度：IP / 用户 / 指定参数值 */
    private String resolveDimension(ProceedingJoinPoint joinPoint, RateLimit rateLimit, String ip) {
        switch (rateLimit.dimension()) {
            case USER:
                LoginUser user = UserContext.get();
                return user != null ? "u" + user.userId() : "ip" + ip;
            case PARAM:
                String value = paramValue(joinPoint, rateLimit.paramName());
                return "p" + (value == null ? ip : value);
            case IP:
            default:
                return "ip" + ip;
        }
    }

    /** 是否为本机回环地址 */
    private boolean isLocal(String ip) {
        return ip == null || ip.equals("unknown")
                || ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1")
                || ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.");
    }

    /** 取出指定名称的方法参数值 */
    private String paramValue(ProceedingJoinPoint joinPoint, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (names == null) {
            return null;
        }
        for (int i = 0; i < names.length; i++) {
            if (name.equals(names[i]) && args[i] != null) {
                // 支持直接传字符串，或传 DTO 时反射取同名 getter
                if (args[i] instanceof String s) {
                    return s;
                }
                try {
                    Object v = args[i].getClass().getMethod("get" + Character.toUpperCase(name.charAt(0))
                            + name.substring(1)).invoke(args[i]);
                    return v == null ? null : String.valueOf(v);
                } catch (Exception ignored) {
                    return String.valueOf(args[i]);
                }
            }
        }
        return null;
    }

    /** 客户端 IP（兼容反向代理的 X-Forwarded-For） */
    private String clientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "unknown";
            }
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real;
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
