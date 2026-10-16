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

import com.campus.trade.common.cache.TokenBlacklist;
import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.GlobalExceptionHandler;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.service.AuthService;
import com.campus.trade.service.ProfileService;
import com.campus.trade.vo.LoginVO;
import com.campus.trade.vo.UserVO;

/**
 * 用户 / 认证 / 个人资料接口层验证（v0.18 / 第3批-⑪）
 *
 * <p>这三组接口共用 <code>/api/user</code> 前缀，且注册、登录、退出、改资料都涉及"身份"。
 * 接口层要守的是：公开接口（注册/登录）不需要登录、其余一律需要；
 * 而"改谁的资料"只能由 Token 决定，前端传什么都不能越权。</p>
 *
 * <p>另外顺带覆盖 <code>/api/auth</code> 那组<b>兼容别名</b>接口 —— 它与正式接口等价，
 * 历史上正是因为"别名漏加限流"出过 BUG-011，所以它的行为同样需要被测试固定。</p>
 */
class UserAuthProfileControllerTest {

    private AuthService authService;
    private TokenBlacklist tokenBlacklist;
    private ProfileService profileService;
    private MockMvc mvc;

    private static final Long UID = 11L;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        tokenBlacklist = mock(TokenBlacklist.class);
        profileService = mock(ProfileService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new UserController(authService, tokenBlacklist),
                        new AuthController(authService),
                        new UserProfileController(profileService))
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

    @Nested
    @DisplayName("一、公开接口：注册与登录不需要登录")
    class Public {

        @Test
        @DisplayName("注册成功 → 200 + 提示「注册成功」（RegisterDTO 需 username/password/nickname）")
        void register() throws Exception {
            when(authService.register(any())).thenReturn(new UserVO());

            mvc.perform(post("/api/user/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"newbie\",\"password\":\"abc12345\",\"nickname\":\"新同学\"}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("注册成功"));
        }

        @Test
        @DisplayName("★ 注册缺必填字段 → 400，且同样是统一结构（前端能统一处理）")
        void registerValidation() throws Exception {
            mvc.perform(post("/api/user/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"newbie\"}"))
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").exists());

            verify(authService, never()).register(any());
        }

        @Test
        @DisplayName("登录成功 → 200 + 提示「登录成功」（Token 由服务层返回）")
        void loginUserPath() throws Exception {
            when(authService.login(any())).thenReturn(mock(LoginVO.class));

            mvc.perform(post("/api/user/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"stu_demo\",\"password\":\"123456\"}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("登录成功"));
        }

        @Test
        @DisplayName("★ 别名接口 /api/auth/login 与正式接口等价（同一个 AuthService）")
        void loginAliasPath() throws Exception {
            when(authService.login(any())).thenReturn(mock(LoginVO.class));

            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"stu_demo\",\"password\":\"123456\"}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("登录成功"));

            verify(authService, org.mockito.Mockito.times(1)).login(any());
        }

        @Test
        @DisplayName("账号密码错误 → 返回服务层的业务码（不是 500）")
        void loginFailure() throws Exception {
            when(authService.login(any())).thenThrow(UserException.passwordError());

            mvc.perform(post("/api/user/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"stu_demo\",\"password\":\"wrong\"}"))
                    .andExpect(jsonPath("$.code").value(UserException.passwordError().getCode()));
        }
    }

    @Nested
    @DisplayName("二、需要登录的接口")
    class AuthRequired {

        @Test
        @DisplayName("未登录查用户信息 → 401")
        void infoNeedsLogin() throws Exception {
            mvc.perform(get("/api/user/info")).andExpect(jsonPath("$.code").value(401));
        }

        @Test
        @DisplayName("★ 未登录改资料 → 401，服务层不会被调用")
        void updateNeedsLogin() throws Exception {
            mvc.perform(put("/api/user/update").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"新昵称\"}"))
                    .andExpect(jsonPath("$.code").value(401));

            verify(profileService, never()).updateProfile(any(), anyLong());
        }

        @Test
        @DisplayName("未登录查自己资料 → 401")
        void profileNeedsLogin() throws Exception {
            mvc.perform(get("/api/user/profile")).andExpect(jsonPath("$.code").value(401));
        }

        @Test
        @DisplayName("查用户信息：透传当前用户 ID")
        void info() throws Exception {
            login();

            mvc.perform(get("/api/user/info")).andExpect(jsonPath("$.code").value(200));

            verify(authService).getUserInfo(UID);
        }

        @Test
        @DisplayName("★ 改资料：用户 ID 取自上下文（前端改不了别人）")
        void update() throws Exception {
            login();

            mvc.perform(put("/api/user/update").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"新昵称\",\"campus\":\"东校区\"}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("资料已更新"));

            verify(profileService).updateProfile(any(), eq(UID));
        }

        @Test
        @DisplayName("查自己资料：透传当前用户 ID")
        void profile() throws Exception {
            login();

            mvc.perform(get("/api/user/profile")).andExpect(jsonPath("$.code").value(200));

            verify(profileService).getProfile(UID);
        }

        @Test
        @DisplayName("★ 退出登录：把当前请求的 Token 交给黑名单（而不是只删前端）")
        void logoutPutsTokenIntoBlacklist() throws Exception {
            login();

            mvc.perform(post("/api/user/logout").header("Authorization", "Bearer abc.def.ghi"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("退出登录成功"));

            verify(tokenBlacklist).add("abc.def.ghi");
        }

        @Test
        @DisplayName("别名退出登录：无状态直接成功，不报错")
        void logoutAlias() throws Exception {
            mvc.perform(post("/api/auth/logout"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("退出登录成功"));
        }
    }
}
