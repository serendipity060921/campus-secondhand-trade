package com.campus.trade.vo;

import com.campus.trade.entity.User;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户基础信息（返回给前端，v0.04）。
 *
 * <p>注意：<b>不包含 password 字段</b>，从源头杜绝密码外泄。</p>
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatar;

    /** 性别：0未知 1男 2女 */
    private Integer gender;

    /** 学校 */
    private String school;

    /** 校区 */
    private String campus;

    /** 角色：0普通学生 1管理员 */
    private Integer role;

    /** 状态：1正常 0禁用 */
    private Integer status;

    /** 信用分 */
    private Integer creditScore;

    /** 最后登录时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastLoginTime;

    /** 注册时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 由实体转换（只复制同名属性，password 不在其中） */
    public static UserVO of(User user) {
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
