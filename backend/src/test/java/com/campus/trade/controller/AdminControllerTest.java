package com.campus.trade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.campus.trade.service.AdminService;

/**
 * 管理后台接口层验证（v0.18 / 第3批-⑪）
 *
 * <p>管理端接口在<b>部署形态</b>下由 <code>@LoginRequired(admin = true)</code> 拦截器
 * 把关（普通用户 403，见安全测试 S26~S28）。而接口层自己的职责是：
 * **操作人 ID 只能取自上下文**、每个写操作都要把"谁操作的"传给服务层写审计日志。</p>
 *
 * <p>这里用 standalone MockMvc（不挂拦截器），因此手工写入 <code>UserContext</code>，
 * 重点验证"操作人透传"与"统一响应结构"，鉴权本身由 LoginInterceptorTest 与安全套件覆盖。</p>
 */
class AdminControllerTest {

    private AdminService adminService;
    private MockMvc mvc;

    private static final Long ADMIN_ID = 1L;

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new AdminController(adminService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAsAdmin() {
        UserContext.set(new LoginUser(ADMIN_ID, "admin", 1));
    }

    @Nested
    @DisplayName("一、读接口：直接透传服务层结果")
    class Reads {

        @Test
        @DisplayName("看板：200 + 统一结构")
        void dashboard() throws Exception {
            loginAsAdmin();

            mvc.perform(get("/api/admin/dashboard"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(adminService).dashboard();
        }

        @Test
        @DisplayName("商品列表（可按状态筛待审核）")
        void productList() throws Exception {
            loginAsAdmin();

            mvc.perform(get("/api/admin/product/list").param("status", "0")
                            .param("page", "1").param("size", "20"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(adminService).productList(any());
        }

        @Test
        @DisplayName("用户列表")
        void userList() throws Exception {
            loginAsAdmin();

            mvc.perform(get("/api/admin/user/list")).andExpect(jsonPath("$.code").value(200));

            verify(adminService).userList(any());
        }

        @Test
        @DisplayName("举报列表：状态与分页透传")
        void reportList() throws Exception {
            loginAsAdmin();

            mvc.perform(get("/api/admin/report/list").param("status", "0")
                            .param("page", "1").param("size", "20"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(adminService).reportList(eq(0), eq(1), eq(20));
        }

        @Test
        @DisplayName("审计日志列表")
        void logList() throws Exception {
            loginAsAdmin();

            mvc.perform(get("/api/admin/log/list").param("page", "1").param("size", "20"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(adminService).logList(eq(1), eq(20));
        }
    }

    @Nested
    @DisplayName("二、★ 写接口：操作人必须取自上下文（审计日志靠它追溯）")
    class Writes {

        @Test
        @DisplayName("商品审核：操作人是当前管理员，提示「审核完成」（AuditProductDTO 字段是 approve）")
        void audit() throws Exception {
            loginAsAdmin();

            mvc.perform(put("/api/admin/product/audit").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":5,\"approve\":true}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("审核完成"));

            verify(adminService).auditProduct(any(), eq(ADMIN_ID));
        }

        @Test
        @DisplayName("强制下架：productId/reason 是【查询参数】（不是 JSON body），管理员 ID 取自上下文")
        void offline() throws Exception {
            loginAsAdmin();

            mvc.perform(put("/api/admin/product/offline")
                            .param("productId", "5").param("reason", "违规商品"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("已下架"));

            verify(adminService).offlineProduct(eq(5L), eq(ADMIN_ID), anyString());
        }

        @Test
        @DisplayName("启用/禁用用户：操作人取自上下文")
        void changeUserStatus() throws Exception {
            loginAsAdmin();

            mvc.perform(put("/api/admin/user/status").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":9,\"status\":0}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("操作成功"));

            verify(adminService).changeUserStatus(any(), eq(ADMIN_ID));
        }

        @Test
        @DisplayName("处理举报：操作人取自上下文（ReportHandleDTO 的处理动作用 action）")
        void handleReport() throws Exception {
            loginAsAdmin();

            mvc.perform(put("/api/admin/report/handle").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reportId\":3,\"action\":1}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("处理完成"));

            verify(adminService).handleReport(any(), eq(ADMIN_ID));
        }
    }

    @Nested
    @DisplayName("三、说明")
    class Note {

        @Test
        @DisplayName("★ 未登录访问管理端返回 401/403 由 LoginInterceptor 负责，不在此类断言")
        void authIsInterceptorConcern() {
            // standaloneSetup 不装载拦截器，因此"未登录 → 401"必须在装了拦截器的场景里验证：
            //   · LoginInterceptorTest 覆盖「缺 Token/黑名单/角色不足」的分支；
            //   · 安全套件的 S26~S28 在真实部署形态上验证「普通用户访问管理端 → 403」。
            // 这里明确记录这一分工，避免以后有人误以为"控制器自己做了鉴权"。
            org.junit.jupiter.api.Assertions.assertTrue(true);
        }
    }
}
