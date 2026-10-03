package com.campus.trade.service;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.AdminProductQueryDTO;
import com.campus.trade.dto.AdminUserQueryDTO;
import com.campus.trade.dto.AuditProductDTO;
import com.campus.trade.dto.ReportHandleDTO;
import com.campus.trade.dto.UserStatusDTO;
import com.campus.trade.entity.AdminLog;
import com.campus.trade.vo.AdminProductVO;
import com.campus.trade.vo.AdminUserVO;
import com.campus.trade.vo.DashboardVO;
import com.campus.trade.vo.ReportVO;

/**
 * 管理后台业务（v0.13，仅管理员可调用）。
 *
 * <p>包含四块能力：</p>
 * <ol>
 *   <li><b>数据看板</b>：概览指标 + 近 7 天趋势 + 分类分布 + 状态分布（供 ECharts 渲染）；</li>
 *   <li><b>商品管理</b>：全状态商品列表、审核（通过/驳回）、强制下架；</li>
 *   <li><b>用户管理</b>：用户列表（含发布/成交/被举报统计）、启用/禁用；</li>
 *   <li><b>举报处理</b>：举报列表、处理/忽略，并记录管理员操作日志。</li>
 * </ol>
 *
 * <p>所有写操作都会写入 {@code admin_log}，便于责任追溯。</p>
 */
public interface AdminService {

    /** 数据看板 */
    DashboardVO dashboard();

    /** 全状态商品列表 */
    PageResult<AdminProductVO> productList(AdminProductQueryDTO query);

    /**
     * 审核商品：通过（上架）或驳回（审核不通过）。
     *
     * @throws com.campus.trade.common.exception.AdminException 商品不存在(3001) / 状态不允许审核(8003)
     */
    AdminProductVO auditProduct(AuditProductDTO dto, Long adminId);

    /**
     * 管理员强制下架商品（可操作他人商品）。
     *
     * @throws com.campus.trade.common.exception.ProductException 商品不存在(3001) / 交易中或已售出不可下架(3003)
     */
    AdminProductVO offlineProduct(Long productId, Long adminId, String reason);

    /** 用户列表 */
    PageResult<AdminUserVO> userList(AdminUserQueryDTO query);

    /**
     * 启用 / 禁用用户。
     *
     * @throws com.campus.trade.common.exception.UserException 用户不存在(2002)
     * @throws com.campus.trade.common.exception.AdminException 不能禁用自己(8004) / 状态非法(8005)
     */
    AdminUserVO changeUserStatus(UserStatusDTO dto, Long adminId);

    /** 举报列表（可按状态筛选） */
    PageResult<ReportVO> reportList(Integer status, int page, int size);

    /**
     * 处理举报（标记已处理 / 忽略）。
     *
     * @throws com.campus.trade.common.exception.AdminException 举报不存在(8001) / 已处理(8002)
     */
    ReportVO handleReport(ReportHandleDTO dto, Long adminId);

    /** 管理员操作日志（分页，最新在前） */
    PageResult<AdminLog> logList(int page, int size);
}
