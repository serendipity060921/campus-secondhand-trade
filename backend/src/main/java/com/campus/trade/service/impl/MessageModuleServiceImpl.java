package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.exception.MessageException;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.MessageReadDTO;
import com.campus.trade.dto.MessageSendDTO;
import com.campus.trade.entity.Message;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.mapper.MessageModuleMapper;
import com.campus.trade.service.MessageModuleService;
import com.campus.trade.service.MessageService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserBehaviorService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.ChatPeerVO;
import com.campus.trade.vo.ConversationVO;
import com.campus.trade.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 私信模块业务实现（v0.07）。
 *
 * <p>业务规则：</p>
 * <ol>
 *   <li>不能给自己发消息（5001）；</li>
 *   <li>接收人必须存在（5002）；</li>
 *   <li>带关联商品时，商品必须存在（3001）；</li>
 *   <li>只能把自己收到的消息标记为已读（通过 to_user_id = 当前用户 保证，防越权）；</li>
 *   <li>会话列表与聊天记录都只查 type = 2（私信），不受商品留言影响。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageModuleServiceImpl implements MessageModuleService {

    /** 消息类型：2 私信（1 为商品留言，留给后续里程碑） */
    private static final int TYPE_PRIVATE = 2;
    /** 未读 */
    private static final int UNREAD = 0;

    private final MessageService messageService;
    private final MessageModuleMapper messageModuleMapper;
    private final UserService userService;
    private final ProductService productService;
    /** v0.11 推荐模块：行为埋点 */
    private final UserBehaviorService userBehaviorService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageVO send(MessageSendDTO dto, Long fromUserId) {
        // ① 不能给自己发消息
        if (Objects.equals(dto.getToUserId(), fromUserId)) {
            throw MessageException.cannotSendToSelf();
        }
        // ② 接收人必须存在
        User receiver = userService.getById(dto.getToUserId());
        if (receiver == null) {
            throw MessageException.receiverNotFound();
        }
        // ③ 关联商品（选填）必须存在
        Product product = null;
        if (dto.getProductId() != null) {
            product = productService.getById(dto.getProductId());
            if (product == null) {
                throw ProductException.notFound();
            }
        }

        // ④ 入库
        Message message = new Message();
        message.setType(TYPE_PRIVATE);
        message.setFromUserId(fromUserId);
        message.setToUserId(dto.getToUserId());
        message.setProductId(dto.getProductId());
        message.setParentId(0L);
        message.setContent(dto.getContent().trim());
        message.setIsRead(UNREAD);
        message.setDeleted(0);
        messageService.save(message);

        log.info("[私信发送] from={} to={} productId={} id={}",
                fromUserId, dto.getToUserId(), dto.getProductId(), message.getId());

        // v0.11 行为埋点：带商品的私信视为一次"询价"兴趣信号（失败不影响主流程）
        if (dto.getProductId() != null) {
            try {
                userBehaviorService.record(fromUserId, dto.getProductId(), UserBehaviorService.TYPE_MESSAGE);
            } catch (Exception e) {
                log.warn("[行为埋点失败] 私信 userId={} productId={} 原因={}", fromUserId, dto.getProductId(), e.getMessage());
            }
        }

        MessageVO vo = new MessageVO();
        BeanUtils.copyProperties(message, vo);
        vo.setProductTitle(product == null ? null : product.getTitle());
        return vo;
    }

    @Override
    public List<ConversationVO> conversationList(Long userId) {
        return messageModuleMapper.selectConversationList(userId);
    }

    @Override
    public PageResult<MessageVO> history(Long peerId, long page, long size, Long userId) {
        Page<MessageVO> pageParam = new Page<>(page, size);
        IPage<MessageVO> result = messageModuleMapper.selectHistory(pageParam, userId, peerId);
        return new PageResult<>(result.getTotal(), result.getPages(),
                result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markRead(MessageReadDTO dto, Long userId) {
        boolean hasIds = dto.getMessageIds() != null && !dto.getMessageIds().isEmpty();

        LambdaUpdateWrapper<Message> wrapper = new LambdaUpdateWrapper<Message>()
                .set(Message::getIsRead, 1)
                .set(Message::getReadTime, LocalDateTime.now())
                // 只能标记"我收到的"消息，从 SQL 层面杜绝越权改他人消息
                .eq(Message::getToUserId, userId)
                .eq(Message::getIsRead, UNREAD);

        if (hasIds) {
            wrapper.in(Message::getId, dto.getMessageIds());
        } else if (dto.getPeerId() != null) {
            wrapper.eq(Message::getFromUserId, dto.getPeerId());
        } else {
            throw MessageException.paramIllegal("peerId 与 messageIds 至少传一个");
        }

        int rows = messageService.getBaseMapper().update(null, wrapper);
        log.info("[私信已读] userId={} peerId={} ids={} 更新条数={}", userId, dto.getPeerId(), dto.getMessageIds(), rows);
        return rows;
    }

    @Override
    public ChatPeerVO peer(Long peerId, Long userId) {
        User user = userService.getById(peerId);
        if (user == null) {
            throw MessageException.receiverNotFound();
        }
        ChatPeerVO vo = new ChatPeerVO();
        vo.setUserId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setCampus(user.getCampus());
        vo.setCreditScore(user.getCreditScore());
        vo.setSelf(Objects.equals(peerId, userId));
        return vo;
    }
}
