package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.trade.entity.UserBehavior;

import java.util.List;

/**
 * 用户行为服务（v0.11 推荐模块数据源）。
 *
 * <p>职责：把业务动作（浏览商品、收藏、私信、下单）落成行为记录，供推荐算法消费。
 * 记录行为必须<b>不影响主业务</b>：所有调用点都做了异常吞掉处理，
 * 行为表写入失败不能导致"收藏失败""下单失败"。</p>
 */
public interface UserBehaviorService extends IService<UserBehavior> {

    /** 行为类型常量 */
    int TYPE_VIEW = 1;
    int TYPE_FAVORITE = 2;
    int TYPE_MESSAGE = 3;
    int TYPE_ORDER = 4;

    /**
     * 记录一次用户行为（幂等：同一用户对同一商品的同类行为只保留一行并累加次数）。
     *
     * @param userId       用户ID（为空则忽略）
     * @param productId    商品ID（为空则忽略）
     * @param behaviorType 行为类型 1浏览 2收藏 3私信 4下单
     */
    void record(Long userId, Long productId, int behaviorType);

    /** 查询某用户的全部行为（按兴趣权重降序） */
    List<UserBehavior> listByUser(Long userId);

    /** 某商品的行为人数（用于相似度分母，单商品场景） */
    int userCountOf(Long productId);
}
