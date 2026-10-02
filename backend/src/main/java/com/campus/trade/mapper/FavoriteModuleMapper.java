package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.trade.entity.Favorite;
import com.campus.trade.vo.FavoriteVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 收藏模块 Mapper（v0.06）。
 *
 * <p>为什么不直接用脚手架已有的 {@code FavoriteMapper}：</p>
 * <ul>
 *   <li>{@code favorite} 有唯一索引 {@code uk_user_product(user_id, product_id)}，
 *       而实体又带 {@code @TableLogic} 逻辑删除 —— 逻辑删除后旧行仍在表里，
 *       再次收藏会撞唯一索引。所以"取消收藏"必须用 <b>物理删除</b>，
 *       这里用注解 SQL 显式删除，避免影响既有 Mapper。</li>
 *   <li>"我的收藏"需要 favorite + product + category + user 四表关联分页，
 *       用 MyBatis-Plus 的 IPage 承接（分页插件会自动生成 count 语句）。</li>
 * </ul>
 */
@Mapper
public interface FavoriteModuleMapper extends BaseMapper<Favorite> {

    /** 取消收藏：物理删除（按用户 + 商品） */
    @Delete("DELETE FROM favorite WHERE user_id = #{userId} AND product_id = #{productId}")
    int physicalDelete(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * 我的收藏分页（只包含「上架中」的商品）。
     *
     * <p>INNER JOIN product 并在 ON 条件里限定 deleted = 0 AND status = 1，
     * 保证已下架/已删除的商品不会出现在收藏列表中，且分页总数正确。</p>
     */
    @Select("""
            SELECT f.id            AS favorite_id,
                   f.create_time   AS favorite_time,
                   p.id            AS product_id,
                   p.title         AS title,
                   p.price         AS price,
                   p.original_price AS original_price,
                   p.cover_image   AS cover_image,
                   p.condition_level AS condition_level,
                   p.campus        AS campus,
                   p.status        AS product_status,
                   p.seller_id     AS seller_id,
                   p.view_count    AS view_count,
                   p.create_time   AS product_create_time,
                   c.name          AS category_name,
                   u.nickname      AS seller_nickname
            FROM favorite f
            INNER JOIN product p ON p.id = f.product_id AND p.deleted = 0 AND p.status = 1
            LEFT JOIN category c ON c.id = p.category_id
            LEFT JOIN `user` u ON u.id = p.seller_id
            WHERE f.deleted = 0 AND f.user_id = #{userId}
            ORDER BY f.create_time DESC, f.id DESC
            """)
    IPage<FavoriteVO> selectFavoritePage(IPage<FavoriteVO> page, @Param("userId") Long userId);
}
