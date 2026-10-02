package com.campus.trade.common.exception;

import com.campus.trade.common.result.FavoriteResultCode;

/**
 * 收藏模块业务异常（v0.06）。
 *
 * <p>继承脚手架原有的 {@link BusinessException}，由既有全局异常处理器统一转为
 * <code>{code, message}</code>；"商品不存在" 直接复用 {@link ProductException#notFound()}（3001）。</p>
 */
public class FavoriteException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public FavoriteException(FavoriteResultCode resultCode) {
        super(resultCode.getCode(), resultCode.getMessage());
    }

    public FavoriteException(Integer code, String message) {
        super(code, message);
    }

    /* ---------------- 语义化工厂方法 ---------------- */

    /** 重复收藏 */
    public static FavoriteException alreadyFavorited() {
        return new FavoriteException(FavoriteResultCode.ALREADY_FAVORITED);
    }

    /** 不能收藏自己发布的商品 */
    public static FavoriteException cannotFavoriteOwn() {
        return new FavoriteException(FavoriteResultCode.CANNOT_FAVORITE_OWN);
    }

    /** 尚未收藏，无法取消 */
    public static FavoriteException notFavorited() {
        return new FavoriteException(FavoriteResultCode.NOT_FAVORITED);
    }

    /** 操作类型非法 */
    public static FavoriteException typeIllegal(String detail) {
        return new FavoriteException(FavoriteResultCode.TYPE_ILLEGAL.getCode(),
                FavoriteResultCode.TYPE_ILLEGAL.getMessage() + "：" + detail);
    }
}
