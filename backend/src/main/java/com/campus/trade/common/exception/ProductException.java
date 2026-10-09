package com.campus.trade.common.exception;

import com.campus.trade.common.result.ProductResultCode;

/**
 * 商品模块业务异常（v0.05）。
 *
 * <p>继承脚手架原有的 {@link BusinessException}，由既有的全局异常处理器统一转换为
 * <code>{code, message}</code> 响应。覆盖需求中的：商品不存在、无权限、参数非法等。</p>
 */
public class ProductException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public ProductException(ProductResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public ProductException(Integer code, String message) {
        super(code, message);
    }

    /* ---------------- 语义化工厂方法 ---------------- */

    /** 商品不存在 */
    public static ProductException notFound() {
        return new ProductException(ProductResultCode.PRODUCT_NOT_FOUND);
    }

    /** 无权限操作他人商品 */
    public static ProductException noPermission() {
        return new ProductException(ProductResultCode.NO_PERMISSION);
    }

    /** 商品状态不允许该操作 */
    public static ProductException statusIllegal() {
        return new ProductException(ProductResultCode.STATUS_ILLEGAL);
    }

    /** 分类不存在 */
    public static ProductException categoryNotFound() {
        return new ProductException(ProductResultCode.CATEGORY_NOT_FOUND);
    }

    /** 参数非法（附带具体原因） */
    public static ProductException paramIllegal(String detail) {
        return new ProductException(ProductResultCode.PARAM_ILLEGAL.getCode(),
                ProductResultCode.PARAM_ILLEGAL.getMessage() + "：" + detail);
    }

    /**
     * 内容机审拒绝（v0.17 内容治理第一层）
     *
     * <p>附带命中的具体原因，用户据此知道要修改哪里。</p>
     */
    public static ProductException contentRejected(String detail) {
        String msg = ProductResultCode.CONTENT_REJECTED.getMessage();
        if (detail != null && !detail.isBlank()) {
            msg = msg + "：" + detail;
        }
        return new ProductException(ProductResultCode.CONTENT_REJECTED.getCode(), msg);
    }

    /** 上传文件为空 */
    public static ProductException fileEmpty() {
        return new ProductException(ProductResultCode.FILE_EMPTY);
    }

    /** 图片格式不支持 */
    public static ProductException fileTypeNotAllowed(String ext) {
        return new ProductException(ProductResultCode.FILE_TYPE_NOT_ALLOWED.getCode(),
                ProductResultCode.FILE_TYPE_NOT_ALLOWED.getMessage() + "（当前：" + ext + "）");
    }

    /** 图片过大 */
    public static ProductException fileTooLarge() {
        return new ProductException(ProductResultCode.FILE_SIZE_EXCEEDED);
    }

    /** 图片保存失败 */
    public static ProductException uploadError(String detail) {
        return new ProductException(ProductResultCode.FILE_UPLOAD_ERROR.getCode(),
                ProductResultCode.FILE_UPLOAD_ERROR.getMessage() + "（" + detail + "）");
    }

    /** 图片数量超限 */
    public static ProductException imageCountExceeded() {
        return new ProductException(ProductResultCode.IMAGE_COUNT_EXCEEDED);
    }
}
