package com.campus.trade.common.result;

import lombok.Getter;

/**
 * 商品模块业务状态码（v0.05）。
 *
 * <p>约定：3001~3099 为商品模块业务错误；400 表示参数非法。</p>
 */
@Getter
public enum ProductResultCode {

    /** 商品不存在 */
    PRODUCT_NOT_FOUND(3001, "商品不存在或已被删除"),

    /** 无权限操作他人商品 */
    NO_PERMISSION(3002, "无权操作他人发布的商品"),

    /** 商品当前状态不允许该操作 */
    STATUS_ILLEGAL(3003, "商品当前状态不允许该操作"),

    /** 分类不存在 */
    CATEGORY_NOT_FOUND(3004, "商品分类不存在，请重新选择分类"),

    /** 参数非法 */
    PARAM_ILLEGAL(400, "参数不合法"),

    /** 上传文件为空 */
    FILE_EMPTY(3005, "请选择要上传的图片"),

    /** 文件类型不支持 */
    FILE_TYPE_NOT_ALLOWED(3006, "图片格式不支持，仅支持 jpg / jpeg / png / gif / webp / bmp"),

    /** 文件过大 */
    FILE_SIZE_EXCEEDED(3007, "图片大小超出限制（单张最大 5MB）"),

    /** 文件保存失败 */
    FILE_UPLOAD_ERROR(3008, "图片上传失败，请稍后重试"),

    /** 图片数量超出限制 */
    IMAGE_COUNT_EXCEEDED(3009, "图片数量超出限制（最多 9 张）");

    private final Integer code;
    private final String message;

    ProductResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
