package com.campus.trade.config;

import com.campus.trade.interceptor.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（v0.04）：注册登录校验拦截器。
 *
 * <p>拦截范围是所有 <code>/api/**</code> 接口；具体放行与否由
 * {@code @LoginRequired} 注解决定：</p>
 * <ul>
 *   <li>标注了 {@code @LoginRequired} 的接口 → 必须携带有效 Token；</li>
 *   <li>未标注的接口（注册、登录、商品列表等）→ 公开访问。</li>
 * </ul>
 *
 * <p>这样做的好处：不需要在配置里维护一长串白名单，新增公开接口时也不会漏配。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .order(1);
    }
}
