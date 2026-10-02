package com.campus.trade.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建订单请求参数（v0.08）。
 *
 * <p>对应接口：<code>POST /api/order/create</code>（买家 = 当前登录用户）</p>
 */
@Data
public class OrderCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    /** 交付方式：1 校内面交（默认） 2 快递 */
    private Integer deliveryType = 1;

    /** 面交地点（默认取商品上的期望交易地点） */
    @Size(max = 100, message = "交易地点不能超过 100 个字符")
    private String tradePlace;

    /** 买家备注 */
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String buyerRemark;
}
