package com.campus.trade.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录成功返回结果（v0.04）。
 *
 * <p>结构与前端脚手架 store/user.js 的约定一致：<code>res.data.token</code> 与 <code>res.data.userInfo</code>。</p>
 *
 * <pre>
 * {
 *   "token": "eyJhbGciOiJIUzI1NiJ9...",
 *   "tokenType": "Bearer",
 *   "expiresIn": 7200,
 *   "userInfo": { "id": 1, "username": "admin", "nickname": "系统管理员", "role": 1 }
 * }
 * </pre>
 */
@Data
@Builder
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JWT Token */
    private String token;

    /** Token 类型，固定 Bearer（前端拼接为 Authorization: Bearer xxx） */
    private String tokenType;

    /** Token 有效期（秒） */
    private Long expiresIn;

    /** 用户基础信息 */
    private UserVO userInfo;
}
