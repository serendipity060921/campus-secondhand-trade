package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.entity.AdminLog;
import com.campus.trade.mapper.AdminLogMapper;
import com.campus.trade.service.AdminLogService;
import org.springframework.stereotype.Service;

/**
 * 管理员操作日志 基础服务实现（v0.13）。
 */
@Service
public class AdminLogServiceImpl extends ServiceImpl<AdminLogMapper, AdminLog> implements AdminLogService {
}
