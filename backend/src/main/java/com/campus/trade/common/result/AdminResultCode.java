package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 管理后台业务状态码（v0.13），沿用 8xxx 段。
 */
@Getter
public enum AdminResultCode {

    /** 举报不存在 */
    REPORT_NOT_FOUND(8001, "举报记录不存在"),
    /** 举报已处理过 */
    REPORT_ALREADY_HANDLED(8002, "该举报已处理，无需重复操作"),
    /** 当前商品状态不允许审核 */
    AUDIT_STATUS_ILLEGAL(8003, "只有「待审核」状态的商品才能审核"),
    /** 不能禁用自己 */
    CANNOT_DISABLE_SELF(8004, "不能禁用当前登录的管理员账号"),
    /** 不能审核/操作用户自己？保留 */
    USER_STATUS_ILLEGAL(8005, "用户状态只能为 1（正常）或 0（禁用）"),
    /** 不能举报自己的内容 */
    CANNOT_REPORT_SELF(8006, "不能举报自己发布的内容"),
    /** 举报对象不存在 */
    REPORT_TARGET_NOT_FOUND(8007, "举报的对象不存在");

    private final Integer code;
    private final String message;

    AdminResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
