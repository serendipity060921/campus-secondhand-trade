package com.campus.trade.common.exception;

import com.campus.trade.common.result.UserResultCode;

/**
 * 用户模块业务异常（v0.04）。
 *
 * <p>继承脚手架原有的 {@link BusinessException}，因此由既有的
 * {@code GlobalExceptionHandler#handleBusinessException} 统一处理，
 * 直接返回 <code>{code, message}</code> 结构的统一响应，无需改动全局异常处理器。</p>
 *
 * <p>覆盖需求中的 5 类异常：用户名已存在、账号不存在、密码错误、Token 过期、Token 无效。</p>
 */
public class UserException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public UserException(UserResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public UserException(Integer code, String message) {
        super(code, message);
    }

    /* ---------------- 语义化工厂方法 ---------------- */

    /** 用户名已存在 */
    public static UserException usernameExists() {
        return new UserException(UserResultCode.USERNAME_EXISTS);
    }

    /** 账号不存在 */
    public static UserException userNotFound() {
        return new UserException(UserResultCode.USER_NOT_FOUND);
    }

    /** 密码错误 */
    public static UserException passwordError() {
        return new UserException(UserResultCode.PASSWORD_ERROR);
    }

    /** 账号被禁用 */
    public static UserException accountDisabled() {
        return new UserException(UserResultCode.ACCOUNT_DISABLED);
    }

    /** 未携带 Token */
    public static UserException tokenMissing() {
        return new UserException(UserResultCode.TOKEN_MISSING);
    }

    /** Token 无效 */
    public static UserException tokenInvalid() {
        return new UserException(UserResultCode.TOKEN_INVALID);
    }

    /** Token 过期 */
    public static UserException tokenExpired() {
        return new UserException(UserResultCode.TOKEN_EXPIRED);
    }

    /** 缺少登录用户上下文 */
    public static UserException unauthorized() {
        return new UserException(UserResultCode.UNAUTHORIZED);
    }
}
