package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.entity.Order;
import com.campus.trade.mapper.OrderMapper;
import com.campus.trade.service.OrderService;
import org.springframework.stereotype.Service;

/**
 * 订单 业务实现。
 */
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {
}
