package com.campus.trade.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户状态变更请求（v0.13，管理端）。
 */
@Data
public class UserStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 目标状态：1 正常 / 0 禁用 */
    @NotNull(message = "状态不能为空")
    private Integer status;
}
