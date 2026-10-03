package com.campus.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 处理举报请求（v0.13，管理端）。
 */
@Data
public class ReportHandleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 举报ID */
    @NotNull(message = "举报ID不能为空")
    private Long reportId;

    /** 处理动作：1 已处理（如下架商品/警告用户）/ 2 忽略 */
    @NotNull(message = "请选择处理动作")
    @Min(value = 1, message = "处理动作只能为 1（处理）或 2（忽略）")
    @Max(value = 2, message = "处理动作只能为 1（处理）或 2（忽略）")
    private Integer action;

    /** 处理结果说明 */
    @Size(max = 255, message = "处理说明不能超过 255 个字符")
    private String result;
}
