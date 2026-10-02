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
 * 留言/私信实体（message）。
 *
 * <p>type = 1 商品留言（product_id 必填，parent_id 支持盖楼回复）；
 * type = 2 私信（to_user_id 必填）。</p>
 */
@Data
@TableName("message")
public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息ID，主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 消息类型：1商品留言 2私信 */
    private Integer type;

    /** 关联商品ID */
    private Long productId;

    /** 发送人用户ID */
    private Long fromUserId;

    /** 接收人用户ID */
    private Long toUserId;

    /** 父留言ID，0 为顶层留言 */
    private Long parentId;

    /** 消息内容 */
    private String content;

    /** 是否已读：0未读 1已读 */
    private Integer isRead;

    /** 读取时间 */
    private LocalDateTime readTime;

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
