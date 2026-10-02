package com.campus.trade.common.constant;

import lombok.Getter;

/**
 * 订单状态（v0.08）。
 *
 * <p>本模块实现需求约定的三态流转：</p>
 * <pre>
 *   待交易(0) ──买家/卖家确认完成──▶ 已完成(3)   商品 → 已售出
 *        └────买家/卖家取消───────▶ 已取消(4)   商品 → 回到在售
 * </pre>
 *
 * <p>说明：db_schema.sql 中 orders.status 注释描述的是完整状态机
 * （0待付款 1待交付 2待收货 3已完成 4已取消 5已退款）。
 * 本里程碑把「支付 + 交付」简化为一次线下见面交易，因此 0 在本模块表示"待交易"；
 * 1 待交付 / 2 待收货 / 5 已退款 <b>保留给后续「支付与物流」里程碑</b>，当前不会产生。</p>
 */
@Getter
public enum OrderStatus {

    /** 待交易（下单后的初始状态） */
    PENDING(0, "待交易"),

    /** 已完成 */
    FINISHED(3, "已完成"),

    /** 已取消 */
    CANCELLED(4, "已取消"),

    /* ---------- 以下为预留状态（本模块不会产生） ---------- */
    /** 待交付（预留：支付完成后） */
    RESERVED_DELIVERING(1, "待交付"),
    /** 待收货（预留：卖家发货后） */
    RESERVED_RECEIVING(2, "待收货"),
    /** 已退款（预留：异常订单） */
    RESERVED_REFUNDED(5, "已退款");

    private final Integer code;
    private final String label;

    OrderStatus(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 根据状态码取中文名，未知返回"未知状态" */
    public static String labelOf(Integer code) {
        if (code == null) {
            return "未知状态";
        }
        for (OrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status.label;
            }
        }
        return "未知状态(" + code + ")";
    }

    /** 是否属于本模块可流转的状态 */
    public static boolean isActive(Integer code) {
        return PENDING.code.equals(code) || FINISHED.code.equals(code) || CANCELLED.code.equals(code);
    }
}
