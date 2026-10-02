package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品分类 Mapper（category 表）。
 *
 * <p>继承 BaseMapper 后即拥有单表增删改查、分页、逻辑删除能力，
 * 复杂多表 SQL 写到 resources/mapper/CategoryMapper.xml 中。</p>
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
