package com.campus.trade.common.exception;

import com.campus.trade.common.result.OrderResultCode;

/**
 * 订单模块业务异常（v0.08）。
 *
 * <p>继承脚手架原有的 {@link BusinessException}，由既有全局异常处理器统一返回
 * <code>{code, message}</code>；覆盖需求中的：商品不存在（复用 3001）、商品已下架、
 * 不能购买自己商品、订单不存在、无权操作、状态不允许。</p>
 */
public class OrderException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public OrderException(OrderResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public OrderException(Integer code, String message) {
        super(code, message);
    }

    /* ---------------- 语义化工厂方法 ---------------- */

    /** 商品已下架 / 已被预订 */
    public static OrderException productNotOnSale() {
        return new OrderException(OrderResultCode.PRODUCT_NOT_ON_SALE);
    }

    /** 不能购买自己发布的商品 */
    public static OrderException cannotBuyOwn() {
        return new OrderException(OrderResultCode.CANNOT_BUY_OWN);
    }

    /** 订单不存在 */
    public static OrderException notFound() {
        return new OrderException(OrderResultCode.ORDER_NOT_FOUND);
    }

    /** 无权查看或操作该订单 */
    public static OrderException noPermission() {
        return new OrderException(OrderResultCode.NO_PERMISSION);
    }

    /** 订单状态不允许该操作 */
    public static OrderException statusIllegal() {
        return new OrderException(OrderResultCode.STATUS_ILLEGAL);
    }

    /** 参数不合法 */
    public static OrderException paramIllegal(String detail) {
        return new OrderException(OrderResultCode.PARAM_ILLEGAL.getCode(),
                OrderResultCode.PARAM_ILLEGAL.getMessage() + "：" + detail);
    }
}
