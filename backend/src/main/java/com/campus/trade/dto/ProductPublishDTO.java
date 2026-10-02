package com.campus.trade.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 商品发布请求参数（v0.05）。
 *
 * <p>对应接口：<code>POST /api/product/publish</code>（必须登录，卖家 = 当前登录用户）</p>
 */
@Data
public class ProductPublishDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品标题 */
    @NotBlank(message = "商品名称不能为空")
    @Size(min = 2, max = 100, message = "商品名称长度必须为 2~100 个字符")
    private String title;

    /** 商品描述 */
    @Size(max = 2000, message = "商品描述不能超过 2000 个字符")
    private String description;

    /** 分类ID */
    @NotNull(message = "请选择商品分类")
    private Long categoryId;

    /** 售价（元） */
    @NotNull(message = "请填写售价")
    @DecimalMin(value = "0.01", message = "售价必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "售价最多 8 位整数、2 位小数")
    private BigDecimal price;

    /** 原价（元），选填 */
    @DecimalMin(value = "0.00", message = "原价不能为负数")
    @Digits(integer = 8, fraction = 2, message = "原价最多 8 位整数、2 位小数")
    private BigDecimal originalPrice;

    /** 成色：1全新 2几乎全新 3轻微使用痕迹 4明显使用痕迹 */
    @NotNull(message = "请选择商品成色")
    @Min(value = 1, message = "成色取值 1~4")
    @Max(value = 4, message = "成色取值 1~4")
    private Integer conditionLevel;

    /** 交易校区 */
    @Size(max = 100, message = "校区名称不能超过 100 个字符")
    private String campus;

    /** 期望交易地点 */
    @Size(max = 100, message = "交易地点不能超过 100 个字符")
    private String tradePlace;

    /** 图片地址列表（由 /api/product/upload 返回），最多 9 张，第一张作为封面 */
    @Size(max = 9, message = "最多上传 9 张图片")
    private List<String> imageUrls;
}
