package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 分类管理模块业务状态码（v0.09）。
 */
@Getter
public enum CategoryResultCode {

    /** 同级分类重名 */
    NAME_EXISTS(7001, "同一上级分类下已存在同名分类"),

    /** 分类不存在 */
    NOT_FOUND(7002, "分类不存在"),

    /** 上级分类不存在 */
    PARENT_NOT_FOUND(7003, "上级分类不存在"),

    /** 上级分类设置为自己 */
    SELF_PARENT(7004, "不能把分类的上级设置为自己"),

    /** 参数不合法 */
    PARAM_ILLEGAL(400, "参数不合法");

    private final Integer code;
    private final String message;

    CategoryResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
