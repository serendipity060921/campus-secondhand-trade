package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 用户模块业务状态码（v0.04）。
 *
 * <p>与通用 {@link ResultCode} 分开维护，避免改动脚手架原有枚举；</p>
 * <p>约定：</p>
 * <ul>
 *   <li>2001~2099：用户模块业务错误（用户名已存在、账号不存在、密码错误等）</li>
 *   <li>401：登录态问题（未登录 / Token 无效 / Token 过期），
 *       前端 axios 响应拦截器识别到 401 会清除本地 Token 并自动跳转登录页</li>
 * </ul>
 */
@Getter
public enum UserResultCode {

    /** 用户名已存在 */
    USERNAME_EXISTS(2001, "用户名已被注册，请更换后重试"),

    /** 账号不存在 */
    USER_NOT_FOUND(2002, "账号不存在，请检查用户名"),

    /** 密码错误 */
    PASSWORD_ERROR(2003, "密码错误，请重新输入"),

    /** 账号被禁用 */
    ACCOUNT_DISABLED(2004, "账号已被禁用，请联系管理员"),

    /** 原密码错误（预留：修改密码用） */
    OLD_PASSWORD_ERROR(2005, "原密码错误"),

    /** 未携带 Token */
    TOKEN_MISSING(401, "未登录，请先登录"),

    /** Token 无效（签名错误 / 格式错误 / 被篡改） */
    TOKEN_INVALID(401, "Token 无效，请重新登录"),

    /** Token 已过期 */
    TOKEN_EXPIRED(401, "登录已过期，请重新登录"),

    /** 登录状态异常（缺少用户上下文） */
    UNAUTHORIZED(401, "登录状态异常，请重新登录"),

    /** 无权限访问（预留：管理员接口） */
    NO_PERMISSION(403, "没有操作权限");

    private final Integer code;
    private final String message;

    UserResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
