package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 私信模块业务状态码（v0.07）。
 *
 * <p>5001~5099 为私信模块业务错误；"商品不存在" 复用商品模块 3001。</p>
 */
@Getter
public enum MessageResultCode {

    /** 不能给自己发消息 */
    CANNOT_SEND_TO_SELF(5001, "不能给自己发送消息"),

    /** 接收人不存在 */
    RECEIVER_NOT_FOUND(5002, "接收人不存在"),

    /** 消息不存在或无权操作 */
    MESSAGE_NOT_FOUND(5003, "消息不存在或无权操作"),

    /** 参数不合法 */
    PARAM_ILLEGAL(400, "参数不合法");

    private final Integer code;
    private final String message;

    MessageResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
