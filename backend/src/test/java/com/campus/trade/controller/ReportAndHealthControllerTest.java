package com.campus.trade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.GlobalExceptionHandler;
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.ReportModuleService;
import com.campus.trade.service.UserService;

/**
 * 举报与健康检查接口层验证（v0.18 / 第3批-⑪）
 *
 * <p>举报接口的价值在于"举报人能查到自己的举报进度"，因此<b>举报人只能取自 Token</b>；
 * 健康检查则是运维与部署验证脚本依赖的探针（D 系列用例会打它），
 * 必须做到<b>匿名可访问且不抛异常</b>。</p>
 */
class ReportAndHealthControllerTest {

    private ReportModuleService reportModuleService;
    private MockMvc mvc;

    private static final Long UID = 11L;

    @BeforeEach
    void setUp() {
        reportModuleService = mock(ReportModuleService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ReportController(reportModuleService),
                        new HealthController(mock(UserService.class), mock(CategoryService.class),
                                mock(ProductService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Nested
    @DisplayName("一、举报：举报人只能来自 Token")
    class Report {

        @Test
        @DisplayName("★ 未登录举报 → 401，不会写入举报记录")
        void submitNeedsLogin() throws Exception {
            mvc.perform(post("/api/report/submit").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetType\":1,\"targetId\":5,\"reasonType\":1}"))
                    .andExpect(jsonPath("$.code").value(401));

            verify(reportModuleService, never()).submit(any(), anyLong());
        }

        @Test
        @DisplayName("★ 提交举报：举报人取自上下文，提示明确")
        void submit() throws Exception {
            UserContext.set(new LoginUser(UID, "stu_demo", 0));

            mvc.perform(post("/api/report/submit").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetType\":1,\"targetId\":5,\"reasonType\":1,\"content\":\"涉嫌虚假\"}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("举报已提交，管理员会尽快处理"));

            verify(reportModuleService).submit(any(), eq(UID));
        }

        @Test
        @DisplayName("我的举报记录：只查自己的，分页透传")
        void mine() throws Exception {
            UserContext.set(new LoginUser(UID, "stu_demo", 0));

            mvc.perform(get("/api/report/mine").param("page", "1").param("size", "10"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(reportModuleService).myReports(eq(1), eq(10), eq(UID));
        }

        @Test
        @DisplayName("未登录看我的举报 → 401")
        void mineNeedsLogin() throws Exception {
            mvc.perform(get("/api/report/mine")).andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("二、健康检查：匿名可访问（部署验证脚本依赖）")
    class Health {

        @Test
        @DisplayName("★ 服务健康检查 → 200，且带统一结构（D 系列用例会断言它）")
        void health() throws Exception {
            mvc.perform(get("/api/health"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("★ 数据库详情探针在关闭时返回 403 + 可读原因（生产默认关闭，避免泄露库内统计）")
        void dbHealth() throws Exception {
            mvc.perform(get("/api/health/db"))
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.message").exists());
        }
    }
}
