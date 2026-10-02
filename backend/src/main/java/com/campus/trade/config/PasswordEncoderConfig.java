package com.campus.trade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密配置（v0.04）。
 *
 * <p>使用 BCrypt 算法对密码做单向哈希：</p>
 * <ul>
 *   <li>同一个明文每次加密结果都不同（自动加盐），因此不能用等号比较，必须用 {@code matches()}</li>
 *   <li>数据库中只保存 <code>$2a$10$...</code> 形式的密文，绝不存在明文</li>
 * </ul>
 *
 * <p>注：只引入 spring-security-crypto 模块，不引入完整的 Spring Security 过滤器链，
 * 鉴权由本项目的 JWT 拦截器负责，保持脚手架结构不变。</p>
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 强度 10（默认值）：约 10 万次哈希迭代，安全性与性能兼顾
        return new BCryptPasswordEncoder();
    }
}
