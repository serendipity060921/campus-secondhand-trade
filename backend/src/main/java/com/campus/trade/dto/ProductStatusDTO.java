package com.campus.trade.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 商品上下架请求参数（v0.05）。
 *
 * <p>对应接口：<code>PUT /api/product/status</code>（必须登录，且只能操作自己发布的商品）</p>
 */
@Data
public class ProductStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    /** 目标状态：1 = 上架（在售），3 = 下架 */
    @NotNull(message = "目标状态不能为空")
    private Integer status;
}
