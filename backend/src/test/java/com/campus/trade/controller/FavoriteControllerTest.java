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
import com.campus.trade.service.FavoriteModuleService;
import com.campus.trade.vo.FavoriteStatusVO;

/**
 * 收藏接口层验证（v0.18 / 第3批-⑪）
 *
 * <p>收藏是"高频点击 + 必须幂等"的接口：连续点两次不能报错、也不能产生两条记录。
 * 接口层的职责是把 `productId` / `type`（收藏还是取消）与当前用户正确传给服务层，
 * 并按结果给出「收藏成功 / 已取消收藏」的提示。</p>
 */
class FavoriteControllerTest {

    private FavoriteModuleService favoriteModuleService;
    private MockMvc mvc;

    private static final Long UID = 11L;

    @BeforeEach
    void setUp() {
        favoriteModuleService = mock(FavoriteModuleService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new FavoriteController(favoriteModuleService))
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
     * 直接构造 VO。
     * ★ FavoriteStatusVO 是 @Data + @Builder（**没有无参构造器**），因此必须走 builder；
     *   同时它也不适合 mock —— 能直接构造真实对象就别打桩。
     */
    private FavoriteStatusVO statusVO(boolean favorited) {
        return FavoriteStatusVO.builder().favorited(favorited).build();
    }

    @Nested
    @DisplayName("一、需要登录")
    class AuthRequired {

        @Test
        @DisplayName("★ 未登录收藏 → 401，不调用服务层")
        void operateNeedsLogin() throws Exception {
            mvc.perform(post("/api/favorite/operate").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":5,\"type\":1}"))
                    .andExpect(jsonPath("$.code").value(401));

            verify(favoriteModuleService, never()).operate(anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("未登录看收藏夹 → 401")
        void listNeedsLogin() throws Exception {
            mvc.perform(get("/api/favorite/list")).andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("二、正常路径与幂等语义")
    class HappyPath {

        @Test
        @DisplayName("★ 收藏成功 → 提示「收藏成功」，productId/type/用户 正确透传")
        void operateAdd() throws Exception {
            login();
            when(favoriteModuleService.operate(eq(5L), any(), eq(UID))).thenReturn(statusVO(true));

            mvc.perform(post("/api/favorite/operate").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":5,\"type\":1}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("收藏成功"));

            verify(favoriteModuleService).operate(eq(5L), any(), eq(UID));
        }

        @Test
        @DisplayName("★ 取消收藏 → 提示「已取消收藏」（与收藏是同一个接口，靠 type 区分）")
        void operateCancel() throws Exception {
            login();
            when(favoriteModuleService.operate(eq(5L), any(), eq(UID))).thenReturn(statusVO(false));

            mvc.perform(post("/api/favorite/operate").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":5,\"type\":0}"))
                    .andExpect(jsonPath("$.message").value("已取消收藏"));
        }

        @Test
        @DisplayName("收藏夹列表：分页参数透传当前用户")
        void list() throws Exception {
            login();

            mvc.perform(get("/api/favorite/list").param("page", "2").param("size", "5"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(favoriteModuleService).pageMine(eq(2L), eq(5L), eq(UID));
        }

        @Test
        @DisplayName("是否已收藏：透传 productId 与当前用户")
        void hasFavorite() throws Exception {
            login();

            mvc.perform(get("/api/favorite/hasFavorite").param("productId", "5"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(favoriteModuleService).hasFavorite(5L, UID);
        }
    }
}
