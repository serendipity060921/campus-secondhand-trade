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

    /**
     * 该分类下**在售**商品件数（v0.16 新增，供首页分类色带与"目录统计"展示真实分布）。
     *
     * <p>一级分类的件数包含其所有子分类：色带按一级分类展示，用户看到的是"这一类一共有多少件"。
     * 统计口径与首页商品列表一致（deleted=0 且 status=1），因此各级件数之和等于列表总数。</p>
     */
    private Long productCount;
}
