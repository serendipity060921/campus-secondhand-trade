package com.campus.trade.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 分类信息（v0.05）。
 *
 * <p>只返回前端需要的字段，避免把 deleted 等内部字段暴露出去。</p>
 */
@Data
public class CategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类ID */
    private Long id;

    /** 父分类ID，0 表示一级分类 */
    private Long parentId;

    /** 分类名称 */
    private String name;

    /** 图标地址 */
    private String icon;

    /** 排序值 */
    private Integer sortOrder;
}
