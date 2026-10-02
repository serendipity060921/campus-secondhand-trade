package com.campus.trade.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 跨域配置（CORS）。
 *
 * <p>前端 Vite 开发服务器（http://localhost:5173）与后端（http://localhost:8080）
 * 属于不同源，需要显式放开；生产环境同样通过该配置指定允许来源。</p>
 *
 * <p>说明：开发环境也可以只依赖 frontend/vite.config.js 中的 proxy 代理，
 * 两种方式本脚手架都已配置，任选其一即可。</p>
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final CorsProperties corsProperties;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许的前端来源（从配置文件读取，便于分环境管理）
        config.setAllowedOriginPatterns(corsProperties.getAllowedOrigins());
        // 允许的请求方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        // 允许的请求头（Authorization 用于后续 JWT）
        config.setAllowedHeaders(List.of("*"));
        // 允许前端读取的响应头
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        // 是否允许携带凭证
        config.setAllowCredentials(corsProperties.isAllowCredentials());
        // 预检请求缓存时间
        config.setMaxAge(corsProperties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
