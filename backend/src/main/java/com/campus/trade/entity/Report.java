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
 * 举报实体（v0.13 管理后台）。
 *
 * <table border="1">
 *   <caption>字段取值</caption>
 *   <tr><th>字段</th><th>取值</th></tr>
 *   <tr><td>target_type</td><td>1 商品 / 2 用户</td></tr>
 *   <tr><td>reason_type</td><td>1 虚假信息 / 2 违禁物品 / 3 辱骂骚扰 / 4 其他</td></tr>
 *   <tr><td>status</td><td>0 待处理 / 1 已处理 / 2 已忽略</td></tr>
 * </table>
 */
@Data
@TableName("report")
public class Report implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 举报ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 举报人ID */
    private Long reporterId;

    /** 举报对象类型：1商品 2用户 */
    private Integer targetType;

    /** 举报对象ID */
    private Long targetId;

    /** 举报原因：1虚假信息 2违禁物品 3辱骂骚扰 4其他 */
    private Integer reasonType;

    /** 补充说明 */
    private String content;

    /** 证据图片 */
    private String imageUrl;

    /** 处理状态：0待处理 1已处理 2已忽略 */
    private Integer status;

    /** 处理人（管理员ID） */
    private Long handleAdminId;

    /** 处理结果说明 */
    private String handleResult;

    /** 处理时间 */
    private LocalDateTime handleTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
