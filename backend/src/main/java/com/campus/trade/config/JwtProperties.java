package com.campus.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性（v0.04）。
 *
 * <p>字段自带默认值，因此<b>不修改</b> application.yml 也能直接运行；</p>
 * <p>如需覆盖，可在 application-dev.yml 中追加（或使用环境变量 APP_JWT_SECRET 等）：</p>
 * <pre>
 * app:
 *   jwt:
 *     secret: 你的密钥（HS256 要求至少 32 个字符）
 *     expire-seconds: 7200          # Token 有效期（秒），默认 2 小时
 *     header: Authorization         # 请求头名称
 *     prefix: "Bearer "             # 请求头前缀
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /**
     * 签名密钥。HS256 算法要求密钥长度不少于 32 字节（256 位）。
     * 生产环境务必替换为自己的随机密钥（可通过环境变量 APP_JWT_SECRET 注入）。
     */
    private String secret = "campus-secondhand-trade-jwt-secret-key-2026-graduation-project";

    /** Token 有效期（秒），默认 7200 秒 = 2 小时 */
    private long expireSeconds = 7200L;

    /** 存放 Token 的请求头名称 */
    private String header = "Authorization";

    /** Token 前缀（注意结尾有空格） */
    private String prefix = "Bearer ";

    /** 签发者 */
    private String issuer = "campus-trade";
}
