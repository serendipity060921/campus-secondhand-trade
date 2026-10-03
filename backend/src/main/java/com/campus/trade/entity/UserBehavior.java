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
 * 用户行为实体（v0.11 推荐模块数据源）。
 *
 * <p>一个用户对一件商品的同类行为只保留一行（唯一索引 uk_user_product_type），
 * 重复发生则累加 {@code behaviorCount} 并刷新时间，避免浏览行为把表撑爆。</p>
 *
 * <table border="1">
 *   <caption>behavior_type 取值</caption>
 *   <tr><th>值</th><th>含义</th><th>默认权重</th></tr>
 *   <tr><td>1</td><td>浏览商品详情</td><td>1</td></tr>
 *   <tr><td>2</td><td>收藏</td><td>3</td></tr>
 *   <tr><td>3</td><td>私信/留言（带商品）</td><td>2</td></tr>
 *   <tr><td>4</td><td>下单</td><td>5</td></tr>
 * </table>
 */
@Data
@TableName("user_behavior")
public class UserBehavior implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 行为ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 商品ID */
    private Long productId;

    /** 行为类型：1浏览 2收藏 3私信 4下单 */
    private Integer behaviorType;

    /** 冗余的商品分类ID，便于聚合用户分类偏好 */
    private Long categoryId;

    /** 行为权重（兴趣强度） */
    private BigDecimal weight;

    /** 同类行为累计次数 */
    private Integer behaviorCount;

    /** 首次发生时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 最近发生时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
