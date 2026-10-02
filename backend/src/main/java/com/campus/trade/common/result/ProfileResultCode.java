package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 个人资料模块业务状态码（v0.09）。
 *
 * <p>"用户不存在" 复用 v0.04 的 {@code UserResultCode.USER_NOT_FOUND}（2002）；
 * 头像上传的格式/大小错误复用 v0.05 文件服务（3006/3007）。</p>
 */
@Getter
public enum ProfileResultCode {

    /** 手机号已被占用 */
    PHONE_EXISTS(2006, "该手机号已被其他账号绑定"),

    /** 参数不合法 */
    PARAM_ILLEGAL(400, "参数不合法");

    private final Integer code;
    private final String message;

    ProfileResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
