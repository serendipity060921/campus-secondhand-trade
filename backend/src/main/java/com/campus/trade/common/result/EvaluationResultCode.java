package com.campus.trade.common.result;

/**
 * 评价模块业务码（v0.18 / 第6批-㉑）。
 *
 * <p>码段划分遵循项目既有约定：2xxx 用户、3xxx 商品、4xxx 收藏、5xxx 私信、
 * 6xxx 订单、7xxx 分类、8xxx 管理端，本模块取 <b>9xxx</b>。</p>
 *
 * <p>注意：这些码都通过 {@code Result{code,message}} 返回（HTTP 200），
 * 与项目其它业务错误一致，便于前端统一按 code 分支处理。</p>
 */
public enum EvaluationResultCode {

    /** 订单不存在（或已被逻辑删除） */
    ORDER_NOT_FOUND(9001, "订单不存在"),

    /** 当前用户既不是买家也不是卖家 */
    NOT_PARTICIPANT(9002, "只有买卖双方可以评价该订单"),

    /** 订单还没走完流程 */
    ORDER_NOT_FINISHED(9003, "订单完成后才能评价"),

    /** 同一订单每人只能评一次（唯一索引兜底后转成业务码） */
    ALREADY_EVALUATED(9004, "你已经评价过这笔订单了"),

    /** 评分越界 */
    RATING_ILLEGAL(9005, "评分只能是 1~5 星"),

    /** 评价内容超长 */
    CONTENT_TOO_LONG(9006, "评价内容不能超过 500 字"),

    /** 被评价人不存在 */
    TARGET_NOT_FOUND(9007, "被评价的用户不存在");

    private final int code;
    private final String message;

    EvaluationResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
