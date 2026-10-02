package com.campus.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品列表查询参数（v0.05）。
 *
 * <p>对应接口：<code>GET /api/product/list</code></p>
 * <p>只返回「上架中（status = 1）」的商品。</p>
 */
@Data
public class ProductQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始 */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer page = 1;

    /** 每页条数 */
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer size = 12;

    /** 分类ID（选填，包含该分类及其子分类） */
    private Long categoryId;

    /** 关键词（选填，匹配商品名称或描述） */
    private String keyword;

    /** 最低价（选填） */
    private BigDecimal minPrice;

    /** 最高价（选填） */
    private BigDecimal maxPrice;

    /** 排序：new（默认，最新发布）/ priceAsc / priceDesc / hot（浏览量） */
    private String sort = "new";

    /* ---------- 由 Controller/Service 内部使用，不对外暴露 ---------- */

    /** 卖家ID（"我的商品"使用，普通列表不传） */
    private transient Long sellerId;

    /** 状态（"我的商品"使用；普通列表固定为 1） */
    private transient Integer status;
}
