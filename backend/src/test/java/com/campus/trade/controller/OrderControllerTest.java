package com.campus.trade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.campus.trade.common.exception.OrderException;
import com.campus.trade.service.OrderModuleService;
import com.campus.trade.vo.OrderDetailVO;

/**
 * 订单接口层验证（v0.18 / 第3批-⑪）
 *
 * <p>订单是"钱"相关的接口，接口层要守三件事：<b>全部都要登录</b>、
 * 买家身份只能来自 Token（前端传不了）、以及提示文案与状态语义一致
 * （完成是"交易已完成"、取消是"订单已取消" —— 这个分支写在控制器里，很容易写反）。</p>
 */
class OrderControllerTest {

    private OrderModuleService orderModuleService;
    private MockMvc mvc;

    private static final Long UID = 11L;

    @BeforeEach
    void setUp() {
        orderModuleService = mock(OrderModuleService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new OrderController(orderModuleService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new LoginUser(UID, "stu_demo", 0));
    }

    /**
     * 直接构造 VO（@Data 类有无参构造器）。
     * ★ 不用 mock：这些 VO 不可被 Mockito 代理，直接 new + set 更稳，也更贴近真实对象。
     */
    private OrderDetailVO voWithStatus(Integer status) {
        OrderDetailVO vo = new OrderDetailVO();
        vo.setStatus(status);
        return vo;
    }

    @Nested
    @DisplayName("一、全部接口都要登录")
    class AuthRequired {

        @Test
        @DisplayName("★ 未登录下单 → 401，不会创建任何订单")
        void createNeedsLogin() throws Exception {
            mvc.perform(post("/api/order/create").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":5}"))
                    .andExpect(jsonPath("$.code").value(401));

            verify(orderModuleService, never()).create(any(), anyLong());
        }

        @Test
        @DisplayName("未登录查看订单详情 → 401")
        void detailNeedsLogin() throws Exception {
            mvc.perform(get("/api/order/1")).andExpect(jsonPath("$.code").value(401));
        }

        @Test
        @DisplayName("未登录看我买到的 / 我卖出的 → 401")
        void listsNeedLogin() throws Exception {
            mvc.perform(get("/api/order/buyList")).andExpect(jsonPath("$.code").value(401));
            mvc.perform(get("/api/order/sellList")).andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("二、正常路径与身份来源")
    class HappyPath {

        @Test
        @DisplayName("★ 下单：买家身份取自上下文（前端无法指定买家）")
        void create() throws Exception {
            login();
            when(orderModuleService.create(any(), eq(UID))).thenReturn(voWithStatus(1));

            mvc.perform(post("/api/order/create").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":5}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("下单成功，请与卖家约定面交时间地点"));

            verify(orderModuleService).create(any(), eq(UID));
        }

        @Test
        @DisplayName("★ 订单状态 3（已完成）→ 提示「交易已完成」")
        void changeStatusFinished() throws Exception {
            login();
            when(orderModuleService.changeStatus(any(), eq(UID))).thenReturn(voWithStatus(3));

            mvc.perform(put("/api/order/status").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":9,\"status\":3}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("交易已完成"));
        }

        @Test
        @DisplayName("★ 订单状态 4（已取消）→ 提示「订单已取消」（分支不能写反）")
        void changeStatusCancelled() throws Exception {
            login();
            when(orderModuleService.changeStatus(any(), eq(UID))).thenReturn(voWithStatus(4));

            mvc.perform(put("/api/order/status").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":9,\"status\":4}"))
                    .andExpect(jsonPath("$.message").value("订单已取消"));
        }

        @Test
        @DisplayName("我买到的：状态筛选与分页参数正确透传")
        void buyList() throws Exception {
            login();

            mvc.perform(get("/api/order/buyList").param("status", "1")
                            .param("page", "2").param("size", "5"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(orderModuleService).pageBuyList(eq(UID), eq(1), eq(2L), eq(5L));
        }

        @Test
        @DisplayName("我卖出的：不传状态时按 null 透传（不加筛选）")
        void sellList() throws Exception {
            login();

            mvc.perform(get("/api/order/sellList")).andExpect(jsonPath("$.code").value(200));

            verify(orderModuleService).pageSellList(eq(UID), eq(null), eq(1L), eq(10L));
        }

        @Test
        @DisplayName("订单详情：路径变量与当前用户一起透传")
        void detail() throws Exception {
            login();

            mvc.perform(get("/api/order/77")).andExpect(jsonPath("$.code").value(200));

            verify(orderModuleService).detail(77L, UID);
        }
    }

    @Nested
    @DisplayName("三、校验与业务异常")
    class Validation {

        @Test
        @DisplayName("★ 分页参数越界 → 400（不查询）")
        void pageTooLarge() throws Exception {
            login();

            mvc.perform(get("/api/order/buyList").param("size", "99999"))
                    .andExpect(jsonPath("$.code").value(400));

            verify(orderModuleService, never()).pageBuyList(anyLong(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("★ 越权查看他人订单（6004）→ 统一结构返回该业务码")
        void detailForbiddenKeepsCode() throws Exception {
            login();
            when(orderModuleService.detail(anyLong(), anyLong())).thenThrow(OrderException.notFound());

            mvc.perform(get("/api/order/77"))
                    .andExpect(jsonPath("$.code").value(OrderException.notFound().getCode()));
        }
    }
}
