package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.trade.common.exception.BusinessException;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.common.util.JwtUtil;
import com.campus.trade.dto.LoginDTO;
import com.campus.trade.dto.RegisterDTO;
import com.campus.trade.entity.User;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.LoginVO;
import com.campus.trade.vo.UserVO;

/**
 * 认证服务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>注册与登录是整个系统唯一的"信任入口"，这里要固定的不是"能登录"，而是：</p>
 * <ul>
 *   <li>★ <b>密码只以密文入库</b>：注册时调用 PasswordEncoder（BCrypt 每次结果不同），
 *       绝不能把明文塞进实体；</li>
 *   <li>★ <b>并发注册同名由唯一索引兜底</b>，并把 DuplicateKeyException 转成业务码 2001，
 *       而不是把通用"数据已存在(1002)"抛给用户（BUG-04 的修复点）；</li>
 *   <li>★ <b>禁用账号能登录成功是把门开着</b>：状态为 0 必须在密码校验之前就被挡住；</li>
 *   <li>★ 登录成功要更新 lastLoginTime 并签发 Token（携带角色，供拦截器鉴权）。</li>
 * </ul>
 */
class AuthServiceImplTest {

    private UserService userService;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private AuthServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), User.class);
    }

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        service = new AuthServiceImpl(userService, passwordEncoder, jwtUtil);
    }

    private RegisterDTO reg(String username, String password, String nickname) {
        RegisterDTO d = new RegisterDTO();
        d.setUsername(username);
        d.setPassword(password);
        d.setNickname(nickname);
        return d;
    }

    private LoginDTO loginDTO(String username, String password) {
        LoginDTO d = new LoginDTO();
        d.setUsername(username);
        d.setPassword(password);
        return d;
    }

    private User user(long id, String username, String encoded, int role, Integer status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword(encoded);
        u.setNickname("用户" + id);
        u.setRole(role);
        u.setStatus(status);
        u.setCreditScore(100);
        return u;
    }

    @Nested
    @DisplayName("一、注册")
    class Register {

        @Test
        @DisplayName("★ 密码必须加密后入库，明文不得落库")
        void passwordIsEncoded() {
            when(userService.count(any())).thenReturn(0L);
            when(passwordEncoder.encode("abc12345")).thenReturn("$2a$10$encoded");
            when(userService.save(any())).thenAnswer(inv -> {
                ((User) inv.getArgument(0)).setId(9L);
                return true;
            });

            service.register(reg("newbie", "abc12345", "新同学"));

            verify(passwordEncoder).encode("abc12345");
            verify(userService).save(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && "$2a$10$encoded".equals(u.getPassword())
                            && !"abc12345".equals(u.getPassword())));
        }

        @Test
        @DisplayName("★ 新用户默认值：普通学生角色(0)、正常状态(1)、信用分 100")
        void defaultsApplied() {
            when(userService.count(any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$enc");
            when(userService.save(any())).thenAnswer(inv -> {
                ((User) inv.getArgument(0)).setId(9L);
                return true;
            });

            service.register(reg("newbie", "abc12345", "新同学"));

            verify(userService).save(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && Integer.valueOf(0).equals(u.getRole())
                            && Integer.valueOf(1).equals(u.getStatus())
                            && Integer.valueOf(100).equals(u.getCreditScore())));
        }

        @Test
        @DisplayName("用户名两端空格会被去掉（否则 ' a' 与 'a' 会变成两个账号）")
        void usernameTrimmed() {
            when(userService.count(any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$enc");
            when(userService.save(any())).thenAnswer(inv -> {
                ((User) inv.getArgument(0)).setId(9L);
                return true;
            });

            service.register(reg("  newbie  ", "abc12345", "新同学"));

            verify(userService).save(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && "newbie".equals(u.getUsername())));
        }

        @Test
        @DisplayName("★ 用户名已存在 → 2001")
        void usernameExists() {
            when(userService.count(any())).thenReturn(1L);

            UserException e = assertThrows(UserException.class,
                    () -> service.register(reg("dup", "abc12345", "重复")));
            assertEquals(UserException.usernameExists().getCode(), e.getCode());
            verify(userService, never()).save(any());
        }

        @Test
        @DisplayName("★ 并发注册同名：唯一索引抛 DuplicateKeyException → 也转成 2001")
        void duplicateKeyConverted() {
            when(userService.count(any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$enc");
            when(userService.save(any())).thenThrow(new DuplicateKeyException("uk_username"));

            UserException e = assertThrows(UserException.class,
                    () -> service.register(reg("race", "abc12345", "并发")));
            assertEquals(UserException.usernameExists().getCode(), e.getCode(),
                    "★ 不能让用户看到通用的 1002");
        }

        @Test
        @DisplayName("入库失败（save 返回 false）→ 业务异常，不返回假成功")
        void saveFailure() {
            when(userService.count(any())).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$enc");
            when(userService.save(any())).thenReturn(false);

            assertThrows(BusinessException.class, () -> service.register(reg("x", "abc12345", "x")));
        }
    }

    @Nested
    @DisplayName("二、★ 登录的四道关")
    class Login {

        @Test
        @DisplayName("账号不存在 → 报错（且不签发 Token）")
        void userNotFound() {
            when(userService.getOne(org.mockito.ArgumentMatchers.<Wrapper<User>>any())).thenReturn(null);

            assertThrows(UserException.class, () -> service.login(loginDTO("nobody", "x")));
            verify(jwtUtil, never()).generateToken(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("★ 账号被禁用 → 在密码校验之前就被挡住（不能等到比对密码）")
        void disabledRejectedEarly() {
            when(userService.getOne(org.mockito.ArgumentMatchers.<Wrapper<User>>any()))
                    .thenReturn(user(9L, "banned", "$2a$enc", 0, 0));

            UserException e = assertThrows(UserException.class,
                    () -> service.login(loginDTO("banned", "abc12345")));
            assertEquals(UserException.accountDisabled().getCode(), e.getCode());
            verify(passwordEncoder, never()).matches(anyString(), anyString());
        }

        @Test
        @DisplayName("密码错误 → 报错（BCrypt matches 返回 false）")
        void wrongPassword() {
            when(userService.getOne(org.mockito.ArgumentMatchers.<Wrapper<User>>any()))
                    .thenReturn(user(9L, "stu", "$2a$enc", 0, 1));
            when(passwordEncoder.matches("wrong", "$2a$enc")).thenReturn(false);

            UserException e = assertThrows(UserException.class,
                    () -> service.login(loginDTO("stu", "wrong")));
            assertEquals(UserException.passwordError().getCode(), e.getCode());
            verify(jwtUtil, never()).generateToken(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("★ 登录成功：更新 lastLoginTime + 签发 Token（tokenType=Bearer，带有效期与用户信息）")
        void loginSuccess() {
            when(userService.getOne(org.mockito.ArgumentMatchers.<Wrapper<User>>any()))
                    .thenReturn(user(9L, "stu", "$2a$enc", 0, 1));
            when(passwordEncoder.matches("abc12345", "$2a$enc")).thenReturn(true);
            when(jwtUtil.generateToken(9L, "stu", 0)).thenReturn("header.payload.signature");
            when(jwtUtil.getExpireSeconds()).thenReturn(604800L);

            LoginVO vo = service.login(loginDTO("stu", "abc12345"));

            assertEquals("header.payload.signature", vo.getToken());
            assertEquals("Bearer", vo.getTokenType());
            assertEquals(604800L, vo.getExpiresIn());
            assertNotNull(vo.getUserInfo());

            // 只更新最后登录时间这一个字段（不能整行覆盖，避免把密码等字段写丢）
            verify(userService).updateById(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && Long.valueOf(9L).equals(u.getId())
                            && u.getLastLoginTime() != null
                            && u.getPassword() == null));
        }

        @Test
        @DisplayName("★ 状态为 null 的账号不当作禁用（历史数据兼容）")
        void nullStatusAllowed() {
            when(userService.getOne(org.mockito.ArgumentMatchers.<Wrapper<User>>any()))
                    .thenReturn(user(9L, "old", "$2a$enc", 0, null));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            // 注意：实现里日志会 token.substring(0, 20)，桩的 token 必须够 20 个字符
            when(jwtUtil.generateToken(anyLong(), anyString(), any()))
                    .thenReturn("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI5In0.signature");

            assertNotNull(service.login(loginDTO("old", "abc12345")).getToken());
        }

        @Test
        @DisplayName("Token 里带的是真实角色（管理员登录后拦截器才能识别）")
        void tokenCarriesRole() {
            when(userService.getOne(org.mockito.ArgumentMatchers.<Wrapper<User>>any()))
                    .thenReturn(user(1L, "admin", "$2a$enc", 1, 1));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(jwtUtil.generateToken(1L, "admin", 1))
                    .thenReturn("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature");

            service.login(loginDTO("admin", "123456"));

            verify(jwtUtil).generateToken(1L, "admin", 1);
        }
    }

    @Nested
    @DisplayName("三、查询用户信息")
    class UserInfo {

        @Test
        @DisplayName("用户不存在 → 报错")
        void notFound() {
            when(userService.getById(9L)).thenReturn(null);
            assertThrows(UserException.class, () -> service.getUserInfo(9L));
        }

        @Test
        @DisplayName("正常返回用户信息")
        void ok() {
            when(userService.getById(9L)).thenReturn(user(9L, "stu", "$2a$enc", 0, 1));

            UserVO vo = service.getUserInfo(9L);

            assertEquals("stu", vo.getUsername());
            // ★ 用反射核实「对外 VO 不含密码字段」，而不是假设它不含
            assertThrows(NoSuchFieldException.class,
                    () -> UserVO.class.getDeclaredField("password"),
                    "UserVO 是出口对象，不能带密码字段");
        }
    }
}
