package com.campus.trade.common.constant;

import lombok.Getter;

/**
 * 商品状态枚举（v0.13 补充，与 db_schema.sql 中 product.status 注释一致）。
 *
 * <p>说明：v0.05 起商品状态散落在业务常量中，v0.13 管理后台需要按状态筛选与审核，
 * 因此统一抽取为枚举，并提供中文标签。</p>
 */
@Getter
public enum ProductStatus {

    /** 待审核：v0.13 起开启审核开关后，新发布商品进入该状态，管理后台审核 */
    PENDING(0, "待审核"),
    /** 在售 */
    ON_SALE(1, "在售"),
    /** 审核不通过（驳回）：卖家可修改后重新提交 */
    REJECTED(2, "审核不通过"),
    /** 已下架 */
    OFF_SHELF(3, "已下架"),
    /** 交易中（已被预订） */
    TRADING(4, "交易中"),
    /** 已售出 */
    SOLD(5, "已售出");

    private final Integer code;
    private final String label;

    ProductStatus(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    public static String labelOf(Integer code) {
        if (code == null) {
            return "未知";
        }
        for (ProductStatus status : values()) {
            if (status.code.equals(code)) {
                return status.label;
            }
        }
        return "未知";
    }
}
