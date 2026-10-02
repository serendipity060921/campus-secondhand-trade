package com.campus.trade.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 订单详情（v0.08）。
 *
 * <p>在列表字段基础上补充：交易地点、交付方式、备注、取消原因、商品当前状态、
 * 买卖双方的详细信息（头像/校区/信用分）等。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderDetailVO extends OrderVO {

    private static final long serialVersionUID = 1L;

    /** 交付方式：1 校内面交 2 快递 */
    private Integer deliveryType;

    /** 面交地点 */
    private String tradePlace;

    /** 买家备注 */
    private String buyerRemark;

    /** 取消原因 */
    private String cancelReason;

    /** 支付时间（预留字段，本模块为空） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime payTime;

    /** 卖家交付时间（预留字段） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deliverTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /* ---------- 商品当前状态（快照之外的实时信息） ---------- */
    /** 商品当前状态：1 在售 4 交易中 5 已售出 等 */
    private Integer productStatus;

    /** 商品当前状态中文 */
    private String productStatusLabel;

    /* ---------- 买卖双方详细信息 ---------- */
    /** 买家头像 */
    private String buyerAvatar;

    /** 买家校区 */
    private String buyerCampus;

    /** 买家信用分 */
    private Integer buyerCreditScore;

    /** 卖家头像 */
    private String sellerAvatar;

    /** 卖家校区 */
    private String sellerCampus;

    /** 卖家信用分 */
    private Integer sellerCreditScore;

    /** 当前用户是否可以操作该订单（状态为待交易时才可以完成/取消） */
    private Boolean operable;
}
