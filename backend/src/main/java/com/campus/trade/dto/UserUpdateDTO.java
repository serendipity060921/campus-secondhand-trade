package com.campus.trade.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改个人资料请求参数（v0.09）。
 *
 * <p>对应接口：<code>PUT /api/user/update</code></p>
 *
 * <p>只提交需要修改的字段即可（null 表示不修改）。
 * 用户名与学号是账号凭证，<b>不允许修改</b>，因此不在本 DTO 中。</p>
 */
@Data
public class UserUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 昵称 */
    @Size(min = 2, max = 20, message = "昵称长度必须为 2~20 个字符")
    private String nickname;

    /** 头像地址（先调 /api/user/avatar 上传拿到地址，再提交到这里） */
    @Size(max = 255, message = "头像地址过长")
    private String avatar;

    /** 真实姓名 */
    @Size(max = 50, message = "真实姓名不能超过 50 个字符")
    private String realName;

    /** 性别：0未知 1男 2女 */
    @Min(value = 0, message = "性别取值 0~2")
    @Max(value = 2, message = "性别取值 0~2")
    private Integer gender;

    /** 学校 */
    @Size(max = 100, message = "学校名称不能超过 100 个字符")
    private String school;

    /** 校区 */
    @Size(max = 100, message = "校区名称不能超过 100 个字符")
    private String campus;

    /** 手机号（全平台唯一） */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 邮箱 */
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱不能超过 100 个字符")
    private String email;
}
