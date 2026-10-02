package com.campus.trade.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单列表项（v0.08）。
 */
@Data
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单ID */
    private Long id;

    /** 订单编号 */
    private String orderNo;

    /** 商品ID */
    private Long productId;

    /** 商品名称（下单时的快照） */
    private String productTitle;

    /** 商品封面（下单时的快照，可能为空，前端用占位图兜底） */
    private String productImage;

    /** 成交金额 */
    private BigDecimal amount;

    /** 状态码 */
    private Integer status;

    /** 状态中文名 */
    private String statusLabel;

    /** 买家ID */
    private Long buyerId;

    /** 买家昵称 */
    private String buyerNickname;

    /** 卖家ID */
    private Long sellerId;

    /** 卖家昵称 */
    private String sellerNickname;

    /** 交易对方昵称（买家视角=卖家，卖家视角=买家） */
    private String counterpartNickname;

    /** 当前用户在当前订单中的角色：buyer / seller */
    private String myRole;

    /** 下单时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 完成时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    /** 取消时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime cancelTime;
}
