package com.campus.trade.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端用户视图（v0.13）。
 */
@Data
public class AdminUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String nickname;
    private String realName;
    private String studentNo;
    private String phone;
    private String email;
    private String avatar;
    private Integer gender;
    private String school;
    private String campus;
    private Integer role;
    private String roleLabel;
    private Integer status;
    private String statusLabel;
    private Integer creditScore;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;

    /* 统计信息 */
    private Long productCount;
    private Long orderCount;
    private Long reportCount;
}
