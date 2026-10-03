package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.dto.ItemCoOccurrenceDTO;
import com.campus.trade.dto.ProductPopularityDTO;
import com.campus.trade.entity.UserBehavior;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 用户行为 Mapper（v0.11 推荐模块）。
 *
 * <p>推荐算法的两个核心聚合（商品流行度、物品共现矩阵）用 SQL 完成，
 * 避免把全量行为记录加载到 JVM 内存 —— 这也是论文"推荐算法工程实现"一节要写的点：
 * 共现矩阵只在需要时按"当前用户交互过的商品"过滤后聚合，结果集规模为 O(用户行为数 × 平均共现数)。</p>
 */
@Mapper
public interface UserBehaviorMapper extends BaseMapper<UserBehavior> {

    /**
     * 商品流行度：每个商品被多少个<b>去重用户</b>产生过行为。
     * 用于协同过滤的物品相似度分母（cosine 相似度）以及冷启动热门榜。
     */
    @Select("SELECT product_id AS productId, COUNT(DISTINCT user_id) AS userCount, "
            + "SUM(behavior_count) AS behaviorCount "
            + "FROM user_behavior WHERE deleted = 0 GROUP BY product_id")
    List<ProductPopularityDTO> selectPopularity();

    /**
     * 物品共现矩阵（只取指定商品的共现）。
     *
     * <p>共现次数 co(i,j) = 同时对 i、j 产生过行为的用户数，用于计算物品相似度：</p>
     * <pre>
     *   sim(i, j) = co(i, j) / sqrt(pop(i) * pop(j))
     * </pre>
     *
     * @param sourceIds 当前用户交互过的商品ID集合（限定聚合范围，控制结果集大小）
     */
    @Select("<script>"
            + "SELECT b1.product_id AS sourceId, b2.product_id AS targetId, "
            + "       COUNT(DISTINCT b1.user_id) AS coCount "
            + "FROM user_behavior b1 "
            + "JOIN user_behavior b2 ON b2.user_id = b1.user_id AND b2.deleted = 0 "
            + "     AND b2.product_id &lt;&gt; b1.product_id "
            + "WHERE b1.deleted = 0 AND b1.product_id IN "
            + "<foreach collection='sourceIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY b1.product_id, b2.product_id"
            + "</script>")
    List<ItemCoOccurrenceDTO> selectCoOccurrence(@Param("sourceIds") Collection<Long> sourceIds);
}
