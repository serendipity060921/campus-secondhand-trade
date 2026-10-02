package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 收藏模块业务状态码（v0.06）。
 *
 * <p>4001~4099 为收藏模块业务错误；"商品不存在" 复用商品模块的 3001。</p>
 */
@Getter
public enum FavoriteResultCode {

    /** 重复收藏 */
    ALREADY_FAVORITED(4001, "已收藏该商品，请勿重复收藏"),

    /** 收藏自己发布的商品 */
    CANNOT_FAVORITE_OWN(4002, "不能收藏自己发布的商品"),

    /** 尚未收藏 */
    NOT_FAVORITED(4003, "尚未收藏该商品，无法取消"),

    /** 操作类型非法 */
    TYPE_ILLEGAL(400, "参数不合法");

    private final Integer code;
    private final String message;

    FavoriteResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
