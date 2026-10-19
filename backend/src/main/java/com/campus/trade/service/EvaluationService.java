package com.campus.trade.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.trade.entity.Evaluation;

/**
 * 评价表基础服务（v0.18 / 第6批-㉑）。
 *
 * <p>与项目里其它 <code>XxxService</code> 一致：只提供 MyBatis-Plus 的基础 CRUD，
 * 业务规则放在 {@link EvaluationModuleService}，两层分开便于测试。</p>
 */
public interface EvaluationService extends IService<Evaluation> {
}
