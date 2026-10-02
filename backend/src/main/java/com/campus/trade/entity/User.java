package com.campus.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户表实体（user）。
 *
 * <p>普通学生与管理员共用一张表，通过 {@code role} 区分：
 * 0 = 普通学生（买家/卖家同一账号），1 = 管理员。</p>
 */
@Data
@TableName("user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID，主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录用户名，唯一 */
    private String username;

    /** 登录密码，BCrypt 密文 */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 真实姓名 */
    private String realName;

    /** 学号，唯一 */
    private String studentNo;

    /** 手机号，唯一 */
    private String phone;

    /** 邮箱 */
    private String email;

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
    private LocalDateTime lastLoginTime;

    /** 创建时间（插入时自动填充） */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（插入/更新时自动填充） */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
