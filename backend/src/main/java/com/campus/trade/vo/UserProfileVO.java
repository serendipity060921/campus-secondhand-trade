package com.campus.trade.vo;

import com.campus.trade.entity.User;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;

/**
 * 个人资料完整信息（v0.09）。
 *
 * <p>v0.04 的 {@link UserVO} 只包含公开字段（昵称/头像/校区/信用分等），
 * 不含手机号、邮箱、真实姓名等隐私字段 —— 这是出于"对外展示脱敏"的考虑。
 * 个人中心编辑页需要这些字段，因此这里新增一个资料专用 VO，
 * <b>不修改 v0.04 的 UserVO</b>，避免影响商品详情、订单、会话等已在用它的接口。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserProfileVO extends UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 真实姓名 */
    private String realName;

    /** 学号（只读展示，不可修改） */
    private String studentNo;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 由实体转换（含隐私字段，仅用于"查自己"的接口） */
    public static UserProfileVO of(User user) {
        if (user == null) {
            return null;
        }
        UserProfileVO vo = new UserProfileVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
