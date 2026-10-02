package com.campus.trade.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品详情（v0.05）。
 *
 * <p>在列表字段基础上补充：描述、图片列表、发布者详细信息、交易地点等。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductDetailVO extends ProductVO {

    private static final long serialVersionUID = 1L;

    /** 商品描述 */
    private String description;

    /** 期望交易地点 */
    private String tradePlace;

    /** 图片列表（来自 product_image 表，按 sort_order 排序） */
    private List<String> images;

    /** 卖家头像 */
    private String sellerAvatar;

    /** 卖家信用分 */
    private Integer sellerCreditScore;

    /** 卖家所在校区 */
    private String sellerCampus;

    /** 收藏量 */
    private Integer favoriteCount;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
