package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.common.util.JwtUtil;
import com.campus.trade.dto.LoginDTO;
import com.campus.trade.dto.RegisterDTO;
import com.campus.trade.entity.User;
import com.campus.trade.service.AuthService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.LoginVO;
import com.campus.trade.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证服务实现（v0.04）。
 *
 * <p>注册：用户名查重 → BCrypt 加密密码 → 入库（数据库永不存明文）</p>
 * <p>登录：按用户名查询 → 状态校验 → BCrypt 比对密码 → 签发 JWT → 更新最后登录时间</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 新注册用户默认角色：0 普通学生 */
    private static final int DEFAULT_ROLE_STUDENT = 0;
    /** 账号状态：1 正常 */
    private static final int STATUS_NORMAL = 1;
    /** 初始信用分 */
    private static final int DEFAULT_CREDIT_SCORE = 100;

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterDTO dto) {
        String username = dto.getUsername().trim();

        // ① 用户名查重（逻辑删除字段由 MyBatis-Plus 自动追加 deleted = 0）
        Long exists = userService.count(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            log.warn("[注册失败] 用户名已存在: {}", username);
            throw UserException.usernameExists();
        }

        // ② 组装用户并加密密码（BCrypt：每次加密结果不同，数据库中只存密文）
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname().trim());
        user.setRole(DEFAULT_ROLE_STUDENT);
        user.setStatus(STATUS_NORMAL);
        user.setCreditScore(DEFAULT_CREDIT_SCORE);
        user.setGender(0);
        user.setDeleted(0);

        // ③ 入库（MyBatis-Plus 会把自增主键回填到 user.id）
        // v0.10 缺陷修复（BUG-04）：并发注册同一用户名时，两个请求可能同时通过上面的查重，
        // 由数据库唯一索引 uk_username 兜底；这里把唯一键冲突转换成业务错误码 2001，
        // 避免用户看到通用的「数据已存在（违反唯一约束）」(1002)。
        boolean saved;
        try {
            saved = userService.save(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.warn("[注册冲突] 并发注册同一用户名: {}", username);
            throw UserException.usernameExists();
        }
        if (!saved) {
            throw new com.campus.trade.common.exception.BusinessException("注册失败，请稍后重试");
        }
        log.info("[注册成功] id={} username={} nickname={}", user.getId(), username, user.getNickname());
        return UserVO.of(user);
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        String username = dto.getUsername().trim();

        // ① 按用户名查询（账号不存在）
        User user = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            log.warn("[登录失败] 账号不存在: {}", username);
            throw UserException.userNotFound();
        }

        // ② 账号状态校验（被禁用）
        if (user.getStatus() != null && user.getStatus() == 0) {
            log.warn("[登录失败] 账号已被禁用: {}", username);
            throw UserException.accountDisabled();
        }

        // ③ 密码比对（BCrypt 不可逆，只能用 matches 比较）
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("[登录失败] 密码错误: {}", username);
            throw UserException.passwordError();
        }

        // ④ 更新最后登录时间（只更新这一个字段）
        User update = new User();
        update.setId(user.getId());
        update.setLastLoginTime(LocalDateTime.now());
        userService.updateById(update);

        // ⑤ 签发 Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        log.info("[登录成功] id={} username={} token 前缀={}...", user.getId(), username, token.substring(0, 20));

        return LoginVO.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getExpireSeconds())
                .userInfo(UserVO.of(user))
                .build();
    }

    @Override
    public UserVO getUserInfo(Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw UserException.userNotFound();
        }
        return UserVO.of(user);
    }
}
