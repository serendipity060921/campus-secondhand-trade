package com.campus.trade.service;

import com.campus.trade.dto.UserUpdateDTO;
import com.campus.trade.vo.UserProfileVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人资料业务（v0.09）。
 */
public interface ProfileService {

    /**
     * 查询当前用户的完整资料（含手机号/邮箱/真实姓名等隐私字段）。
     *
     * @throws com.campus.trade.common.exception.UserException 用户不存在(2002)
     */
    UserProfileVO getProfile(Long userId);

    /**
     * 修改个人资料（只更新提交的字段）。
     *
     * @throws com.campus.trade.common.exception.UserException    用户不存在(2002)
     * @throws com.campus.trade.common.exception.ProfileException 手机号被占用(2006)
     */
    UserProfileVO updateProfile(UserUpdateDTO dto, Long userId);

    /**
     * 上传头像并立即更新到当前用户。
     *
     * <p>复用 v0.05 的 {@code FileService}：非空 / 扩展名白名单 / 必须 image/* / 单张 ≤ 5MB，
     * 因此文件相关错误码沿用 3005~3007。</p>
     *
     * @return 更新后的完整资料（含新头像地址）
     */
    UserProfileVO uploadAvatar(MultipartFile file, Long userId);
}
