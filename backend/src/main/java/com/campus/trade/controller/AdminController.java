package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.AdminProductQueryDTO;
import com.campus.trade.dto.AdminUserQueryDTO;
import com.campus.trade.dto.AuditProductDTO;
import com.campus.trade.dto.ReportHandleDTO;
import com.campus.trade.dto.UserStatusDTO;
import com.campus.trade.entity.AdminLog;
import com.campus.trade.service.AdminService;
import com.campus.trade.vo.AdminProductVO;
import com.campus.trade.vo.AdminUserVO;
import com.campus.trade.vo.DashboardVO;
import com.campus.trade.vo.ReportVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台接口（v0.13）。
 *
 * <p><b>类级别标注 {@code @LoginRequired(admin = true)}</b>：该 Controller 下所有接口
 * 都要求登录且角色为管理员（role = 1），非管理员访问统一返回 403「没有操作权限」。</p>
 *
 * <table border="1">
 *   <caption>接口清单</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/admin/dashboard</td><td>数据看板（概览 + 趋势 + 分布）</td></tr>
 *   <tr><td>GET</td><td>/api/admin/product/list</td><td>全状态商品列表</td></tr>
 *   <tr><td>PUT</td><td>/api/admin/product/audit</td><td>商品审核（通过 / 驳回）</td></tr>
 *   <tr><td>PUT</td><td>/api/admin/product/offline</td><td>强制下架商品</td></tr>
 *   <tr><td>GET</td><td>/api/admin/user/list</td><td>用户列表</td></tr>
 *   <tr><td>PUT</td><td>/api/admin/user/status</td><td>启用 / 禁用用户</td></tr>
 *   <tr><td>GET</td><td>/api/admin/report/list</td><td>举报列表</td></tr>
 *   <tr><td>PUT</td><td>/api/admin/report/handle</td><td>处理举报</td></tr>
 *   <tr><td>GET</td><td>/api/admin/log/list</td><td>管理员操作日志</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@LoginRequired(admin = true)
public class AdminController {

    private final AdminService adminService;

    /** 数据看板 */
    @GetMapping("/dashboard")
    public Result<DashboardVO> dashboard() {
        return Result.success(adminService.dashboard());
    }

    /** 商品列表（含全部状态，可按状态/关键词/卖家筛选） */
    @GetMapping("/product/list")
    public Result<PageResult<AdminProductVO>> productList(@Valid AdminProductQueryDTO query) {
        return Result.success(adminService.productList(query));
    }

    /** 商品审核：approve=true 通过（上架），false 驳回（可填 remark 说明原因） */
    @PutMapping("/product/audit")
    public Result<AdminProductVO> audit(@Valid @RequestBody AuditProductDTO dto) {
        return Result.success("审核完成", adminService.auditProduct(dto, UserContext.getUserId()));
    }

    /** 强制下架（可操作他人商品，交易中/已售出不可下架） */
    @PutMapping("/product/offline")
    public Result<AdminProductVO> offline(@RequestParam("productId") Long productId,
                                          @RequestParam(value = "reason", required = false) String reason) {
        return Result.success("已下架", adminService.offlineProduct(productId, UserContext.getUserId(), reason));
    }

    /** 用户列表 */
    @GetMapping("/user/list")
    public Result<PageResult<AdminUserVO>> userList(@Valid AdminUserQueryDTO query) {
        return Result.success(adminService.userList(query));
    }

    /** 启用 / 禁用用户 */
    @PutMapping("/user/status")
    public Result<AdminUserVO> changeUserStatus(@Valid @RequestBody UserStatusDTO dto) {
        return Result.success("操作成功", adminService.changeUserStatus(dto, UserContext.getUserId()));
    }

    /** 举报列表（status 为空查全部：0待处理 1已处理 2已忽略） */
    @GetMapping("/report/list")
    public Result<PageResult<ReportVO>> reportList(@RequestParam(value = "status", required = false) Integer status,
                                                   @RequestParam(value = "page", defaultValue = "1") int page,
                                                   @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.success(adminService.reportList(status, page, size));
    }

    /** 处理举报（action：1 处理 / 2 忽略） */
    @PutMapping("/report/handle")
    public Result<ReportVO> handleReport(@Valid @RequestBody ReportHandleDTO dto) {
        return Result.success("处理完成", adminService.handleReport(dto, UserContext.getUserId()));
    }

    /** 管理员操作日志 */
    @GetMapping("/log/list")
    public Result<PageResult<AdminLog>> logList(@RequestParam(value = "page", defaultValue = "1") int page,
                                                @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.success(adminService.logList(page, size));
    }
}
