package com.campus.trade.service;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.MessageReadDTO;
import com.campus.trade.dto.MessageSendDTO;
import com.campus.trade.vo.ChatPeerVO;
import com.campus.trade.vo.ConversationVO;
import com.campus.trade.vo.MessageVO;

import java.util.List;

/**
 * 私信模块业务（v0.07）。
 *
 * <p>只处理 <code>type = 2</code> 的私信；商品留言（type = 1）留给后续里程碑，
 * 两套功能共用 message 表但互不影响。</p>
 */
public interface MessageModuleService {

    /**
     * 发送私信。
     *
     * @throws com.campus.trade.common.exception.MessageException 给自己发消息(5001) / 接收人不存在(5002)
     * @throws com.campus.trade.common.exception.ProductException  关联商品不存在(3001)
     */
    MessageVO send(MessageSendDTO dto, Long fromUserId);

    /** 会话列表：所有聊天对象 + 最后一条消息 + 未读数 */
    List<ConversationVO> conversationList(Long userId);

    /** 与某个用户的聊天记录（分页，时间倒序：最新在前） */
    PageResult<MessageVO> history(Long peerId, long page, long size, Long userId);

    /**
     * 标记消息已读（只能标记"我收到的"消息）。
     *
     * @return 实际更新条数
     * @throws com.campus.trade.common.exception.MessageException 参数不合法(400)
     */
    int markRead(MessageReadDTO dto, Long userId);

    /** 聊天对象公开信息（聊天窗口顶部展示） */
    ChatPeerVO peer(Long peerId, Long userId);

    /**
     * 我的未读私信总数（v0.14：前端顶部角标 + WebSocket 连接建立时下发的欢迎包）。
     */
    long unreadTotal(Long userId);
}
