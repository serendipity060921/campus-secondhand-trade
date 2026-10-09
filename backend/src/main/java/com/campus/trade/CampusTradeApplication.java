package com.campus.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 校园二手交易平台 - 后端启动类
 *
 * <p>版本号统一来自配置项 {@code app.version}（与 /api/health 返回的版本一致），
 * 不再在代码里硬编码，避免出现"启动横幅写着旧版本、健康接口写着新版本"的不一致。</p>
 *
 * @author 毕业设计
 */
@SpringBootApplication
@MapperScan("com.campus.trade.mapper")
public class CampusTradeApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(CampusTradeApplication.class, args);
        // 版本从配置读取（与 HealthController 用的是同一个 app.version），保证两处永远一致
        String version = context.getEnvironment().getProperty("app.version", "unknown");
        // 说明：这里刻意使用英文，避免部分 Windows 控制台（GBK）下中文乱码
        System.out.printf("""

                ============================================================
                  Campus Secondhand Trade - Backend started successfully
                  Health check : http://localhost:8080/api/health
                  Version      : %s
                ============================================================
                """, version);
    }
}
