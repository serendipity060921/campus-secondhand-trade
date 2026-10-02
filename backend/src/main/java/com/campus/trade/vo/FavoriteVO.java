package com.campus.trade.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 我的收藏列表项（v0.06）。
 *
 * <p>由 favorite + product（+ category / user）关联查询直接映射，
 * 字段与 SQL 别名一一对应（下划线自动转驼峰）。</p>
 */
@Data
public class FavoriteVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 收藏记录ID */
    private Long favoriteId;

    /** 收藏时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime favoriteTime;

    /** 商品ID */
    private Long productId;

    /** 商品名称 */
    private String title;

    /** 售价 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 封面图（可能为空，前端按分类显示占位图） */
    private String coverImage;

    /** 分类名称 */
    private String categoryName;

    /** 成色 */
    private Integer conditionLevel;

    /** 交易校区 */
    private String campus;

    /** 商品状态（列表只返回 1 在售） */
    private Integer productStatus;

    /** 卖家ID */
    private Long sellerId;

    /** 卖家昵称 */
    private String sellerNickname;

    /** 浏览量 */
    private Integer viewCount;

    /** 商品发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime productCreateTime;
}
