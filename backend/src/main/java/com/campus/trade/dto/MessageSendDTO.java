package com.campus.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 发送私信请求参数（v0.07）。
 *
 * <p>对应接口：<code>POST /api/message/send</code></p>
 */
@Data
public class MessageSendDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 接收人用户ID（不能是自己） */
    @NotNull(message = "接收人不能为空")
    private Long toUserId;

    /** 消息内容 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 1000, message = "消息内容不能超过 1000 个字符")
    private String content;

    /** 关联商品ID（选填，从商品详情页发起私聊时会带上） */
    private Long productId;
}
