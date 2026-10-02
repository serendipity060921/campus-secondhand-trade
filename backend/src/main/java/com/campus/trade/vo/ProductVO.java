package com.campus.trade.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品列表项（v0.05）。
 *
 * <p>列表页只需要少量字段，单独用 VO 承载，避免把实体里的审核字段等暴露给前端。</p>
 */
@Data
public class ProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    private Long id;

    /** 商品名称 */
    private String title;

    /** 售价 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 封面图（可能为空，前端会用占位图兜底） */
    private String coverImage;

    /** 分类ID */
    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    /** 卖家ID */
    private Long sellerId;

    /** 卖家昵称 */
    private String sellerNickname;

    /** 成色：1全新 2几乎全新 3轻微使用痕迹 4明显使用痕迹 */
    private Integer conditionLevel;

    /** 交易校区 */
    private String campus;

    /** 状态：0待审核 1在售 2审核不通过 3已下架 4交易中 5已售出 */
    private Integer status;

    /** 浏览量 */
    private Integer viewCount;

    /** 发布时间（= 创建时间） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
