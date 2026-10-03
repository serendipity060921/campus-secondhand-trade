package com.campus.trade.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 商品流行度聚合结果（v0.11 推荐模块内部用）。
 */
@Data
public class ProductPopularityDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    private Long productId;

    /** 产生过行为的去重用户数（协同过滤相似度分母 / 热门排序依据） */
    private Integer userCount;

    /** 行为累计次数 */
    private Integer behaviorCount;
}
