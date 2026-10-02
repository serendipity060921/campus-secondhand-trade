package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.OrderException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.OrderCreateDTO;
import com.campus.trade.dto.OrderStatusDTO;
import com.campus.trade.service.OrderModuleService;
import com.campus.trade.vo.OrderDetailVO;
import com.campus.trade.vo.OrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单交易接口（v0.08）。
 *
 * <table border="1">
 *   <caption>接口清单（全部需要登录）</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/order/create</td><td>创建订单（买家 = 当前登录用户）</td></tr>
 *   <tr><td>PUT</td><td>/api/order/status</td><td>修改订单状态：3 已完成 / 4 已取消</td></tr>
 *   <tr><td>GET</td><td>/api/order/buyList</td><td>我买到的订单（分页，可按状态筛选）</td></tr>
 *   <tr><td>GET</td><td>/api/order/sellList</td><td>我卖出的订单（分页，可按状态筛选）</td></tr>
 *   <tr><td>GET</td><td>/api/order/{id}</td><td>订单详情（仅买卖双方可查看）</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    /** 每页条数上限 */
    private static final long MAX_PAGE_SIZE = 100L;

    private final OrderModuleService orderModuleService;

    /** 创建订单 */
    @LoginRequired
    @PostMapping("/create")
    public Result<OrderDetailVO> create(@Valid @RequestBody OrderCreateDTO dto) {
        OrderDetailVO vo = orderModuleService.create(dto, UserContext.getUserId());
        return Result.success("下单成功，请与卖家约定面交时间地点", vo);
    }

    /** 修改订单状态：已完成 / 已取消 */
    @LoginRequired
    @PutMapping("/status")
    public Result<OrderDetailVO> changeStatus(@Valid @RequestBody OrderStatusDTO dto) {
        OrderDetailVO vo = orderModuleService.changeStatus(dto, UserContext.getUserId());
        boolean finished = vo.getStatus() != null && vo.getStatus() == 3;
        return Result.success(finished ? "交易已完成" : "订单已取消", vo);
    }

    /** 我买到的订单 */
    @LoginRequired
    @GetMapping("/buyList")
    public Result<PageResult<OrderVO>> buyList(@RequestParam(value = "status", required = false) Integer status,
                                               @RequestParam(value = "page", defaultValue = "1") long page,
                                               @RequestParam(value = "size", defaultValue = "10") long size) {
        checkPage(page, size);
        return Result.success(orderModuleService.pageBuyList(UserContext.getUserId(), status, page, size));
    }

    /** 我卖出的订单 */
    @LoginRequired
    @GetMapping("/sellList")
    public Result<PageResult<OrderVO>> sellList(@RequestParam(value = "status", required = false) Integer status,
                                                @RequestParam(value = "page", defaultValue = "1") long page,
                                                @RequestParam(value = "size", defaultValue = "10") long size) {
        checkPage(page, size);
        return Result.success(orderModuleService.pageSellList(UserContext.getUserId(), status, page, size));
    }

    /** 订单详情（仅买卖双方） */
    @LoginRequired
    @GetMapping("/{id}")
    public Result<OrderDetailVO> detail(@PathVariable("id") Long id) {
        return Result.success(orderModuleService.detail(id, UserContext.getUserId()));
    }

    private void checkPage(long page, long size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw OrderException.paramIllegal("page 必须大于 0，size 必须为 1~" + MAX_PAGE_SIZE);
        }
    }
}
