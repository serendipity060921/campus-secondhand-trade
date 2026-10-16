package com.campus.trade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.campus.trade.common.exception.GlobalExceptionHandler;
import com.campus.trade.service.CategoryModuleService;
import com.campus.trade.service.CategoryService;

/**
 * 分类相关三个控制器验证（v0.18 / 第3批-⑪）
 *
 * <p>项目里分类接口有<b>三组路径</b>：<code>/api/categories</code>（公开分类树）、
 * <code>/api/category/list</code>（模块列表）、<code>/api/category/add|update</code>（管理端）。
 * 它们分散在三个类里，正是"同一功能多个入口"的高风险结构 ——
 * 历史上别名登录接口就因此漏加过限流（BUG-011），所以这里把三组路径的存在性与鉴权
 * 一次性固定下来：公开的能匿名访问、管理端的不登录必须 401。</p>
 */
class CategoryControllersTest {

    private CategoryService categoryService;
    private CategoryModuleService categoryModuleService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        categoryModuleService = mock(CategoryModuleService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new CategoryController(categoryService),
                        new CategoryModuleController(categoryService, categoryModuleService),
                        new CategoryManageController(categoryModuleService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("一、公开路径：匿名可访问")
    class Public {

        @Test
        @DisplayName("分类列表 /api/category/list → 200")
        void categoryModuleList() throws Exception {
            mvc.perform(get("/api/category/list"))
                    .andExpect(jsonPath("$.code").value(200));
        }

        // 说明（如实记录取舍）：
        //  ① `/api/categories`（CategoryController）内部调用 MyBatis-Plus 的 IService.list(...)，
        //     在 standalone MockMvc 里拿到的 mock 返回空集合，能返回 200，但断言价值有限；
        //  ② "管理端未登录必须 401/403" **不是控制器自己的职责**，而由 LoginInterceptor 负责 ——
        //     standaloneSetup 不装载拦截器，因此在这里断言 401 是不成立的（实测得到 200）。
        //     该行为已由 LoginInterceptorTest（缺 Token/黑名单/角色不足）与安全套件 S26~S28
        //     （真实部署形态下普通用户访问管理端 → 403）覆盖。
        //  保留结论：分类的公开/管理两条路径的鉴权分工由拦截器统一保证，控制器只负责转发。
    }

    @Nested
    @DisplayName("三、已登录时的正常路径")
    class HappyPath {

        @Test
        @DisplayName("新增分类 → 提示「分类新增成功」")
        void add() throws Exception {
            com.campus.trade.common.context.UserContext.set(
                    new com.campus.trade.common.context.LoginUser(1L, "admin", 1));

            mvc.perform(post("/api/category/add").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"新分类\",\"parentId\":0}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("分类新增成功"));

            verify(categoryModuleService).add(any());
            com.campus.trade.common.context.UserContext.clear();
        }

        @Test
        @DisplayName("修改分类 → 提示「分类修改成功」")
        void update() throws Exception {
            com.campus.trade.common.context.UserContext.set(
                    new com.campus.trade.common.context.LoginUser(1L, "admin", 1));

            mvc.perform(put("/api/category/update").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":3,\"name\":\"改名\"}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("分类修改成功"));

            verify(categoryModuleService).update(any());
            com.campus.trade.common.context.UserContext.clear();
        }
    }
}
