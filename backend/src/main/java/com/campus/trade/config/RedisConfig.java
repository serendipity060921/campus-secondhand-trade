package com.campus.trade.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置（v0.12）。
 *
 * <p>设计要点：</p>
 * <ol>
 *   <li><b>Key 用 String 序列化</b>：直接用 redis-cli 就能看懂缓存内容（如 {@code campus:product:detail:6}）；</li>
 *   <li><b>Value 用 JSON 序列化</b>（而非 JDK 序列化）：可读、跨语言、体积更小，
 *       并开启 {@code @class} 类型信息以便还原具体类型；</li>
 *   <li><b>类型白名单</b>：默认类型信息（default typing）在反序列化时存在安全风险，
 *       这里用 {@link BasicPolymorphicTypeValidator} 只放行本项目与 JDK 常用包，
 *       避免被构造恶意 @class 触发远程代码执行；</li>
 *   <li>支持 {@code LocalDateTime}（注册 JavaTimeModule 并关闭时间戳格式）。</li>
 * </ol>
 */
@Configuration
public class RedisConfig {

    /** 允许参与默认类型信息反序列化的包前缀（安全白名单） */
    private static final String[] ALLOWED_PACKAGES = {
            "com.campus.trade.", "java.util.", "java.math.", "java.time.", "java.lang."
    };

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        BasicPolymorphicTypeValidator.Builder builder = BasicPolymorphicTypeValidator.builder();
        for (String pkg : ALLOWED_PACKAGES) {
            builder.allowIfSubType(pkg);
        }
        PolymorphicTypeValidator validator = builder.build();
        mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
