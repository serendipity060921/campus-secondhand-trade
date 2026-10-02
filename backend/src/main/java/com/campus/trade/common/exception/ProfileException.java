package com.campus.trade.common.exception;

import com.campus.trade.common.result.ProfileResultCode;

/**
 * 个人资料模块业务异常（v0.09）。
 */
public class ProfileException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public ProfileException(ProfileResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public ProfileException(Integer code, String message) {
        super(code, message);
    }

    /** 手机号已被其他账号绑定 */
    public static ProfileException phoneExists() {
        return new ProfileException(ProfileResultCode.PHONE_EXISTS);
    }

    /** 参数不合法 */
    public static ProfileException paramIllegal(String detail) {
        return new ProfileException(ProfileResultCode.PARAM_ILLEGAL.getCode(),
                ProfileResultCode.PARAM_ILLEGAL.getMessage() + "：" + detail);
    }
}
