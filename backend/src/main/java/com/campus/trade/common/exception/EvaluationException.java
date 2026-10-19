package com.campus.trade.common.exception;

import com.campus.trade.common.result.EvaluationResultCode;

/**
 * 评价模块业务异常（v0.18 / 第6批-㉑）。
 *
 * <p>与其它模块保持同一形态：继承 {@link BusinessException}，
 * 由既有 {@code GlobalExceptionHandler} 统一转成 <code>{code, message}</code>。</p>
 */
public class EvaluationException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public EvaluationException(EvaluationResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public EvaluationException(Integer code, String message) {
        super(code, message);
    }

    /* ---------------- 语义化工厂方法 ---------------- */

    public static EvaluationException orderNotFound() {
        return new EvaluationException(EvaluationResultCode.ORDER_NOT_FOUND);
    }

    public static EvaluationException notParticipant() {
        return new EvaluationException(EvaluationResultCode.NOT_PARTICIPANT);
    }

    public static EvaluationException orderNotFinished() {
        return new EvaluationException(EvaluationResultCode.ORDER_NOT_FINISHED);
    }

    public static EvaluationException alreadyEvaluated() {
        return new EvaluationException(EvaluationResultCode.ALREADY_EVALUATED);
    }

    public static EvaluationException ratingIllegal() {
        return new EvaluationException(EvaluationResultCode.RATING_ILLEGAL);
    }

    public static EvaluationException contentTooLong() {
        return new EvaluationException(EvaluationResultCode.CONTENT_TOO_LONG);
    }

    public static EvaluationException targetNotFound() {
        return new EvaluationException(EvaluationResultCode.TARGET_NOT_FOUND);
    }
}
