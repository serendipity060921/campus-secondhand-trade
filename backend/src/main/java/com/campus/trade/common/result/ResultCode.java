package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 业务状态码枚举。
 *
 * <p>约定：200 成功；4xx 客户端错误；5xx 服务端错误；1xxx 为业务自定义错误。</p>
 */
@Getter
public enum ResultCode {

    /* ---------- 通用 ---------- */
    SUCCESS(200, "success"),
    ERROR(500, "服务器内部错误"),
    PARAM_ERROR(400, "参数校验失败"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有操作权限"),

    /* ---------- 业务（脚手架阶段预留） ---------- */
    BUSINESS_ERROR(1000, "业务处理失败"),
    DATA_NOT_EXIST(1001, "数据不存在"),
    DATA_ALREADY_EXIST(1002, "数据已存在");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
