package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.exception.MessageException;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.dto.MessageReadDTO;
import com.campus.trade.dto.MessageSendDTO;
import com.campus.trade.entity.Message;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.mapper.MessageModuleMapper;
import com.campus.trade.service.ChatPushService;
import com.campus.trade.service.MessageService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserBehaviorService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.ChatPeerVO;
import com.campus.trade.vo.MessageVO;

/**
 * 私信模块业务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>私信是唯一"双向实时"的业务，规则集中在三处：</p>
 * <ul>
 *   <li>★ 不能给自己发消息、接收人必须存在、关联商品（选填）必须存在；</li>
 *   <li>★ <b>已读只能改"我收到的"消息</b>：条件里带 {@code to_user_id = 当前用户}，
 *       从 SQL 层面杜绝越权改他人消息；且必须"peerId 或 messageIds 至少传一个"；</li>
 *   <li>★ <b>实时推送不能影响主流程</b>：推送失败时消息已入库，对方拉历史仍能看到；
 *       已读回执只在真的更新到行、且是按 peerId 标记时才发。</li>
 * </ul>
 */
class MessageModuleServiceImplTest {

    private MessageService messageService;
    private MessageModuleMapper messageModuleMapper;
    private UserService userService;
    private ProductService productService;
    private UserBehaviorService userBehaviorService;
    private ChatPushService chatPushService;
    private BaseMapper<Message> messageBaseMapper;

    private MessageModuleServiceImpl service;

