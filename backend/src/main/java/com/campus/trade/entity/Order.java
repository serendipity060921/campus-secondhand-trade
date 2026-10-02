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
 * 订单实体（orders）。
 *
 * <p>表名使用复数 orders，规避 MySQL 保留字 ORDER；
 * 订单状态：0待付款 1待交付 2待收货 3已完成 4已取消 5已退款。</p>
 */
@Data
@TableName("orders")
public class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单ID，主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单编号，对外展示，唯一 */
    private String orderNo;

    /** 商品ID */
    private Long productId;

    /** 买家用户ID */
    private Long buyerId;

    /** 卖家用户ID */
    private Long sellerId;

    /** 商品标题快照 */
    private String productTitle;

    /** 商品封面快照 */
    private String productImage;

    /** 成交金额（元） */
    private BigDecimal amount;

    /** 状态：0待付款 1待交付 2待收货 3已完成 4已取消 5已退款 */
    private Integer status;

    /** 交付方式：1校内面交 2快递 */
    private Integer deliveryType;

    /** 面交地点 */
    private String tradePlace;

    /** 收货人姓名 */
    private String receiverName;

    /** 收货人电话 */
    private String receiverPhone;

    /** 收货地址 */
    private String receiverAddress;

    /** 支付方式：1模拟支付 2微信 3支付宝 */
    private Integer payType;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 卖家交付时间 */
    private LocalDateTime deliverTime;

    /** 交易完成时间 */
    private LocalDateTime finishTime;

    /** 取消时间 */
    private LocalDateTime cancelTime;

    /** 取消/退款原因 */
    private String cancelReason;

    /** 买家备注 */
    private String buyerRemark;

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
