package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.entity.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * 留言/私信 Mapper（message 表）。
 *
 * <p>继承 BaseMapper 后即拥有单表增删改查、分页、逻辑删除能力，
 * 复杂多表 SQL 写到 resources/mapper/MessageMapper.xml 中。</p>
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
