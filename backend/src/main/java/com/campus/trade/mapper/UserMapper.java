package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper（user 表）。
 *
 * <p>继承 BaseMapper 后即拥有单表增删改查、分页、逻辑删除能力，
 * 复杂多表 SQL 写到 resources/mapper/UserMapper.xml 中。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
