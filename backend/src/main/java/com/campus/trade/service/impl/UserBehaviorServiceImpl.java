package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.config.RecommendProperties;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.UserBehavior;
import com.campus.trade.mapper.UserBehaviorMapper;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserBehaviorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户行为服务实现（v0.11）。
 *
 * <p>写入策略（幂等 upsert）：</p>
 * <ol>
 *   <li>先按 (user_id, product_id, behavior_type) 查已有记录；</li>
 *   <li>存在则累加 behaviorCount、刷新 updateTime；</li>
 *   <li>不存在则插入；并发下若唯一索引冲突（DuplicateKeyException）则转为一次累加更新，
 *       与 v0.10 修复 BUG-04 的思路一致 —— 幂等，不产生重复行。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBehaviorServiceImpl extends ServiceImpl<UserBehaviorMapper, UserBehavior>
        implements UserBehaviorService {

    private final ProductService productService;
    private final RecommendProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(Long userId, Long productId, int behaviorType) {
        if (userId == null || productId == null) {
            return;
        }
        // 行为权重来自配置（1浏览 2收藏 3私信 4下单）
        double weightValue = properties.getBehaviorWeights()
                .getOrDefault(behaviorType, 1.0);
        BigDecimal weight = BigDecimal.valueOf(weightValue);

        // 冗余分类ID，便于按分类聚合用户偏好（省一次 JOIN）
        Long categoryId = null;
        Product product = productService.getById(productId);
        if (product != null) {
            categoryId = product.getCategoryId();
        }

        UserBehavior exists = getOne(new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .eq(UserBehavior::getProductId, productId)
                .eq(UserBehavior::getBehaviorType, behaviorType));

        if (exists != null) {
            exists.setBehaviorCount((exists.getBehaviorCount() == null ? 1 : exists.getBehaviorCount()) + 1);
            exists.setWeight(weight);
            exists.setUpdateTime(LocalDateTime.now());
            updateById(exists);
            return;
        }

        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setProductId(productId);
        behavior.setBehaviorType(behaviorType);
        behavior.setCategoryId(categoryId);
        behavior.setWeight(weight);
        behavior.setBehaviorCount(1);
        behavior.setDeleted(0);
        try {
            save(behavior);
        } catch (DuplicateKeyException e) {
            // 并发下的唯一索引冲突：退化为累加更新，保证幂等
            UserBehavior again = getOne(new LambdaQueryWrapper<UserBehavior>()
                    .eq(UserBehavior::getUserId, userId)
                    .eq(UserBehavior::getProductId, productId)
                    .eq(UserBehavior::getBehaviorType, behaviorType));
            if (again != null) {
                again.setBehaviorCount((again.getBehaviorCount() == null ? 1 : again.getBehaviorCount()) + 1);
                again.setUpdateTime(LocalDateTime.now());
                updateById(again);
            }
        }
    }

    @Override
    public List<UserBehavior> listByUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return list(new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .orderByDesc(UserBehavior::getWeight)
                .orderByDesc(UserBehavior::getUpdateTime));
    }

    @Override
    public int userCountOf(Long productId) {
        if (productId == null) {
            return 0;
        }
        return (int) count(new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getProductId, productId));
    }
}
