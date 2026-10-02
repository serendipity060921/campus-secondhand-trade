package com.campus.trade.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单状态修改请求参数（v0.08）。
 *
 * <p>对应接口：<code>PUT /api/order/status</code></p>
 *
 * <p>可流转的目标状态：</p>
 * <ul>
 *   <li><b>3 已完成</b>：买家或卖家确认交易完成 → 商品变为"已售出"，双方信用分 +1</li>
 *   <li><b>4 已取消</b>：买家或卖家取消 → 商品回到"在售"</li>
 * </ul>
 * <p>0 待交易 是下单后的初始状态，不需要通过本接口设置。</p>
 */
@Data
public class OrderStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单ID */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 目标状态：3 已完成 / 4 已取消 */
    @NotNull(message = "目标状态不能为空")
    private Integer status;

    /** 取消原因（取消时选填） */
    @Size(max = 255, message = "取消原因不能超过 255 个字符")
    private String cancelReason;
}
