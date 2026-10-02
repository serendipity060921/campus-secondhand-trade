package com.campus.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 校园二手交易平台 - 后端启动类
 *
 * <p>版本：v0.03  里程碑：搭建前后端分离项目脚手架</p>
 *
 * @author 毕业设计
 */
@SpringBootApplication
@MapperScan("com.campus.trade.mapper")
public class CampusTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusTradeApplication.class, args);
        // 说明：这里刻意使用英文，避免部分 Windows 控制台（GBK）下中文乱码
        System.out.println("""

                ============================================================
                  Campus Secondhand Trade - Backend started successfully
                  Health check : http://localhost:8080/api/health
                  Version      : v0.03 (front-end / back-end scaffold)
                ============================================================
                """);
    }
}
