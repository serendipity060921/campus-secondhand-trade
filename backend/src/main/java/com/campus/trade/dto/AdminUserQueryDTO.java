package com.campus.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端用户查询条件（v0.13）。
 */
@Data
public class AdminUserQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Min(value = 1, message = "页码必须大于 0")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer size = 10;

    /** 关键词（用户名 / 昵称 / 学号 / 手机号 模糊） */
    private String keyword;

    /** 角色筛选：0学生 1管理员 */
    private Integer role;

    /** 状态筛选：1正常 0禁用 */
    private Integer status;
}
