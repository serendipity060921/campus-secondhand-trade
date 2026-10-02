package com.campus.trade.common.exception;

import com.campus.trade.common.result.CategoryResultCode;

/**
 * 分类管理模块业务异常（v0.09）。
 */
public class CategoryException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public CategoryException(CategoryResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public CategoryException(Integer code, String message) {
        super(code, message);
    }

    /** 同级分类重名 */
    public static CategoryException nameExists() {
        return new CategoryException(CategoryResultCode.NAME_EXISTS);
    }

    /** 分类不存在 */
    public static CategoryException notFound() {
        return new CategoryException(CategoryResultCode.NOT_FOUND);
    }

    /** 上级分类不存在 */
    public static CategoryException parentNotFound() {
        return new CategoryException(CategoryResultCode.PARENT_NOT_FOUND);
    }

    /** 上级分类不能是自己 */
    public static CategoryException selfParent() {
        return new CategoryException(CategoryResultCode.SELF_PARENT);
    }

    /** 参数不合法 */
    public static CategoryException paramIllegal(String detail) {
        return new CategoryException(CategoryResultCode.PARAM_ILLEGAL.getCode(),
                CategoryResultCode.PARAM_ILLEGAL.getMessage() + "：" + detail);
    }
}
