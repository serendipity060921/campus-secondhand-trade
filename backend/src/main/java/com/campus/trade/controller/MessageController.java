package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.annotation.RateLimit;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.MessageException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.MessageReadDTO;
import com.campus.trade.dto.MessageSendDTO;
import com.campus.trade.service.MessageModuleService;
import com.campus.trade.vo.ChatPeerVO;
import com.campus.trade.vo.ConversationVO;
import com.campus.trade.vo.MessageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 私信聊天接口（v0.07）。
 *
 * <table border="1">
 *   <caption>接口清单（全部需要登录）</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/message/send</td><td>发送私信（toUserId / content / productId）</td></tr>
 *   <tr><td>GET</td><td>/api/message/conversationList</td><td>会话列表（聊天对象 + 最后一条消息 + 未读数）</td></tr>
 *   <tr><td>GET</td><td>/api/message/history</td><td>与某人的聊天记录（分页，最新在前）</td></tr>
 *   <tr><td>PUT</td><td>/api/message/read</td><td>标记已读（按聊天对象或按消息ID）</td></tr>
 *   <tr><td>GET</td><td>/api/message/peer</td><td>聊天对象公开信息（聊天窗口顶部展示，配套接口）</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
public class MessageController {

    /** 每页条数上限 */
    private static final long MAX_PAGE_SIZE = 100L;

    private final MessageModuleService messageModuleService;

    /**
     * 发送私信。
     * <p>发送人取自 Token；给自己发消息会被拦截（5001）。</p>
     */
    @LoginRequired
    @PostMapping("/send")
    @RateLimit(key = "send-msg", limit = 30, window = 60, dimension = RateLimit.Dimension.USER,
            message = "发送消息过于频繁")
    public Result<MessageVO> send(@Valid @RequestBody MessageSendDTO dto) {
        MessageVO vo = messageModuleService.send(dto, UserContext.getUserId());
        return Result.success("发送成功", vo);
    }

    /** 会话列表：当前用户的所有聊天对象 + 最新一条消息 + 未读数 */
    @LoginRequired
    @GetMapping("/conversationList")
    public Result<List<ConversationVO>> conversationList() {
        return Result.success(messageModuleService.conversationList(UserContext.getUserId()));
    }

    /**
     * 聊天记录分页（最新在前）。
     *
     * @param peerId 聊天对象用户ID
     */
    @LoginRequired
    @GetMapping("/history")
    public Result<PageResult<MessageVO>> history(@RequestParam("peerId") Long peerId,
                                                 @RequestParam(value = "page", defaultValue = "1") long page,
                                                 @RequestParam(value = "size", defaultValue = "20") long size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw MessageException.paramIllegal("page 必须大于 0，size 必须为 1~" + MAX_PAGE_SIZE);
        }
        return Result.success(messageModuleService.history(peerId, page, size, UserContext.getUserId()));
    }

    /** 标记消息已读，返回实际更新条数 */
    @LoginRequired
    @PutMapping("/read")
    public Result<Integer> read(@RequestBody MessageReadDTO dto) {
        int rows = messageModuleService.markRead(dto, UserContext.getUserId());
        return Result.success("已标记 " + rows + " 条消息为已读", rows);
    }

    /** 聊天对象公开信息（配套接口：聊天窗口显示对方昵称/头像/校区/信用分） */
    @LoginRequired
    @GetMapping("/peer")
    public Result<ChatPeerVO> peer(@RequestParam("peerId") Long peerId) {
        return Result.success(messageModuleService.peer(peerId, UserContext.getUserId()));
    }
}
