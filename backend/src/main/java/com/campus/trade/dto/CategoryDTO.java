package com.campus.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 分类新增/修改请求参数（v0.09）。
 *
 * <p>对应接口：<code>POST /api/category/add</code>（新增）、
 * <code>PUT /api/category/update</code>（修改，需带 id）</p>
 */
@Data
public class CategoryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类ID（修改时必填，新增时忽略） */
    private Long id;

    /** 上级分类ID：0 或 null 表示一级分类 */
    @Min(value = 0, message = "上级分类ID不能为负数")
    private Long parentId = 0L;

    /**
     * 分类名称（同一上级下不能重名）。
     * 新增时必填（由 Service 校验），修改时可选：不传表示保持原名。
     */
    @Size(min = 2, max = 50, message = "分类名称长度必须为 2~50 个字符")
    private String name;

    /** 分类图标地址 */
    @Size(max = 255, message = "图标地址过长")
    private String icon;

    /** 排序值，越小越靠前 */
    private Integer sortOrder = 0;

    /** 状态：1 启用 0 停用 */
    @Min(value = 0, message = "状态取值 0 或 1")
    @Max(value = 1, message = "状态取值 0 或 1")
    private Integer status = 1;
}
