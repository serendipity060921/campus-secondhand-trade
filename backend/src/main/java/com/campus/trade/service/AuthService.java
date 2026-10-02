package com.campus.trade.service;

import com.campus.trade.dto.LoginDTO;
import com.campus.trade.dto.RegisterDTO;
import com.campus.trade.vo.LoginVO;
import com.campus.trade.vo.UserVO;

/**
 * 认证服务（用户注册 / 登录，v0.04）。
 *
 * <p>说明：脚手架已有 UserService（继承 IService&lt;User&gt;），本接口只承载"认证"相关业务，
 * 不改动原有 UserService，符合"只新增、不改动"的要求。</p>
 */
public interface AuthService {

    /**
     * 用户注册。
     *
     * @param dto 用户名、密码、昵称
     * @return 注册成功的用户基础信息（不含密码）
     * @throws com.campus.trade.common.exception.UserException 用户名已存在
     */
    UserVO register(RegisterDTO dto);

    /**
     * 用户登录。
     *
     * @param dto 用户名、密码
     * @return JWT Token + 用户基础信息
     * @throws com.campus.trade.common.exception.UserException 账号不存在 / 密码错误 / 账号被禁用
     */
    LoginVO login(LoginDTO dto);

    /**
     * 查询用户基础信息。
     *
     * @param userId 用户ID
     * @throws com.campus.trade.common.exception.UserException 账号不存在
     */
    UserVO getUserInfo(Long userId);
}
