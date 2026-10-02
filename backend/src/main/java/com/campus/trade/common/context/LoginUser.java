package com.campus.trade.common.context;

/**
 * 当前登录用户信息（由 JWT 载荷解析而来）。
 *
 * @param userId   用户ID
 * @param username 用户名
 * @param role     角色：0普通学生 1管理员
 */
public record LoginUser(Long userId, String username, Integer role) {

    /** 是否管理员 */
    public boolean isAdmin() {
        return role != null && role == 1;
    }
}
