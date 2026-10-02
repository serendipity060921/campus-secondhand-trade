package com.campus.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体（product）。
 *
 * <p>状态流转：0待审核 → 1在售 → 4交易中 → 5已售出；
 * 0待审核 → 2审核不通过（修改后重新提交）；1在售 → 3已下架。</p>
 */
@Data
@TableName("product")
public class Product implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID，主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 商品标题 */
    private String title;

    /** 商品描述 */
    private String description;

    /** 所属分类ID */
    private Long categoryId;

    /** 卖家用户ID */
    private Long sellerId;

    /** 售价（元） */
    private BigDecimal price;

    /** 原价（元） */
    private BigDecimal originalPrice;

    /** 封面图地址 */
    private String coverImage;

    /** 成色：1全新 2几乎全新 3轻微使用痕迹 4明显使用痕迹 */
    private Integer conditionLevel;

    /** 交易校区 */
    private String campus;

    /** 期望交易地点 */
    private String tradePlace;

    /** 状态：0待审核 1在售 2审核不通过 3已下架 4交易中 5已售出 */
    private Integer status;

    /** 审核意见（驳回理由） */
    private String auditRemark;

    /** 审核管理员ID */
    private Long auditorId;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 浏览量 */
    private Integer viewCount;

    /** 收藏量 */
    private Integer favoriteCount;

    /** 上架时间 */
    private LocalDateTime shelfTime;

    /** 售出时间 */
    private LocalDateTime soldTime;

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
