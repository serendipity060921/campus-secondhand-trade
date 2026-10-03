package com.campus.trade.mapper;

import com.campus.trade.vo.NameCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台统计 Mapper（v0.13）。
 *
 * <p>看板需要的聚合查询（按天趋势、分类分布、状态分布）全部用 SQL 聚合完成，
 * 不把明细数据拉到应用层再统计 —— 数据量增长时这是必须的做法。</p>
 */
@Mapper
public interface AdminStatMapper {

    /** 近 N 天每日新增用户 */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS label, COUNT(*) AS value "
            + "FROM `user` WHERE deleted = 0 AND create_time >= #{since} "
            + "GROUP BY label ORDER BY label")
    List<NameCountVO> countNewUsersByDay(@Param("since") LocalDateTime since);

    /** 近 N 天每日新增商品 */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS label, COUNT(*) AS value "
            + "FROM product WHERE deleted = 0 AND create_time >= #{since} "
            + "GROUP BY label ORDER BY label")
    List<NameCountVO> countNewProductsByDay(@Param("since") LocalDateTime since);

    /** 近 N 天每日订单数与成交额（金额只统计已完成订单） */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS label, COUNT(*) AS value, "
            + "IFNULL(SUM(CASE WHEN status = 3 THEN amount ELSE 0 END), 0) AS amount "
            + "FROM orders WHERE deleted = 0 AND create_time >= #{since} "
            + "GROUP BY label ORDER BY label")
    List<NameCountVO> countOrdersByDay(@Param("since") LocalDateTime since);

    /** 商品分类分布（不含顶级分类、不含已删除商品） */
    @Select("SELECT c.name AS label, COUNT(p.id) AS value "
            + "FROM category c LEFT JOIN product p ON p.category_id = c.id AND p.deleted = 0 "
            + "WHERE c.deleted = 0 AND c.parent_id <> 0 "
            + "GROUP BY c.id, c.name HAVING value > 0 ORDER BY value DESC LIMIT 12")
    List<NameCountVO> countProductsByCategory();

    /** 订单状态分布 */
    @Select("SELECT CASE status WHEN 0 THEN '待交易' WHEN 3 THEN '已完成' WHEN 4 THEN '已取消' "
            + "ELSE CONCAT('预留状态', status) END AS label, COUNT(*) AS value "
            + "FROM orders WHERE deleted = 0 GROUP BY status ORDER BY status")
    List<NameCountVO> countOrdersByStatus();

    /** 商品状态分布 */
    @Select("SELECT CASE status WHEN 0 THEN '待审核' WHEN 1 THEN '在售' WHEN 2 THEN '审核不通过' "
            + "WHEN 3 THEN '已下架' WHEN 4 THEN '交易中' WHEN 5 THEN '已售出' END AS label, "
            + "COUNT(*) AS value FROM product WHERE deleted = 0 GROUP BY status ORDER BY status")
    List<NameCountVO> countProductsByStatus();

    /** 累计成交额（已完成订单） */
    @Select("SELECT IFNULL(SUM(amount), 0) FROM orders WHERE deleted = 0 AND status = 3")
    java.math.BigDecimal sumGmv();

    /** 近 N 天成交额 */
    @Select("SELECT IFNULL(SUM(amount), 0) FROM orders WHERE deleted = 0 AND status = 3 "
            + "AND create_time >= #{since}")
    java.math.BigDecimal sumGmvSince(@Param("since") LocalDateTime since);
}
