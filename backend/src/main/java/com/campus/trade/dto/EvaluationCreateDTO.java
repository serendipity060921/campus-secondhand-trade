package com.campus.trade.dto;

import java.io.Serializable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交评价入参（v0.18 / 第6批-㉑）。
 *
 * <p>只让前端传 {@code orderId}，<b>不传</b>被评价人 —— 评价对象由服务端按订单的买卖双方推导，
 * 否则前端可以把"评价"指向任意用户。</p>
 */
@Data
public class EvaluationCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 关联订单ID（唯一必填项） */
    @NotNull(message = "请选择要评价的订单")
    private Long orderId;

    /** 评分 1~5 星 */
    @NotNull(message = "请选择评分")
    @Min(value = 1, message = "评分最少 1 星")
    @Max(value = 5, message = "评分最多 5 星")
    private Integer rating;

    /** 评价内容（可空） */
    @Size(max = 500, message = "评价内容不能超过 500 字")
    private String content;

    /** 标签，逗号分隔（可空） */
    @Size(max = 200, message = "标签总长度不能超过 200 字")
    private String tags;

    /** 是否匿名：0 否 / 1 是，缺省否 */
    private Integer anonymous;
}
