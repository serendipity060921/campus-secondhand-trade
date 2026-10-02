package com.campus.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品分类实体（category）。
 *
 * <p>{@code parentId = 0} 表示一级分类，其余为二级分类。</p>
 */
@Data
@TableName("category")
public class Category implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分类ID，主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 父分类ID，0 表示一级分类 */
    private Long parentId;

    /** 分类名称 */
    private String name;

    /** 分类图标地址 */
    private String icon;

    /** 排序值，越小越靠前 */
    private Integer sortOrder;

    /** 状态：1启用 0停用 */
    private Integer status;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
