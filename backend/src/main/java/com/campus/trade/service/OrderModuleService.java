package com.campus.trade.service;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.OrderCreateDTO;
import com.campus.trade.dto.OrderStatusDTO;
import com.campus.trade.vo.OrderDetailVO;
import com.campus.trade.vo.OrderVO;

/**
 * 订单交易业务（v0.08）。
 *
 * <p>三态流程：待交易(0) → 已完成(3) / 已取消(4)；商品状态随之在
 * 在售(1) → 交易中(4) → 已售出(5) / 回到在售(1) 之间流转。</p>
 */
public interface OrderModuleService {

    /**
     * 创建订单（买家 = 当前登录用户）。
     *
     * @throws com.campus.trade.common.exception.ProductException 商品不存在(3001)
     * @throws com.campus.trade.common.exception.OrderException   不能买自己的商品(6002) / 商品已下架或已被预订(6001)
     */
    OrderDetailVO create(OrderCreateDTO dto, Long buyerId);

    /**
     * 修改订单状态：3 已完成 / 4 已取消（仅买卖双方可操作，且订单必须是待交易）。
     *
     * @throws com.campus.trade.common.exception.OrderException 订单不存在(6003) / 无权操作(6004) / 状态不允许(6005) / 参数非法(400)
     */
    OrderDetailVO changeStatus(OrderStatusDTO dto, Long userId);

    /** 我买到的订单（分页） */
    PageResult<OrderVO> pageBuyList(Long buyerId, Integer status, long page, long size);

    /** 我卖出的订单（分页） */
    PageResult<OrderVO> pageSellList(Long sellerId, Integer status, long page, long size);

    /**
     * 订单详情（仅买卖双方可查看）。
     *
     * @throws com.campus.trade.common.exception.OrderException 订单不存在(6003) / 无权查看(6004)
     */
    OrderDetailVO detail(Long orderId, Long userId);
}
