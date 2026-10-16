package com.campus.trade.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.GlobalExceptionHandler;
import com.campus.trade.common.exception.MessageException;
import com.campus.trade.dto.MessageSendDTO;
import com.campus.trade.service.MessageModuleService;
import com.campus.trade.service.OnlineStatusService;
import com.campus.trade.vo.MessageVO;

/**
 * 私信接口层验证（v0.18 / 第3批-⑪）
 *
 * <p>接口层要守的不是业务规则（那在服务层测），而是<b>接线</b>与<b>契约</b>：
 * 路径与方法是否对上、参数怎么绑定、未登录会不会带着空用户往下跑、
 * 以及成功/失败是否都返回统一结构。服务层已测透，这里用 mock 隔离。</p>
 */
class MessageControllerTest {

    private MessageModuleService messageModuleService;
    private OnlineStatusService onlineStatusService;
    private MockMvc mvc;

    private static final Long UID = 11L;

    @BeforeEach
    void setUp() {
        messageModuleService = mock(MessageModuleService.class);
        onlineStatusService = mock(OnlineStatusService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new MessageController(messageModuleService, onlineStatusService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new LoginUser(UID, "stu_demo", 0));
    }

    @Nested
    @DisplayName("一、需要登录的接口：未登录必须 401")
    class AuthRequired {

        @Test
        @DisplayName("未登录查未读数 → 401，且不调用服务层")
        void unreadTotalNeedsLogin() throws Exception {
            mvc.perform(get("/api/message/unreadTotal"))
                    .andExpect(jsonPath("$.code").value(401));

            verify(messageModuleService, never()).unreadTotal(anyLong());
        }

        @Test
        @DisplayName("未登录发消息 → 401，消息不会落库")
        void sendNeedsLogin() throws Exception {
            mvc.perform(post("/api/message/send").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"toUserId\":5,\"content\":\"你好\"}"))
                    .andExpect(jsonPath("$.code").value(401));

            verify(messageModuleService, never()).send(any(), anyLong());
        }

        @Test
        @DisplayName("未登录看会话列表 → 401")
        void conversationListNeedsLogin() throws Exception {
            mvc.perform(get("/api/message/conversationList"))
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("二、正常路径：调用服务层并把结果包进统一结构")
    class HappyPath {

        @Test
        @DisplayName("未读数：透传服务层结果，带上时间戳")
        void unreadTotal() throws Exception {
            login();
            when(messageModuleService.unreadTotal(UID)).thenReturn(5L);

            mvc.perform(get("/api/message/unreadTotal"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(5))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("★ 发消息：DTO 与当前用户一起传给服务层（发送人取自上下文，前端改不了）")
        void send() throws Exception {
            login();
            when(messageModuleService.send(any(MessageSendDTO.class), eq(UID))).thenReturn(new MessageVO());

            mvc.perform(post("/api/message/send").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"toUserId\":5,\"content\":\"你好\",\"productId\":7}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("发送成功"));

            org.mockito.ArgumentCaptor<MessageSendDTO> captor =
                    org.mockito.ArgumentCaptor.forClass(MessageSendDTO.class);
            verify(messageModuleService).send(captor.capture(), eq(UID));
            org.junit.jupiter.api.Assertions.assertEquals(5L, captor.getValue().getToUserId());
            org.junit.jupiter.api.Assertions.assertEquals("你好", captor.getValue().getContent());
        }

        @Test
        @DisplayName("标记已读：返回实际条数，提示里带条数")
        void read() throws Exception {
            login();
            when(messageModuleService.markRead(any(), eq(UID))).thenReturn(3);

            mvc.perform(put("/api/message/read").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"peerId\":8}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(3))
                    .andExpect(jsonPath("$.message").value("已标记 3 条消息为已读"));
        }

        @Test
        @DisplayName("聊天记录：查询参数正确绑定并透传 peerId")
        void history() throws Exception {
            login();

            mvc.perform(get("/api/message/history").param("peerId", "8")
                            .param("page", "2").param("size", "20"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(messageModuleService).history(eq(8L), eq(2L), eq(20L), eq(UID));
        }

        @Test
        @DisplayName("在线状态：不传 peerId 时查自己")
        void onlineSelf() throws Exception {
            login();
            when(onlineStatusService.isOnline(anyLong())).thenReturn(true);
            when(onlineStatusService.onlineCount()).thenReturn(12L);

            mvc.perform(get("/api/message/online"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value(UID))
                    .andExpect(jsonPath("$.data.online").value(true))
                    .andExpect(jsonPath("$.data.onlineCount").value(12));

            verify(onlineStatusService).isOnline(UID);
        }

        @Test
        @DisplayName("在线状态：传了 peerId 就查对方")
        void onlinePeer() throws Exception {
            login();
            when(onlineStatusService.isOnline(8L)).thenReturn(false);

            mvc.perform(get("/api/message/online").param("peerId", "8"))
                    .andExpect(jsonPath("$.data.userId").value(8))
                    .andExpect(jsonPath("$.data.online").value(false));

            verify(onlineStatusService).isOnline(8L);
        }

        @Test
        @DisplayName("聊天对象信息：透传 peerId 与当前用户")
        void peer() throws Exception {
            login();

            mvc.perform(get("/api/message/peer").param("peerId", "8"))
                    .andExpect(jsonPath("$.code").value(200));

            verify(messageModuleService).peer(8L, UID);
        }
    }

    @Nested
    @DisplayName("三、参数校验与业务异常")
    class Validation {

        @Test
        @DisplayName("★ 分页参数越界 → 400（不让 size=99999 拖垮数据库）")
        void historyPageTooLarge() throws Exception {
            login();

            mvc.perform(get("/api/message/history").param("peerId", "8").param("size", "99999"))
                    .andExpect(jsonPath("$.code").value(400));

            verify(messageModuleService, never()).history(anyLong(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("★ 服务层业务异常（不能给自己发消息 5001）→ 统一结构返回该码，不是 500")
        void businessExceptionKeepsCode() throws Exception {
            login();
            when(messageModuleService.send(any(), anyLong())).thenThrow(new MessageException(5001, "不能给自己发消息"));

            mvc.perform(post("/api/message/send").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"toUserId\":11,\"content\":\"你好\"}"))
                    .andExpect(jsonPath("$.code").value(5001))
                    .andExpect(jsonPath("$.message").value("不能给自己发消息"));
        }
    }
}
