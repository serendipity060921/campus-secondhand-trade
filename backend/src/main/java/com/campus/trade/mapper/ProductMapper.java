package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 商品 Mapper（product 表）。
 *
 * <p>继承 BaseMapper 后即拥有单表增删改查、分页、逻辑删除能力，
 * 复杂多表 SQL 写到 resources/mapper/ProductMapper.xml 中。</p>
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 按分类统计**在售**商品件数（v0.16 新增，供分类接口返回真实分布）。
     *
     * <p>口径与首页商品列表一致（deleted = 0 且 status = 1），因此各分类件数之和等于列表总数；
     * 一级分类的件数由其子分类在 Service 层汇总。</p>
     */
    @Select("SELECT category_id AS categoryId, COUNT(*) AS cnt FROM product "
            + "WHERE deleted = 0 AND status = 1 GROUP BY category_id")
    List<Map<String, Object>> countOnSaleGroupByCategory();
}
