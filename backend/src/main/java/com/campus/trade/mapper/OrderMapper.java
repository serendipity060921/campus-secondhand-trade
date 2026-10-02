package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单 Mapper（orders 表）。
 *
 * <p>继承 BaseMapper 后即拥有单表增删改查、分页、逻辑删除能力，
 * 复杂多表 SQL 写到 resources/mapper/OrderMapper.xml 中。</p>
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
