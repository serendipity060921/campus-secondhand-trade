package com.campus.trade.common.context;

import com.campus.trade.common.exception.UserException;

/**
 * 登录用户上下文（v0.04）。
 *
 * <p>拦截器校验 Token 通过后，把用户信息放入 ThreadLocal，
 * 业务代码可在任意位置通过 {@code UserContext.getUserId()} 取到当前登录用户，
 * 请求结束后由拦截器调用 {@link #clear()} 释放，避免线程复用导致的数据串号。</p>
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /** 保存当前登录用户 */
    public static void set(LoginUser loginUser) {
        HOLDER.set(loginUser);
    }

    /** 获取当前登录用户，可能为 null（公开接口） */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 获取当前登录用户，未登录时抛出 401 异常 */
    public static LoginUser require() {
        LoginUser loginUser = HOLDER.get();
        if (loginUser == null) {
            throw UserException.unauthorized();
        }
        return loginUser;
    }

    /** 当前登录用户ID，未登录抛 401 */
    public static Long getUserId() {
        return require().userId();
    }

    /** 当前登录用户名，未登录抛 401 */
    public static String getUsername() {
        return require().username();
    }

    /** 是否已登录 */
    public static boolean isLogin() {
        return HOLDER.get() != null;
    }

    /** 是否管理员 */
    public static boolean isAdmin() {
        LoginUser loginUser = HOLDER.get();
        return loginUser != null && loginUser.isAdmin();
    }

    /** 清除（必须在本请求结束时调用） */
    public static void clear() {
        HOLDER.remove();
    }
}
