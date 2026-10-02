package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.trade.common.exception.ProfileException;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.dto.UserUpdateDTO;
import com.campus.trade.entity.User;
import com.campus.trade.service.FileService;
import com.campus.trade.service.ProfileService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.FileUploadVO;
import com.campus.trade.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人资料业务实现（v0.09）。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>MyBatis-Plus 的 updateById 默认忽略 null 字段，因此天然支持"只改提交的字段"；</li>
 *   <li>用户名、学号是账号凭证，不在 DTO 中，从接口层面就不可修改；</li>
 *   <li>手机号有唯一索引（uk_phone），这里先做友好查重，数据库唯一索引作为兜底；</li>
 *   <li>头像上传复用 v0.05 的 FileService，不重复实现文件校验与落盘；</li>
 *   <li>返回 {@link UserProfileVO}（含手机号/邮箱），与对外展示用的 UserVO 区分开。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final UserService userService;
    private final FileService fileService;

    @Override
    public UserProfileVO getProfile(Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw UserException.userNotFound();
        }
        return UserProfileVO.of(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO updateProfile(UserUpdateDTO dto, Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw UserException.userNotFound();
        }

        // 手机号唯一性校验（只有真的改动过才查库）
        String phone = dto.getPhone();
        if (StringUtils.hasText(phone) && !phone.equals(user.getPhone())) {
            long exists = userService.count(new LambdaQueryWrapper<User>()
                    .eq(User::getPhone, phone)
                    .ne(User::getId, userId));
            if (exists > 0) {
                throw ProfileException.phoneExists();
            }
        } else if (!StringUtils.hasText(phone)) {
            // 清空手机号时不要把空串写进唯一索引
            dto.setPhone(null);
        }

        User update = new User();
        update.setId(userId);
        update.setNickname(trimToNull(dto.getNickname()));
        update.setAvatar(trimToNull(dto.getAvatar()));
        update.setRealName(trimToNull(dto.getRealName()));
        update.setGender(dto.getGender());
        update.setSchool(trimToNull(dto.getSchool()));
        update.setCampus(trimToNull(dto.getCampus()));
        update.setPhone(trimToNull(dto.getPhone()));
        update.setEmail(trimToNull(dto.getEmail()));

        userService.updateById(update);
        log.info("[资料修改] userId={} nickname={} campus={} phone={} email={}",
                userId, dto.getNickname(), dto.getCampus(), dto.getPhone(), dto.getEmail());
        return UserProfileVO.of(userService.getById(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO uploadAvatar(MultipartFile file, Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw UserException.userNotFound();
        }

        // 复用 v0.05 文件服务：保存到 uploads/yyyy/MM/uuid.ext 并返回可访问地址
        FileUploadVO uploaded = fileService.store(file);

        User update = new User();
        update.setId(userId);
        update.setAvatar(uploaded.getUrl());
        userService.updateById(update);

        log.info("[头像更新] userId={} {} -> {}", userId, user.getAvatar(), uploaded.getUrl());
        return UserProfileVO.of(userService.getById(userId));
    }

    /** 去除首尾空格，空白串转 null（避免把空串写进数据库） */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
