package com.campus.trade.common.exception;

import com.campus.trade.common.result.MessageResultCode;

/**
 * 私信模块业务异常（v0.07）。
 *
 * <p>继承脚手架原有的 {@link BusinessException}，由既有全局异常处理器统一返回
 * <code>{code, message}</code>，无需修改原处理器。</p>
 */
public class MessageException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public MessageException(MessageResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public MessageException(Integer code, String message) {
        super(code, message);
    }

    /* ---------------- 语义化工厂方法 ---------------- */

    /** 不能给自己发消息 */
    public static MessageException cannotSendToSelf() {
        return new MessageException(MessageResultCode.CANNOT_SEND_TO_SELF);
    }

    /** 接收人不存在 */
    public static MessageException receiverNotFound() {
        return new MessageException(MessageResultCode.RECEIVER_NOT_FOUND);
    }

    /** 消息不存在或无权操作 */
    public static MessageException messageNotFound() {
        return new MessageException(MessageResultCode.MESSAGE_NOT_FOUND);
    }

    /** 参数不合法 */
    public static MessageException paramIllegal(String detail) {
        return new MessageException(MessageResultCode.PARAM_ILLEGAL.getCode(),
                MessageResultCode.PARAM_ILLEGAL.getMessage() + "：" + detail);
    }
}
