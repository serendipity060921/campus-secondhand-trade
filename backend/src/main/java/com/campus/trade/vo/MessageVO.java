package com.campus.trade.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 私信消息（v0.07）。
 */
@Data
public class MessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息ID */
    private Long id;

    /** 发送人ID */
    private Long fromUserId;

    /** 发送人昵称（v0.14：实时推送时前端用于弹出"新私信来自 xxx"提醒） */
    private String fromNickname;

    /** 接收人ID */
    private Long toUserId;

    /** 消息内容 */
    private String content;

    /** 关联商品ID（可为空） */
    private Long productId;

    /** 关联商品名称（联表查出，便于聊天窗口显示"正在咨询的商品"） */
    private String productTitle;

    /** 是否已读：0未读 1已读 */
    private Integer isRead;

    /** 读取时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime readTime;

    /** 发送时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
