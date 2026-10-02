package com.campus.trade.controller;

import com.campus.trade.common.result.Result;
import com.campus.trade.dto.LoginDTO;
import com.campus.trade.service.AuthService;
import com.campus.trade.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口别名（v0.04，兼容脚手架预留路径）。
 *
 * <p>脚手架前端的 <code>frontend/src/api/common.js</code> 里已预留
 * <code>POST /api/auth/login</code> 与 <code>POST /api/auth/logout</code>，
 * 为了让原有代码零改动即可工作，这里提供同样功能的别名接口，内部复用同一个 AuthService：</p>
 *
 * <ul>
 *   <li>正式接口：<code>POST /api/user/login</code>（本模块主接口，文档以此为准）</li>
 *   <li>兼容接口：<code>POST /api/auth/login</code>（等价）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 登录（等价于 POST /api/user/login） */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success("登录成功", authService.login(dto));
    }

    /** 退出登录（等价于 POST /api/user/logout，无状态，直接返回成功） */
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success("退出登录成功", null);
    }
}
