package com.campus.trade.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 登录校验注解（v0.04）。
 *
 * <p>标注在 Controller 类或方法上，表示该接口必须携带有效 Token 才能访问：</p>
 * <pre>
 * // 方式一：标注在方法上，只保护该接口
 * {@code @LoginRequired}
 * {@code @GetMapping("/api/user/info")}
 * public Result&lt;UserVO&gt; info() { ... }
 *
 * // 方式二：标注在类上，保护该 Controller 的所有接口
 * {@code @LoginRequired}
 * {@code @RestController}
 * public class AdminController { ... }
 * </pre>
 *
 * <p>校验逻辑由 {@code LoginInterceptor} 实现；未标注该注解的接口默认放行（公开接口）。</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginRequired {

    /** 是否需要管理员角色（预留：后台管理接口使用） */
    boolean admin() default false;
}
