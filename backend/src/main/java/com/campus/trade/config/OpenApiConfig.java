package com.campus.trade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * 接口文档配置（v0.18 / 收尾⑨-1）。
 *
 * <p>引入 springdoc 后，全部 Controller 会按"控制器即分组"自动生成 OpenAPI 3 文档，
 * 访问 <code>/swagger-ui.html</code> 可在线查看并试调；<code>/v3/api-docs</code> 是它的
 * JSON 描述，可直接导入 Postman / Apifox。</p>
 *
 * <p>这里只补全文档元信息（标题、版本、说明），接口本身无需逐个加注解 ——
 * 项目所有接口都返回统一响应结构 {@code {code, message, data, timestamp}}，
 * 这一点写在描述里，读者看文档时就能对上。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI campusTradeOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("校园二手交易平台 API")
                .version("v0.18")
                .description("""
                        基于 Spring Boot 3 + Vue 3 的校园二手交易平台后端接口。

                        **统一响应结构**：所有接口均返回
                        `{ "code": 200, "message": "success", "data": ..., "timestamp": 1730000000000 }`，
                        前端按 `code` 分支处理；HTTP 状态码统一为 200（除网关层错误）。

                        **鉴权**：需登录的接口在方法或类上标注 `@LoginRequired`，
                        请求头携带 `Authorization: Bearer <token>`；
                        公开接口采用"可选鉴权"（带 Token 时能识别身份，不带也能访问）。

                        **限流**：登录、发布、发消息等接口带 `@RateLimit`，
                        超限返回业务码 429。

                        **内容机审**：发布商品时先过机审（违禁词 → 拒绝；
                        联系方式引流/价格异常 → 转人工复核），结论落库可查。
                        """));
    }
}
