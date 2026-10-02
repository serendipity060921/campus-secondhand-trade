package com.campus.trade.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 收藏/取消收藏请求参数（v0.06）。
 *
 * <p>对应接口：<code>POST /api/favorite/operate</code></p>
 */
@Data
public class FavoriteDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    /**
     * 操作类型：1 = 收藏（默认），2 = 取消收藏。
     * 不传时按「收藏」处理，方便只用 productId 调用。
     */
    private Integer type = 1;
}
