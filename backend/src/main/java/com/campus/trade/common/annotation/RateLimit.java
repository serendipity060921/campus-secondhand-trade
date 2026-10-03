package com.campus.trade.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解式限流（v0.12）。
 *
 * <p>基于 Redis 计数器 + Lua 脚本实现"固定窗口限流"：
 * 在 {@code window} 秒内同一维度最多允许 {@code limit} 次调用，超出返回业务码 429。</p>
 *
 * <p>用法示例：</p>
 * <pre>
 * &#64;RateLimit(key = "login", limit = 10, window = 60, dimension = Dimension.IP, message = "登录过于频繁")
 * &#64;RateLimit(key = "send-msg", limit = 30, window = 60, dimension = Dimension.USER)
 * &#64;RateLimit(key = "register", limit = 5, window = 300, paramName = "username")
 * </pre>
 *
 * 说明：限流维度做成枚举而不是写死 IP，是为了覆盖不同攻击面 ——
 * 登录接口要防单 IP 撞库、发消息要防单用户刷屏、注册要防同一用户名被反复提交。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 业务标识，参与限流 Key 的构造（如 login / register / send-msg） */
    String key();

    /** 窗口内允许的最大次数 */
    int limit() default 30;

    /** 窗口大小（秒） */
    int window() default 60;

    /** 限流维度 */
    Dimension dimension() default Dimension.IP;

    /** 指定按方法参数值限流时的参数名（dimension = PARAM 时生效） */
    String paramName() default "";

    /** 触发限流时的提示语 */
    String message() default "操作过于频繁，请稍后再试";

    /** 限流维度 */
    enum Dimension {
        /** 按客户端 IP */
        IP,
        /** 按登录用户（未登录时退化为 IP） */
        USER,
        /** 按指定方法参数值（如注册时的 username） */
        PARAM
    }
}
