package com.campus.trade.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 聊天对象公开信息（v0.07）。
 *
 * <p>聊天窗口顶部展示用；只暴露昵称、头像、校区、信用分等公开字段。</p>
 */
@Data
public class ChatPeerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 昵称 */
    private String nickname;

    /** 头像 */
    private String avatar;

    /** 校区 */
    private String campus;

    /** 信用分 */
    private Integer creditScore;

    /** 是否是当前登录用户自己（前端据此禁用输入框） */
    private Boolean self;
}
