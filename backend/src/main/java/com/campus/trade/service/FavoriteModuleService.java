package com.campus.trade.service;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.vo.FavoriteStatusVO;
import com.campus.trade.vo.FavoriteVO;

/**
 * 收藏模块业务（v0.06）。
 *
 * <p>不修改既有 UserService / ProductService 的业务方法，只通过它们提供的
 * 通用 CRUD 能力读写数据；本接口承载收藏自身的业务规则。</p>
 */
public interface FavoriteModuleService {

    /**
     * 收藏 / 取消收藏。
     *
     * @param productId 商品ID
     * @param type      1 = 收藏，2 = 取消收藏
     * @param userId    当前登录用户
     * @return 操作后的收藏状态与收藏总数
     * @throws com.campus.trade.common.exception.ProductException  商品不存在（3001）
     * @throws com.campus.trade.common.exception.FavoriteException 收藏自己的商品(4002) / 重复收藏(4001) / 未收藏(4003) / 类型非法(400)
     */
    FavoriteStatusVO operate(Long productId, Integer type, Long userId);

    /** 我的收藏分页（只返回上架商品） */
    PageResult<FavoriteVO> pageMine(long page, long size, Long userId);

    /** 当前用户是否已收藏该商品 */
    FavoriteStatusVO hasFavorite(Long productId, Long userId);
}
