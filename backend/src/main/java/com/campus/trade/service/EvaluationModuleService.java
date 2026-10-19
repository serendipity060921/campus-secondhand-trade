package com.campus.trade.service;

import java.util.List;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.EvaluationCreateDTO;
import com.campus.trade.vo.EvaluationSummaryVO;
import com.campus.trade.vo.EvaluationVO;

/**
 * 评价模块业务接口（v0.18 / 第6批-㉑）。
 *
 * <p>与其它模块一致：<code>XxxService</code> 只做 CRUD，业务规则在本接口的实现里。</p>
 *
 * <p>读接口都带 {@code viewerId}（可空）：只有登录用户才能判断"这条评价是不是我写的"，
 * 未登录时一律按 false 处理。</p>
 */
public interface EvaluationModuleService {

    /** 提交评价（对象由订单推导，前端只能传 orderId） */
    EvaluationVO submit(EvaluationCreateDTO dto, Long userId);

    /** 某商品的评价列表（公开） */
    PageResult<EvaluationVO> pageByProduct(Long productId, long page, long size, Long viewerId);

    /** 某人收到的评价（公开） */
    PageResult<EvaluationVO> pageReceived(Long userId, long page, long size, Long viewerId);

    /** 我发出的评价（需登录） */
    PageResult<EvaluationVO> pageSent(long page, long size, Long userId);

    /** 某商品的评价汇总（平均分 / 好评率 / 星级分布，公开） */
    EvaluationSummaryVO summaryByProduct(Long productId);

    /** 某订单下的评价（用于前端判断"我评过没有"，需登录且必须是买卖双方） */
    List<EvaluationVO> listByOrder(Long orderId, Long userId);
}
