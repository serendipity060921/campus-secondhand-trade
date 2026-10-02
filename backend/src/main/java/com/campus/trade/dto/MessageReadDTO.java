package com.campus.trade.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 消息已读请求参数（v0.07）。
 *
 * <p>对应接口：<code>PUT /api/message/read</code></p>
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li>只传 <code>peerId</code>：把该聊天对象发给我的所有未读消息标记为已读（聊天窗口打开时使用）；</li>
 *   <li>传 <code>messageIds</code>：只标记指定消息（会校验这些消息的接收人必须是我，防止越权）。</li>
 * </ul>
 */
@Data
public class MessageReadDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 聊天对象ID（与 messageIds 至少传一个） */
    private Long peerId;

    /** 需要标记已读的消息ID列表（可选） */
    private List<Long> messageIds;
}
