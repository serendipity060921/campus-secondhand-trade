package com.campus.trade.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 订单双向评价（v0.18 / 第6批-㉑）。
 *
 * <p>一笔已完成的订单，买卖双方各可评价对方一次（由 {@code uk_order_from} 唯一索引兜底）。
 * 三个冗余字段是刻意保留的：</p>
 * <ul>
 *   <li>{@code role}：显式记录"谁在评谁"，避免靠订单字段反推（订单字段一变就全错）；</li>
 *   <li>{@code toUserId}：查"我收到的评价"不必再关联订单；</li>
 *   <li>{@code productId}：商品详情页要展示评价，避免三表关联。</li>
 * </ul>
 */
@Data
@TableName("evaluation")
public class Evaluation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联订单ID */
    private Long orderId;

    /** 关联商品ID（冗余） */
    private Long productId;

    /** 评价人ID */
    private Long fromUserId;

    /** 被评价人ID（冗余） */
    private Long toUserId;

    /** 评价者身份：1 买家评卖家 / 2 卖家评买家 */
    private Integer role;

    /** 评分 1~5 星 */
    private Integer rating;

    /** 评价内容 */
    private String content;

    /** 标签，逗号分隔 */
    private String tags;

    /** 是否匿名：0 否 / 1 是（匿名只隐藏昵称，不影响统计） */
    private Integer anonymous;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0 正常 / 1 已删除 */
    private Integer deleted;
}
