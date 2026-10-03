package com.campus.trade.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 商品审核请求（v0.13）。
 */
@Data
public class AuditProductDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    /** true = 审核通过（上架）；false = 驳回（审核不通过） */
    @NotNull(message = "请选择审核结果")
    private Boolean approve;

    /** 审核意见 / 驳回理由（驳回时建议填写） */
    @Size(max = 255, message = "审核意见不能超过 255 个字符")
    private String remark;
}
