package com.campus.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 提交举报请求（v0.13，普通用户）。
 */
@Data
public class ReportCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 举报对象类型：1 商品 / 2 用户 */
    @NotNull(message = "举报对象类型不能为空")
    @Min(value = 1, message = "举报对象类型只能为 1（商品）或 2（用户）")
    @Max(value = 2, message = "举报对象类型只能为 1（商品）或 2（用户）")
    private Integer targetType;

    /** 举报对象ID */
    @NotNull(message = "举报对象ID不能为空")
    private Long targetId;

    /** 举报原因：1 虚假信息 / 2 违禁物品 / 3 辱骂骚扰 / 4 其他 */
    @NotNull(message = "请选择举报原因")
    @Min(value = 1, message = "举报原因不合法")
    @Max(value = 4, message = "举报原因不合法")
    private Integer reasonType;

    /** 补充说明 */
    @Size(max = 500, message = "补充说明不能超过 500 个字符")
    private String content;

    /** 证据图片地址 */
    @Size(max = 255, message = "图片地址过长")
    private String imageUrl;
}
