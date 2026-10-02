package com.campus.trade.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 收藏状态（v0.06）。
 *
 * <p>收藏/取消收藏接口与"是否已收藏"接口共用：</p>
 * <pre>
 * { "productId": 6, "favorited": true, "favoriteCount": 5 }
 * </pre>
 */
@Data
@Builder
public class FavoriteStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    private Long productId;

    /** 当前用户是否已收藏 */
    private Boolean favorited;

    /** 该商品当前收藏总数 */
    private Integer favoriteCount;
}
