package com.campus.trade.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端商品视图（v0.13）。
 *
 * <p>相比前台 ProductVO，这里额外包含：所有状态（含待审核/已下架）、
 * 审核字段（审核人/时间/意见）与卖家信息，供后台列表与审核弹窗使用。</p>
 */
@Data
public class AdminProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String description;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String coverImage;
    private Long categoryId;
    private String categoryName;
    private Integer conditionLevel;
    private String campus;
    private Integer status;
    private String statusLabel;
    private Integer viewCount;
    private Integer favoriteCount;
    private LocalDateTime createTime;

    /* 卖家信息 */
    private Long sellerId;
    private String sellerNickname;
    private String sellerUsername;
    private Integer sellerCreditScore;

    /* 审核信息 */
    private Long auditorId;
    private String auditorName;
    private LocalDateTime auditTime;
    private String auditRemark;
}
