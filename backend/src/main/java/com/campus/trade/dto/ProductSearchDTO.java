package com.campus.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品搜索请求参数（v0.09）。
 *
 * <p>对应接口：<code>GET /api/product/search</code></p>
 */
@Data
public class ProductSearchDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 搜索关键词（按商品名称模糊匹配） */
    @Size(max = 50, message = "搜索关键词不能超过 50 个字符")
    private String keyword;

    /** 分类ID（选填；传一级分类时会自动包含其子分类） */
    private Long categoryId;

    /** 最低价（选填） */
    private BigDecimal minPrice;

    /** 最高价（选填） */
    private BigDecimal maxPrice;

    /** 排序：new（默认，最新）/ priceAsc / priceDesc / hot（最多浏览） */
    private String sort = "new";

    /** 页码 */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer page = 1;

    /** 每页条数 */
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer size = 12;
}
