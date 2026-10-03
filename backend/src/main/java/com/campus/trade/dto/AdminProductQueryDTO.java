package com.campus.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端商品查询条件（v0.13）。
 *
 * <p>与前台 ProductQueryDTO 的区别：<b>不限制状态</b>（后台要能看到待审核/已下架/已售出），
 * 并支持按卖家ID筛选。</p>
 */
@Data
public class AdminProductQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Min(value = 1, message = "页码必须大于 0")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer size = 10;

    /** 状态筛选：0待审核 1在售 2审核不通过 3已下架 4交易中 5已售出；为空表示全部 */
    private Integer status;

    /** 关键词（商品名称模糊） */
    private String keyword;

    /** 卖家ID筛选 */
    private Long sellerId;
}
