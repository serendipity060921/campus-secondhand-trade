package com.campus.trade.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录请求参数（v0.04）。
 *
 * <p>对应接口：<code>POST /api/user/login</code></p>
 */
@Data
public class LoginDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 密码（明文，仅用于本次比对，不会入库） */
    @NotBlank(message = "密码不能为空")
    private String password;
}
