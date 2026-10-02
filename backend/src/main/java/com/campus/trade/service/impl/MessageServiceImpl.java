package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.entity.Message;
import com.campus.trade.mapper.MessageMapper;
import com.campus.trade.service.MessageService;
import org.springframework.stereotype.Service;

/**
 * 留言/私信 业务实现。
 */
@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {
}
