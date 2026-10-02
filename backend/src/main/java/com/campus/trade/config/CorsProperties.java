package com.campus.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域配置属性，对应 application-dev.yml 中的 app.cors.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** 允许跨域访问的前端来源 */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 是否允许携带 Cookie / 认证信息 */
    private boolean allowCredentials = true;

    /** 预检请求缓存时间（秒） */
    private long maxAge = 3600L;
}
