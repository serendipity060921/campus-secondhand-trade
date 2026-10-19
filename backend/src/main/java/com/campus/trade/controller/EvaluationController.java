package com.campus.trade.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.annotation.RateLimit;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.EvaluationCreateDTO;
import com.campus.trade.service.EvaluationModuleService;
import com.campus.trade.vo.EvaluationSummaryVO;
import com.campus.trade.vo.EvaluationVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 评价接口（v0.18 / 第6批-㉑）。
 *
 * <p>设计口径：</p>
 * <ul>
 *   <li><b>写接口</b>：需登录 + 限流（限流是 CI 强制的 —— 所有写接口必须有限流或白名单理由）；</li>
 *   <li><b>读接口</b>：商品评价、某人的收到的评价、汇总都是<b>公开</b>的 ——
 *       不然新用户看不到任何口碑，双向评价就失去意义；</li>
 *   <li><b>「我评过没有」</b>走 <code>/order/{orderId}</code>，只有买卖双方可访问，
 *       避免任意人通过该接口探测订单关系。</li>
 * </ul>
 */
@Tag(name = "评价")
@RestController
@RequestMapping("/api/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationModuleService evaluationModuleService;

    @Operation(summary = "提交评价（只能评价自己参与的已完成订单，每单一次）")
    @LoginRequired
    @RateLimit(key = "evaluation", limit = 20, window = 300, dimension = RateLimit.Dimension.USER,
            message = "评价过于频繁，请稍后再试")
    @PostMapping
    public Result<EvaluationVO> submit(@Valid @RequestBody EvaluationCreateDTO dto) {
        Long userId = UserContext.getUserId();
        return Result.success("评价成功", evaluationModuleService.submit(dto, userId));
    }

    @Operation(summary = "某商品的评价列表（公开）")
    @GetMapping("/product/{productId}")
    public Result<PageResult<EvaluationVO>> pageByProduct(
            @PathVariable("productId") Long productId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(evaluationModuleService.pageByProduct(productId, page, size,
                UserContext.isLogin() ? UserContext.getUserId() : null));
    }

    @Operation(summary = "某人收到的评价（公开）")
    @GetMapping("/received/{userId}")
    public Result<PageResult<EvaluationVO>> pageReceived(
            @PathVariable("userId") Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(evaluationModuleService.pageReceived(userId, page, size,
                UserContext.isLogin() ? UserContext.getUserId() : null));
    }

    @Operation(summary = "我发出的评价")
    @LoginRequired
    @GetMapping("/mine")
    public Result<PageResult<EvaluationVO>> pageMine(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(evaluationModuleService.pageSent(page, size, UserContext.getUserId()));
    }

    @Operation(summary = "某商品的评价汇总（平均分/好评率/星级分布，公开）")
    @GetMapping("/summary/product/{productId}")
    public Result<EvaluationSummaryVO> summaryByProduct(@PathVariable("productId") Long productId) {
        return Result.success(evaluationModuleService.summaryByProduct(productId));
    }

    @Operation(summary = "某订单下的评价（用于判断我评过没有，仅买卖双方可见）")
    @LoginRequired
    @GetMapping("/order/{orderId}")
    public Result<List<EvaluationVO>> listByOrder(@PathVariable("orderId") Long orderId) {
        return Result.success(evaluationModuleService.listByOrder(orderId, UserContext.getUserId()));
    }
}
