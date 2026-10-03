package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.entity.Report;
import com.campus.trade.mapper.ReportMapper;
import com.campus.trade.service.ReportService;
import org.springframework.stereotype.Service;

/**
 * 举报 基础服务实现（v0.13）。
 */
@Service
public class ReportServiceImpl extends ServiceImpl<ReportMapper, Report> implements ReportService {
}
