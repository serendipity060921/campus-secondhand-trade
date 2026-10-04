package com.campus.trade.service;

import com.campus.trade.mapper.MessageModuleMapper;
import com.campus.trade.vo.ConversationVO;
import com.campus.trade.vo.MessageVO;
import com.campus.trade.websocket.ChatSessionRegistry;
import com.campus.trade.websocket.WsEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 实时推送服务（v0.14）。
 *
 * <p>把"业务事件"翻译成"WebSocket 帧"并推给相关用户。之所以单独抽一层：</p>
 * <ol>
 *   <li>私信的发送入口有两个 —— <b>REST 接口</b>（v0.07 保留）与 <b>WebSocket</b>（v0.14 新增），
 *       两个入口都要推送，逻辑必须只有一份；</li>
 *   <li>业务层（MessageModuleServiceImpl）只依赖本服务，不直接依赖 WebSocket 组件，
 *       这样"推送失败"不会影响"消息入库"，也让业务代码保持干净。</li>
 * </ol>
 *
 * <p>所有推送方法都做了异常兜底：实时推送属于"尽力而为"的增强能力，
 * 推送失败时消息本身已落库，对方下次拉取历史仍能看到，不会丢消息。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPushService {

    private final ChatSessionRegistry registry;
    private final MessageModuleMapper messageModuleMapper;
    private final ObjectMapper objectMapper;

    /**
     * 推送新私信：
     * ① 推给接收人的所有连接；② 回推给发送人的其它连接（多标签页同步）。
     *
     * @return 成功推送的连接数
     */
    public int pushMessage(MessageVO vo) {
        if (vo == null) {
            return 0;
        }
        try {
            String payload = WsEnvelope.of(WsEnvelope.TYPE_CHAT, vo).toJson(objectMapper);
            int sent = registry.sendToUser(vo.getToUserId(), payload);
            // 发送人自己也要收到（前端借此把消息标记为"已送达"，并同步其它标签页）
            sent += registry.sendToUser(vo.getFromUserId(), payload);
            log.debug("[推送私信] id={} to={} 送达连接数={}", vo.getId(), vo.getToUserId(), sent);
            return sent;
        } catch (Exception e) {
            log.warn("[推送私信失败] id={} 原因={}", vo.getId(), e.getMessage());
            return 0;
        }
    }

    /**
     * 推送已读回执：告诉 peerId"你发给我的消息已被我读掉"。
     *
     * @param readerId 读消息的人（接收方）
     * @param peerId   消息的原发送方
     */
    public int pushRead(Long readerId, Long peerId) {
        try {
            String payload = WsEnvelope.read(readerId, peerId).toJson(objectMapper);
            return registry.sendToUser(peerId, payload);
        } catch (Exception e) {
            log.warn("[推送已读回执失败] readerId={} peerId={} 原因={}", readerId, peerId, e.getMessage());
            return 0;
        }
    }

    /**
     * 推送在线状态变化：只通知"与该用户有会话关系的人"，
     * 避免把状态广播给全站用户（那是无意义的流量）。
     */
    public int pushOnline(Long userId, boolean online) {
        try {
            Set<Long> peers = peersOf(userId);
            if (peers.isEmpty()) {
                return 0;
            }
            String payload = WsEnvelope.online(userId, online).toJson(objectMapper);
            return registry.sendToUsers(peers, payload);
        } catch (Exception e) {
            log.warn("[推送在线状态失败] userId={} 原因={}", userId, e.getMessage());
            return 0;
        }
    }

    /** 与我聊过天的所有人（会话对象），用于在线状态通知 */
    public Set<Long> peersOf(Long userId) {
        try {
            List<ConversationVO> conversations = messageModuleMapper.selectConversationList(userId);
            if (conversations == null || conversations.isEmpty()) {
                return Set.of();
            }
            return conversations.stream()
                    .map(ConversationVO::getPeerId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            log.warn("[查询会话对象失败] userId={} 原因={}", userId, e.getMessage());
            return Set.of();
        }
    }
}
