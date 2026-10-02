package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.LoginDTO;
import com.campus.trade.dto.RegisterDTO;
import com.campus.trade.service.AuthService;
import com.campus.trade.vo.LoginVO;
import com.campus.trade.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户模块接口（v0.04）。
 *
 * <table border="1">
 *   <caption>接口清单</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th><th>是否需要 Token</th></tr>
 *   <tr><td>POST</td><td>/api/user/register</td><td>注册</td><td>否</td></tr>
 *   <tr><td>POST</td><td>/api/user/login</td><td>登录</td><td>否</td></tr>
 *   <tr><td>GET</td><td>/api/user/info</td><td>当前登录用户信息</td><td>是（@LoginRequired）</td></tr>
 *   <tr><td>POST</td><td>/api/user/logout</td><td>退出登录</td><td>是（@LoginRequired）</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    /**
     * 用户注册。
     * <p>参数校验：用户名/密码/昵称非空 + 长度与格式校验；用户名重复返回 2001。</p>
     */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO dto) {
        UserVO userVO = authService.register(dto);
        return Result.success("注册成功", userVO);
    }

    /**
     * 用户登录。
     * <p>成功返回 JWT Token 与用户基础信息；账号不存在返回 2002，密码错误返回 2003。</p>
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        LoginVO loginVO = authService.login(dto);
        return Result.success("登录成功", loginVO);
    }

    /**
     * 查询当前登录用户信息（需要携带 Token）。
     * <p>用户ID 从 Token 解析后的上下文中获取，<b>不由前端传入</b>，避免越权查询他人信息。</p>
     */
    @LoginRequired
    @GetMapping("/info")
    public Result<UserVO> info() {
        return Result.success(authService.getUserInfo(UserContext.getUserId()));
    }

    /**
     * 退出登录（需要携带 Token）。
     * <p>JWT 是无状态的，服务端不保存会话；此处仅做前端 Token 清理的确认接口。
     * 若后续需要"立即失效"，可把 Token 加入 Redis 黑名单并在此处写入。</p>
     */
    @LoginRequired
    @PostMapping("/logout")
    public Result<Void> logout() {
        log.info("[退出登录] userId={} username={}", UserContext.getUserId(), UserContext.getUsername());
        return Result.success("退出登录成功", null);
    }
}
