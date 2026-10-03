package com.campus.trade.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 推荐结果条目（v0.11 推荐模块）。
 *
 * <p>在商品卡片所需字段之外，额外返回算法的"可解释信息"：
 * 三路得分、来源类型、推荐理由 —— 这既是产品体验（给用户看"为什么推荐"），
 * 也是论文实验部分做消融分析（分别去掉协同过滤/内容/热门看效果变化）的依据。</p>
 */
@Data
public class RecommendItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /* ---------------- 商品卡片字段 ---------------- */
    private Long productId;
    private String title;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String coverImage;
    private Long categoryId;
    private String categoryName;
    private Long sellerId;
    private String sellerNickname;
    private Integer conditionLevel;
    private String campus;
    private Integer status;
    private Integer viewCount;
    private Integer favoriteCount;
    private LocalDateTime createTime;

    /* ---------------- 算法可解释字段 ---------------- */
    /** 综合推荐得分（0~1，已归一化） */
    private Double score;
    /** 协同过滤得分（归一化后） */
    private Double cfScore;
    /** 内容匹配得分（归一化后） */
    private Double contentScore;
    /** 热门度得分（归一化后） */
    private Double hotScore;
    /** 来源类型：1协同过滤 2内容匹配 3热门 4混合 */
    private Integer sourceType;
    /** 来源中文标签，例如「协同过滤」「同分类热门」 */
    private String sourceLabel;
    /** 主要推荐理由（一句话） */
    private String reason;
    /** 全部推荐理由（前端可做 tooltip 展示） */
    private List<String> reasons;
}
