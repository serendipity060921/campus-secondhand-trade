package com.campus.trade.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.entity.Evaluation;
import com.campus.trade.mapper.EvaluationMapper;
import com.campus.trade.service.EvaluationService;

/**
 * 评价表基础服务实现（v0.18 / 第6批-㉑）。只有 MyBatis-Plus 的默认 CRUD。
 */
@Service
public class EvaluationServiceImpl extends ServiceImpl<EvaluationMapper, Evaluation>
        implements EvaluationService {
}