    private static final Long FROM = 11L;
    private static final Long TO = 8L;

    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (Class<?> c : new Class<?>[]{Message.class, User.class, Product.class}) {
            TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""), c);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        messageService = mock(MessageService.class);
        messageModuleMapper = mock(MessageModuleMapper.class);
        userService = mock(UserService.class);
        productService = mock(ProductService.class);
        userBehaviorService = mock(UserBehaviorService.class);
        chatPushService = mock(ChatPushService.class);
        messageBaseMapper = mock(BaseMapper.class);

        // markRead 走 messageService.getBaseMapper().update(null, wrapper)
        when(messageService.getBaseMapper()).thenReturn(messageBaseMapper);

        service = new MessageModuleServiceImpl(messageService, messageModuleMapper, userService,
                productService, userBehaviorService, chatPushService);
    }

    private MessageSendDTO sendDTO(Long toUserId, Long productId, String content) {
        MessageSendDTO d = new MessageSendDTO();
        d.setToUserId(toUserId);
        d.setProductId(productId);
        d.setContent(content);
        return d;
    }

    private User user(long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        u.setUsername("u" + id);
        u.setCampus("东校区");
        u.setCreditScore(100);
        return u;
    }

    @Nested
    @DisplayName("一、发送私信")
    class Send {

        @Test
        @DisplayName("★ 不能给自己发消息")
        void cannotSendToSelf() {
            assertThrows(MessageException.class, () -> service.send(sendDTO(FROM, null, "你好"), FROM));
            verify(messageService, never()).save(any());
        }

        @Test
        @DisplayName("接收人不存在 → 报错")
        void receiverNotFound() {
            when(userService.getById(TO)).thenReturn(null);
            assertThrows(MessageException.class, () -> service.send(sendDTO(TO, null, "你好"), FROM));
            verify(messageService, never()).save(any());
        }

        @Test
        @DisplayName("★ 关联商品必须存在（商品被删后不能再基于它发私信）")
        void productMustExist() {
            when(userService.getById(TO)).thenReturn(user(TO, "卖家"));
            when(productService.getById(5L)).thenReturn(null);

            assertThrows(ProductException.class, () -> service.send(sendDTO(TO, 5L, "在吗"), FROM));
            verify(messageService, never()).save(any());
        }

        @Test
        @DisplayName("★ 发送成功：type=私信、parentId=0、未读、内容去空格，并推送给接收人")
        void sendOk() {
            when(userService.getById(TO)).thenReturn(user(TO, "卖家"));
            when(userService.getById(FROM)).thenReturn(user(FROM, "买家"));
            Product p = new Product();
            p.setId(5L);
            p.setTitle("九成新教材");
            when(productService.getById(5L)).thenReturn(p);

            MessageVO vo = service.send(sendDTO(TO, 5L, "  还在吗  "), FROM);

            verify(messageService).save(org.mockito.ArgumentMatchers.argThat(m ->
                    m != null && Integer.valueOf(2).equals(m.getType())
                            && Long.valueOf(0L).equals(m.getParentId())
                            && Integer.valueOf(0).equals(m.getIsRead())
                            && "还在吗".equals(m.getContent())
                            && Long.valueOf(FROM).equals(m.getFromUserId())));
            assertEquals("九成新教材", vo.getProductTitle());
            assertEquals("买家", vo.getFromNickname());
            verify(chatPushService).pushMessage(any());
        }

        @Test
        @DisplayName("★ 带商品的私信会记一次「询价」行为（推荐数据源）")
        void behaviorRecordedForProductMessage() {
            when(userService.getById(TO)).thenReturn(user(TO, "卖家"));
            when(userService.getById(FROM)).thenReturn(user(FROM, "买家"));
            Product p = new Product();
            p.setId(5L);
            when(productService.getById(5L)).thenReturn(p);

            service.send(sendDTO(TO, 5L, "在吗"), FROM);

            verify(userBehaviorService).record(FROM, 5L, UserBehaviorService.TYPE_MESSAGE);
        }

        @Test
        @DisplayName("不带商品的消息不记行为")
        void noBehaviorWithoutProduct() {
            when(userService.getById(TO)).thenReturn(user(TO, "卖家"));
            when(userService.getById(FROM)).thenReturn(user(FROM, "买家"));

            service.send(sendDTO(TO, null, "在吗"), FROM);

            verify(userBehaviorService, never()).record(anyLong(), anyLong(), any(Integer.class));
        }

        @Test
        @DisplayName("★ 行为埋点失败不影响消息送达（旁路逻辑）")
        void behaviorFailureSwallowed() {
            when(userService.getById(TO)).thenReturn(user(TO, "卖家"));
            when(userService.getById(FROM)).thenReturn(user(FROM, "买家"));
            Product p = new Product();
            p.setId(5L);
            when(productService.getById(5L)).thenReturn(p);
            doThrow(new RuntimeException("埋点挂了"))
                    .when(userBehaviorService).record(anyLong(), anyLong(), any(Integer.class));

            assertNotNull(service.send(sendDTO(TO, 5L, "在吗"), FROM), "埋点失败时消息仍应发送成功");
        }
    }

    @Nested
    @DisplayName("二、★ 标记已读：只能改「我收到的」")
    class MarkRead {

        @Test
        @DisplayName("★ peerId 与 messageIds 都不传 → 参数非法（不允许「全部已读」）")
        void requiresPeerOrIds() {
            MessageReadDTO d = new MessageReadDTO();
            assertThrows(MessageException.class, () -> service.markRead(d, FROM));
        }

        @Test
        @DisplayName("按 peerId 标记已读 → 更新行数返回 + 给对方发已读回执")
        void byPeer() {
            MessageReadDTO d = new MessageReadDTO();
            d.setPeerId(TO);
            when(messageBaseMapper.update(any(), any())).thenReturn(3);

            int rows = service.markRead(d, FROM);

            assertEquals(3, rows);
            verify(chatPushService).pushRead(FROM, TO);
        }

        @Test
        @DisplayName("★ 没有更新到任何行时**不**发已读回执（避免误报「已读」）")
        void noReceiptWhenNothingUpdated() {
            MessageReadDTO d = new MessageReadDTO();
            d.setPeerId(TO);
            when(messageBaseMapper.update(any(), any())).thenReturn(0);

            assertEquals(0, service.markRead(d, FROM));
            verify(chatPushService, never()).pushRead(anyLong(), anyLong());
        }

        @Test
        @DisplayName("按 messageIds 标记时不发 peer 回执（不知道对方是谁）")
        void byIdsNoReceipt() {
            MessageReadDTO d = new MessageReadDTO();
            d.setMessageIds(List.of(1L, 2L));
            when(messageBaseMapper.update(any(), any())).thenReturn(2);

            assertEquals(2, service.markRead(d, FROM));
            verify(chatPushService, never()).pushRead(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("三、列表与统计")
    class Lists {

        @Test
        @DisplayName("会话列表直接由 Mapper 聚合返回")
        void conversationList() {
            when(messageModuleMapper.selectConversationList(FROM)).thenReturn(List.of());
            assertTrue(service.conversationList(FROM).isEmpty());
        }

        @Test
        @DisplayName("聊天记录分页：字段从 IPage 映射")
        void history() {
            Page<MessageVO> page = new Page<>(1, 20);
            page.setRecords(List.of(new MessageVO()));
            page.setTotal(1L);
            when(messageModuleMapper.selectHistory(any(), anyLong(), anyLong())).thenReturn(page);

            var result = service.history(TO, 1, 20, FROM);

            assertEquals(1L, result.getTotal());
            assertEquals(1, result.getRecords().size());
        }

        @Test
        @DisplayName("★ 未登录（userId 为空）时未读数为 0，不查库")
        void unreadTotalNullUser() {
            assertEquals(0L, service.unreadTotal(null));
            verify(messageService, never()).count(any());
        }

        @Test
        @DisplayName("未读数按「私信 + 我收到 + 未读」统计")
        void unreadTotal() {
            when(messageService.count(any())).thenReturn(5L);
            assertEquals(5L, service.unreadTotal(FROM));
        }

        @Test
        @DisplayName("聊天对象信息：不存在 → 报错；存在 → 带 self 标识")
        void peer() {
            when(userService.getById(TO)).thenReturn(null);
            assertThrows(MessageException.class, () -> service.peer(TO, FROM));

            when(userService.getById(FROM)).thenReturn(user(FROM, "我"));
            ChatPeerVO vo = service.peer(FROM, FROM);
            assertEquals("我", vo.getNickname());
            assertTrue(vo.getSelf(), "查自己时应标记 self");
        }
    }
}
