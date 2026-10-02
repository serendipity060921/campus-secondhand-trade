package com.campus.trade.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话列表项（v0.07）：一个聊天对象 + 双方最后一条私信 + 未读数。
 */
@Data
public class ConversationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 聊天对象用户ID */
    private Long peerId;

    /** 聊天对象昵称 */
    private String peerNickname;

    /** 聊天对象头像 */
    private String peerAvatar;

    /** 聊天对象校区 */
    private String peerCampus;

    /** 最后一条消息内容 */
    private String lastMessage;

    /** 最后一条消息时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastMessageTime;

    /** 最后一条消息是否由我发出（前端可显示"我：xxx"） */
    private Boolean lastFromMe;

    /** 最后一条消息关联的商品ID（可为空） */
    private Long productId;

    /** 最后一条消息关联的商品名称 */
    private String productTitle;

    /** 我未读的消息条数（对方发给我的） */
    private Integer unreadCount;
}
