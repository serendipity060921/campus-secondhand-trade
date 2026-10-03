package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.annotation.RateLimit;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.ReportCreateDTO;
import com.campus.trade.service.ReportModuleService;
import com.campus.trade.vo.ReportVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 举报接口（v0.13，面向普通用户）。
 *
 * <table border="1">
 *   <caption>接口清单</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/report/submit</td><td>提交举报（商品或用户）</td></tr>
 *   <tr><td>GET</td><td>/api/report/mine</td><td>我的举报记录</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportModuleService reportModuleService;

    /** 提交举报：targetType 1商品 2用户；reasonType 1虚假信息 2违禁物品 3辱骂骚扰 4其他 */
    @LoginRequired
    @PostMapping("/submit")
    @RateLimit(key = "report", limit = 10, window = 600, dimension = RateLimit.Dimension.USER,
            message = "举报提交过于频繁")
    public Result<ReportVO> submit(@Valid @RequestBody ReportCreateDTO dto) {
        return Result.success("举报已提交，管理员会尽快处理", reportModuleService.submit(dto, UserContext.getUserId()));
    }

    /** 我的举报记录 */
    @LoginRequired
    @GetMapping("/mine")
    public Result<PageResult<ReportVO>> mine(@RequestParam(value = "page", defaultValue = "1") int page,
                                             @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.success(reportModuleService.myReports(page, size, UserContext.getUserId()));
    }
}
