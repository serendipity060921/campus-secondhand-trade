package com.campus.trade.common.exception;

import com.campus.trade.common.result.AdminResultCode;

/**
 * 管理后台业务异常（v0.13）。
 */
public class AdminException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public AdminException(AdminResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public AdminException(Integer code, String message) {
        super(code, message);
    }

    public static AdminException reportNotFound() {
        return new AdminException(AdminResultCode.REPORT_NOT_FOUND);
    }

    public static AdminException reportHandled() {
        return new AdminException(AdminResultCode.REPORT_ALREADY_HANDLED);
    }

    public static AdminException auditStatusIllegal() {
        return new AdminException(AdminResultCode.AUDIT_STATUS_ILLEGAL);
    }

    public static AdminException cannotDisableSelf() {
        return new AdminException(AdminResultCode.CANNOT_DISABLE_SELF);
    }

    public static AdminException userStatusIllegal() {
        return new AdminException(AdminResultCode.USER_STATUS_ILLEGAL);
    }

    public static AdminException cannotReportSelf() {
        return new AdminException(AdminResultCode.CANNOT_REPORT_SELF);
    }

    public static AdminException reportTargetNotFound() {
        return new AdminException(AdminResultCode.REPORT_TARGET_NOT_FOUND);
    }
}
