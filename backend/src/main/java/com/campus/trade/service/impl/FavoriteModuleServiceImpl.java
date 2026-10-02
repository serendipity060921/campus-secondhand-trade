package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.exception.FavoriteException;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.entity.Favorite;
import com.campus.trade.entity.Product;
import com.campus.trade.mapper.FavoriteModuleMapper;
import com.campus.trade.service.FavoriteModuleService;
import com.campus.trade.service.FavoriteService;
import com.campus.trade.service.ProductService;
import com.campus.trade.vo.FavoriteStatusVO;
import com.campus.trade.vo.FavoriteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 收藏模块业务实现（v0.06）。
 *
 * <p>业务规则：</p>
 * <ol>
 *   <li>商品必须存在（否则 3001）；</li>
 *   <li>不能收藏自己发布的商品（4002）；</li>
 *   <li>不能重复收藏（4001），由「先查再插 + 数据库唯一索引 uk_user_product」双重保证；</li>
 *   <li>取消收藏必须已收藏（4003），采用物理删除以避开逻辑删除与唯一索引的冲突；</li>
 *   <li>每次操作后把 product.favorite_count 与收藏表真实条数对齐（不做 ±1 累加，避免数据漂移）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteModuleServiceImpl implements FavoriteModuleService {

    /** 操作类型：收藏 */
    private static final int TYPE_FAVORITE = 1;
    /** 操作类型：取消收藏 */
    private static final int TYPE_CANCEL = 2;

    private final FavoriteService favoriteService;
    private final FavoriteModuleMapper favoriteModuleMapper;
    private final ProductService productService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FavoriteStatusVO operate(Long productId, Integer type, Long userId) {
        int operateType = type == null ? TYPE_FAVORITE : type;
        if (operateType != TYPE_FAVORITE && operateType != TYPE_CANCEL) {
            throw FavoriteException.typeIllegal("type 只能为 1（收藏）或 2（取消收藏）");
        }

        // ① 商品必须存在
        Product product = productService.getById(productId);
        if (product == null) {
            throw ProductException.notFound();
        }

        // ② 当前是否已收藏
        boolean exists = countFavorite(userId, productId) > 0;

        if (operateType == TYPE_FAVORITE) {
            // ③ 不能收藏自己的商品
            if (Objects.equals(product.getSellerId(), userId)) {
                throw FavoriteException.cannotFavoriteOwn();
            }
            // ④ 不能重复收藏
            if (exists) {
                throw FavoriteException.alreadyFavorited();
            }
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setProductId(productId);
            favorite.setDeleted(0);
            favoriteService.save(favorite);
            log.info("[收藏成功] userId={} productId={} title={}", userId, productId, product.getTitle());
        } else {
            // ⑤ 取消收藏：必须已收藏
            if (!exists) {
                throw FavoriteException.notFavorited();
            }
            favoriteModuleMapper.physicalDelete(userId, productId);
            log.info("[取消收藏] userId={} productId={}", userId, productId);
        }

        int total = syncFavoriteCount(productId);
        return FavoriteStatusVO.builder()
                .productId(productId)
                .favorited(operateType == TYPE_FAVORITE)
                .favoriteCount(total)
                .build();
    }

    @Override
    public PageResult<FavoriteVO> pageMine(long page, long size, Long userId) {
        Page<FavoriteVO> pageParam = new Page<>(page, size);
        IPage<FavoriteVO> result = favoriteModuleMapper.selectFavoritePage(pageParam, userId);
        return new PageResult<>(result.getTotal(), result.getPages(),
                result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public FavoriteStatusVO hasFavorite(Long productId, Long userId) {
        boolean favorited = countFavorite(userId, productId) > 0;
        long total = favoriteService.count(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getProductId, productId));
        return FavoriteStatusVO.builder()
                .productId(productId)
                .favorited(favorited)
                .favoriteCount((int) total)
                .build();
    }

    /* ==================== 内部方法 ==================== */

    /** 某用户是否收藏了某商品（返回条数，0 表示未收藏） */
    private long countFavorite(Long userId, Long productId) {
        return favoriteService.count(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getProductId, productId));
    }

    /**
     * 把 product.favorite_count 与收藏表真实条数对齐。
     *
     * <p>用"查真实条数再回写"而不是 +1/-1，避免并发或异常场景下计数漂移。</p>
     */
    private int syncFavoriteCount(Long productId) {
        long total = favoriteService.count(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getProductId, productId));
        productService.update(new LambdaUpdateWrapper<Product>()
                .set(Product::getFavoriteCount, (int) total)
                .eq(Product::getId, productId));
        return (int) total;
    }
}
