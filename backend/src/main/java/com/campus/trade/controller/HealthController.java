package com.campus.trade.controller;

import com.campus.trade.common.result.Result;
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.HealthVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 / 连通性自检接口。
 *
 * <p>脚手架阶段用于验证「前端 → 后端 → MySQL」整条链路是否打通：</p>
 * <ul>
 *   <li>GET /api/health     仅验证后端服务是否启动</li>
 *   <li>GET /api/health/db  验证 MySQL 连接与 MyBatis-Plus 是否正常</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserService userService;
    private final CategoryService categoryService;
    private final ProductService productService;

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${app.version:v0.03}")
    private String version;

    @Value("${spring.profiles.active:dev}")
    private String profile;

    /** 后端存活检查 */
    @GetMapping
    public Result<HealthVO> health() {
        HealthVO vo = HealthVO.builder()
                .application(applicationName)
                .version(version)
                .profile(profile)
                .status("UP")
                .serverTime(LocalDateTime.now().format(FORMATTER))
                .javaVersion(System.getProperty("java.version"))
                .build();
        return Result.success("后端服务运行正常", vo);
    }

    /** 数据库连通性检查：执行真实 count 查询 */
    @GetMapping("/db")
    public Result<Map<String, Object>> dbHealth() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", "campus_trade");
        data.put("driverCheck", "ok");
        data.put("userCount", userService.count());
        data.put("categoryCount", categoryService.count());
        data.put("productCount", productService.count());
        data.put("checkTime", LocalDateTime.now().format(FORMATTER));
        return Result.success("MySQL 连接正常", data);
    }
}
