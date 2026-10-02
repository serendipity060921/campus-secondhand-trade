package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 订单模块业务状态码（v0.08）。
 *
 * <p>6001~6099 为订单模块业务错误；"商品不存在" 复用商品模块 3001。</p>
 */
@Getter
public enum OrderResultCode {

    /** 商品已下架 / 已被预订 */
    PRODUCT_NOT_ON_SALE(6001, "商品已下架或已被预订，无法下单"),

    /** 不能购买自己的商品 */
    CANNOT_BUY_OWN(6002, "不能购买自己发布的商品"),

    /** 订单不存在 */
    ORDER_NOT_FOUND(6003, "订单不存在"),

    /** 无权操作该订单 */
    NO_PERMISSION(6004, "无权查看或操作该订单"),

    /** 订单状态不允许该操作 */
    STATUS_ILLEGAL(6005, "订单当前状态不允许该操作"),

    /** 参数不合法 */
    PARAM_ILLEGAL(400, "参数不合法");

    private final Integer code;
    private final String message;

    OrderResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
