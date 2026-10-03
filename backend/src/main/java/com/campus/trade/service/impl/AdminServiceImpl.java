package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.cache.CacheEvictor;
import com.campus.trade.common.constant.ProductStatus;
import com.campus.trade.common.exception.AdminException;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.AdminProductQueryDTO;
import com.campus.trade.dto.AdminUserQueryDTO;
import com.campus.trade.dto.AuditProductDTO;
import com.campus.trade.dto.ReportHandleDTO;
import com.campus.trade.dto.UserStatusDTO;
import com.campus.trade.entity.AdminLog;
import com.campus.trade.entity.Category;
import com.campus.trade.entity.Favorite;
import com.campus.trade.entity.Message;
import com.campus.trade.entity.Order;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.Report;
import com.campus.trade.entity.User;
import com.campus.trade.mapper.AdminStatMapper;
import com.campus.trade.service.AdminLogService;
import com.campus.trade.service.AdminService;
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.FavoriteService;
import com.campus.trade.service.MessageService;
import com.campus.trade.service.OrderService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.ReportModuleService;
import com.campus.trade.service.ReportService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.AdminProductVO;
import com.campus.trade.vo.AdminUserVO;
import com.campus.trade.vo.DashboardVO;
import com.campus.trade.vo.NameCountVO;
import com.campus.trade.vo.ReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理后台业务实现（v0.13）。
 *
 * <p>实现要点：</p>
 * <ol>
 *   <li><b>统计下推数据库</b>：趋势、分布全部用 SQL 聚合（见 AdminStatMapper），
 *       应用层只做"补齐空缺日期"这类轻量加工；</li>
 *   <li><b>批量补齐关联信息</b>：商品列表的卖家、分类、审核人一次性批量查询，避免 N+1；</li>
 *   <li><b>写操作三件套</b>：改数据 → 记 admin_log → 失效相关缓存；</li>
 *   <li><b>越权与状态校验</b>：只有待审核商品可审核；交易中/已售出不可下架；管理员不能禁用自己。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    /** 用户角色 / 状态 */
    private static final int ROLE_ADMIN = 1;
    private static final int USER_STATUS_NORMAL = 1;
    private static final int USER_STATUS_DISABLED = 0;
    /** 举报状态 */
    private static final int REPORT_PENDING = 0;
    private static final int REPORT_HANDLED = 1;
    private static final int REPORT_IGNORED = 2;
    /** 趋势天数 */
    private static final int TREND_DAYS = 7;

    private final ProductService productService;
    private final CategoryService categoryService;
    private final UserService userService;
    private final OrderService orderService;
    private final FavoriteService favoriteService;
    private final MessageService messageService;
    private final ReportService reportService;
    private final AdminLogService adminLogService;
    private final ReportModuleService reportModuleService;
    private final AdminStatMapper adminStatMapper;
    private final CacheEvictor cacheEvictor;

    /* ==================== 1. 数据看板 ==================== */

    @Override
    public DashboardVO dashboard() {
        DashboardVO vo = new DashboardVO();
        DashboardVO.Overview overview = vo.getOverview();

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime since7d = LocalDate.now().minusDays(TREND_DAYS - 1L).atStartOfDay();

        // 总量
        overview.setUserCount(userService.count());
        overview.setProductCount(productService.count());
        overview.setOnSaleCount(productService.count(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, ProductStatus.ON_SALE.getCode())));
        overview.setOrderCount(orderService.count());
        overview.setFinishedOrderCount(orderService.count(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, 3)));
        overview.setMessageCount(messageService.count());
        overview.setFavoriteCount(favoriteService.count());

        // 金额与今日新增
        BigDecimal gmv = adminStatMapper.sumGmv();
        overview.setGmv(gmv == null ? BigDecimal.ZERO : gmv);
        BigDecimal gmv7d = adminStatMapper.sumGmvSince(since7d);
        overview.setGmv7d(gmv7d == null ? BigDecimal.ZERO : gmv7d);
        overview.setTodayNewUsers(userService.count(new LambdaQueryWrapper<User>()
                .ge(User::getCreateTime, todayStart)));
        overview.setTodayNewProducts(productService.count(new LambdaQueryWrapper<Product>()
                .ge(Product::getCreateTime, todayStart)));
        overview.setTodayNewOrders(orderService.count(new LambdaQueryWrapper<Order>()
                .ge(Order::getCreateTime, todayStart)));

        // 待办
        overview.setPendingAuditCount(productService.count(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, ProductStatus.PENDING.getCode())));
        overview.setPendingReportCount(reportService.count(new LambdaQueryWrapper<Report>()
                .eq(Report::getStatus, REPORT_PENDING)));

        // 趋势（补齐空缺日期，保证图表 X 轴连续）
        vo.setUserTrend(fillMissingDays(adminStatMapper.countNewUsersByDay(since7d)));
        vo.setProductTrend(fillMissingDays(adminStatMapper.countNewProductsByDay(since7d)));
        vo.setOrderTrend(fillMissingDays(adminStatMapper.countOrdersByDay(since7d)));

        vo.setCategoryDist(adminStatMapper.countProductsByCategory());
        vo.setOrderStatusDist(adminStatMapper.countOrdersByStatus());
        vo.setProductStatusDist(adminStatMapper.countProductsByStatus());
        vo.setGeneratedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return vo;
    }

    /** 把"只有有数据的日期"补齐为连续 7 天（缺的填 0），前端折线图才不会断点 */
    private List<NameCountVO> fillMissingDays(List<NameCountVO> rows) {
        Map<String, NameCountVO> byLabel = new LinkedHashMap<>();
        for (NameCountVO row : rows) {
            byLabel.put(row.getLabel(), row);
        }
        List<NameCountVO> result = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            String label = LocalDate.now().minusDays(i).format(formatter);
            NameCountVO row = byLabel.get(label);
            result.add(row == null ? new NameCountVO(label, 0L, BigDecimal.ZERO) : row);
        }
        return result;
    }

    /* ==================== 2. 商品管理 ==================== */

    @Override
    public PageResult<AdminProductVO> productList(AdminProductQueryDTO query) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(query.getStatus() != null, Product::getStatus, query.getStatus())
                .eq(query.getSellerId() != null, Product::getSellerId, query.getSellerId())
                .like(StringUtils.hasText(query.getKeyword()), Product::getTitle, query.getKeyword())
                .orderByDesc(Product::getCreateTime);
        Page<Product> page = productService.page(new Page<>(query.getPage(), query.getSize()), wrapper);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                toProductVOList(page.getRecords()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminProductVO auditProduct(AuditProductDTO dto, Long adminId) {
        Product product = productService.getById(dto.getProductId());
        if (product == null) {
            throw ProductException.notFound();
        }
        // 只有待审核商品才能审核（避免重复审核或对已售出商品改状态）
        if (!ProductStatus.PENDING.getCode().equals(product.getStatus())) {
            throw AdminException.auditStatusIllegal();
        }

        boolean approve = Boolean.TRUE.equals(dto.getApprove());
        Product update = new Product();
        update.setId(product.getId());
        update.setStatus(approve ? ProductStatus.ON_SALE.getCode() : ProductStatus.REJECTED.getCode());
        update.setAuditorId(adminId);
        update.setAuditTime(LocalDateTime.now());
        update.setAuditRemark(StringUtils.hasText(dto.getRemark()) ? dto.getRemark().trim() : null);
        if (approve) {
            update.setShelfTime(LocalDateTime.now());
        }
        productService.updateById(update);

        writeLog(adminId, "AUDIT_PRODUCT", "PRODUCT", product.getId(),
                (approve ? "审核通过：" : "审核驳回：") + product.getTitle()
                        + (StringUtils.hasText(dto.getRemark()) ? "；意见：" + dto.getRemark() : ""));
        // 缓存失效：审核结果决定商品是否进入列表与推荐
        cacheEvictor.productChanged(product.getId());
        log.info("[商品审核] productId={} 结果={} 管理员={}", product.getId(), approve ? "通过" : "驳回", adminId);
        return toProductVO(productService.getById(product.getId()), null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminProductVO offlineProduct(Long productId, Long adminId, String reason) {
        Product product = productService.getById(productId);
        if (product == null) {
            throw ProductException.notFound();
        }
        if (ProductStatus.TRADING.getCode().equals(product.getStatus())
                || ProductStatus.SOLD.getCode().equals(product.getStatus())) {
            throw ProductException.statusIllegal();
        }
        Product update = new Product();
        update.setId(productId);
        update.setStatus(ProductStatus.OFF_SHELF.getCode());
        update.setAuditRemark(StringUtils.hasText(reason) ? reason.trim() : "管理员下架");
        update.setAuditorId(adminId);
        update.setAuditTime(LocalDateTime.now());
        productService.updateById(update);

        writeLog(adminId, "OFFLINE_PRODUCT", "PRODUCT", productId,
                "强制下架：" + product.getTitle() + (StringUtils.hasText(reason) ? "；原因：" + reason : ""));
        cacheEvictor.productChanged(productId);
        log.info("[强制下架] productId={} 管理员={} 原因={}", productId, adminId, reason);
        return toProductVO(productService.getById(productId), null, null, null);
    }

    /* ==================== 3. 用户管理 ==================== */

    @Override
    public PageResult<AdminUserVO> userList(AdminUserQueryDTO query) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(query.getRole() != null, User::getRole, query.getRole())
                .eq(query.getStatus() != null, User::getStatus, query.getStatus())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(User::getUsername, query.getKeyword()).or()
                        .like(User::getNickname, query.getKeyword()).or()
                        .like(User::getStudentNo, query.getKeyword()).or()
                        .like(User::getPhone, query.getKeyword()))
                .orderByDesc(User::getCreateTime);
        Page<User> page = userService.page(new Page<>(query.getPage(), query.getSize()), wrapper);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                toUserVOList(page.getRecords()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO changeUserStatus(UserStatusDTO dto, Long adminId) {
        if (dto.getStatus() != USER_STATUS_NORMAL && dto.getStatus() != USER_STATUS_DISABLED) {
            throw AdminException.userStatusIllegal();
        }
        User user = userService.getById(dto.getUserId());
        if (user == null) {
            throw UserException.userNotFound();
        }
        if (Objects.equals(dto.getUserId(), adminId)) {
            throw AdminException.cannotDisableSelf();
        }
        if (USER_STATUS_DISABLED == dto.getStatus() && ROLE_ADMIN == (user.getRole() == null ? 0 : user.getRole())) {
            throw new AdminException(400, "不能禁用管理员账号");
        }

        User update = new User();
        update.setId(user.getId());
        update.setStatus(dto.getStatus());
        userService.updateById(update);

        writeLog(adminId, USER_STATUS_NORMAL == dto.getStatus() ? "ENABLE_USER" : "DISABLE_USER",
                "USER", user.getId(),
                (USER_STATUS_NORMAL == dto.getStatus() ? "启用用户：" : "禁用用户：") + user.getUsername());
        log.info("[用户状态变更] userId={} status={} 管理员={}", user.getId(), dto.getStatus(), adminId);
        return toUserVOList(List.of(userService.getById(user.getId()))).get(0);
    }

    /* ==================== 4. 举报处理 ==================== */

    @Override
    public PageResult<ReportVO> reportList(Integer status, int page, int size) {
        Page<Report> result = reportService.page(new Page<>(page, size),
                new LambdaQueryWrapper<Report>()
                        .eq(status != null, Report::getStatus, status)
                        .orderByAsc(Report::getStatus)
                        .orderByDesc(Report::getCreateTime));
        List<ReportVO> vos = reportModuleService.toVOList(result.getRecords());
        // 补上举报对象标题（商品名 / 用户名）
        fillTargetTitles(vos);
        return new PageResult<>(result.getTotal(), result.getPages(), result.getCurrent(), result.getSize(), vos);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportVO handleReport(ReportHandleDTO dto, Long adminId) {
        Report report = reportService.getById(dto.getReportId());
        if (report == null) {
            throw AdminException.reportNotFound();
        }
        if (!Integer.valueOf(REPORT_PENDING).equals(report.getStatus())) {
            throw AdminException.reportHandled();
        }

        boolean ignore = dto.getAction() != null && dto.getAction() == 2;
        Report update = new Report();
        update.setId(report.getId());
        update.setStatus(ignore ? REPORT_IGNORED : REPORT_HANDLED);
        update.setHandleAdminId(adminId);
        update.setHandleResult(StringUtils.hasText(dto.getResult()) ? dto.getResult().trim()
                : (ignore ? "经核实不构成违规，已忽略" : "已处理"));
        update.setHandleTime(LocalDateTime.now());
        reportService.updateById(update);

        writeLog(adminId, "HANDLE_REPORT", "REPORT", report.getId(),
                (ignore ? "忽略举报：" : "处理举报：") + update.getHandleResult());
        log.info("[举报处理] reportId={} 动作={} 管理员={}", report.getId(), ignore ? "忽略" : "处理", adminId);

        List<ReportVO> vos = reportModuleService.toVOList(List.of(reportService.getById(report.getId())));
        fillTargetTitles(vos);
        return vos.get(0);
    }

    /* ==================== 5. 操作日志 ==================== */

    @Override
    public PageResult<AdminLog> logList(int page, int size) {
        Page<AdminLog> result = adminLogService.page(new Page<>(page, size),
                new LambdaQueryWrapper<AdminLog>().orderByDesc(AdminLog::getCreateTime));
        return new PageResult<>(result.getTotal(), result.getPages(), result.getCurrent(), result.getSize(),
                result.getRecords());
    }

    /** 记录管理员操作日志（失败不影响主业务） */
    private void writeLog(Long adminId, String action, String targetType, Long targetId, String detail) {
        try {
            User admin = userService.getById(adminId);
            AdminLog entity = new AdminLog();
            entity.setAdminId(adminId);
            entity.setAdminName(admin == null ? null : admin.getUsername());
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setDetail(detail);
            entity.setIp(currentIp());
            entity.setDeleted(0);
            adminLogService.save(entity);
        } catch (Exception e) {
            log.warn("[操作日志写入失败] adminId={} action={} 原因={}", adminId, action, e.getMessage());
        }
    }

    private String currentIp() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attributes =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            return attributes == null ? null : attributes.getRequest().getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== 6. 转换（批量补齐关联信息，避免 N+1） ==================== */

    private List<AdminProductVO> toProductVOList(List<Product> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        Set<Long> categoryIds = products.stream().map(Product::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> userIds = new HashSet<>();
        products.forEach(p -> {
            if (p.getSellerId() != null) {
                userIds.add(p.getSellerId());
            }
            if (p.getAuditorId() != null) {
                userIds.add(p.getAuditorId());
            }
        });
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryService.listByIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        return products.stream().map(p -> toProductVO(p, categoryNames, users, null)).collect(Collectors.toList());
    }

    private AdminProductVO toProductVO(Product product, Map<Long, String> categoryNames,
                                       Map<Long, User> users, String ignored) {
        AdminProductVO vo = new AdminProductVO();
        BeanUtils.copyProperties(product, vo);
        vo.setStatusLabel(ProductStatus.labelOf(product.getStatus()));

        // 单条转换时按需查询（审核/下架接口返回单条数据）
        if (product.getCategoryId() != null) {
            Category category = categoryService.getById(product.getCategoryId());
            vo.setCategoryName(category == null ? null : category.getName());
        }
        if (product.getSellerId() != null) {
            User seller = userService.getById(product.getSellerId());
            if (seller != null) {
                vo.setSellerNickname(seller.getNickname());
                vo.setSellerUsername(seller.getUsername());
                vo.setSellerCreditScore(seller.getCreditScore());
            }
        }
        if (product.getAuditorId() != null) {
            User auditor = userService.getById(product.getAuditorId());
            vo.setAuditorName(auditor == null ? null : auditor.getNickname());
        }
        return vo;
    }

    private List<AdminUserVO> toUserVOList(List<User> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> ids = users.stream().map(User::getId).collect(Collectors.toList());
        Map<Long, Long> productCount = countByProduct(ids);
        Map<Long, Long> orderCount = countByBuyer(ids);
        Map<Long, Long> reportCount = countByReporter(ids);

        return users.stream().map(u -> {
            AdminUserVO vo = new AdminUserVO();
            BeanUtils.copyProperties(u, vo);
            vo.setRoleLabel(ROLE_ADMIN == (u.getRole() == null ? 0 : u.getRole()) ? "管理员" : "学生");
            vo.setStatusLabel(USER_STATUS_NORMAL == (u.getStatus() == null ? 0 : u.getStatus()) ? "正常" : "禁用");
            vo.setProductCount(productCount.getOrDefault(u.getId(), 0L));
            vo.setOrderCount(orderCount.getOrDefault(u.getId(), 0L));
            vo.setReportCount(reportCount.getOrDefault(u.getId(), 0L));
            return vo;
        }).collect(Collectors.toList());
    }

    private Map<Long, Long> countByProduct(Collection<Long> userIds) {
        return productService.list(new LambdaQueryWrapper<Product>()
                        .in(Product::getSellerId, userIds)
                        .select(Product::getSellerId))
                .stream().collect(Collectors.groupingBy(Product::getSellerId, Collectors.counting()));
    }

    private Map<Long, Long> countByBuyer(Collection<Long> userIds) {
        return orderService.list(new LambdaQueryWrapper<Order>()
                        .in(Order::getBuyerId, userIds)
                        .select(Order::getBuyerId))
                .stream().collect(Collectors.groupingBy(Order::getBuyerId, Collectors.counting()));
    }

    private Map<Long, Long> countByReporter(Collection<Long> userIds) {
        return reportService.list(new LambdaQueryWrapper<Report>()
                        .in(Report::getReporterId, userIds)
                        .select(Report::getReporterId))
                .stream().collect(Collectors.groupingBy(Report::getReporterId, Collectors.counting()));
    }

    /** 给举报列表补上"举报对象"的名称 */
    private void fillTargetTitles(List<ReportVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        Set<Long> productIds = vos.stream().filter(v -> Integer.valueOf(1).equals(v.getTargetType()))
                .map(ReportVO::getTargetId).collect(Collectors.toSet());
        Set<Long> userIds = vos.stream().filter(v -> Integer.valueOf(2).equals(v.getTargetType()))
                .map(ReportVO::getTargetId).collect(Collectors.toSet());
        Map<Long, Product> products = productIds.isEmpty() ? Map.of()
                : productService.listByIds(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        for (ReportVO vo : vos) {
            if (Integer.valueOf(1).equals(vo.getTargetType())) {
                Product product = products.get(vo.getTargetId());
                vo.setTargetTitle(product == null ? "（商品已删除）" : product.getTitle());
                vo.setTargetExtra(product == null ? null : ProductStatus.labelOf(product.getStatus()));
            } else {
                User user = users.get(vo.getTargetId());
                vo.setTargetTitle(user == null ? "（用户已删除）" : user.getNickname());
                vo.setTargetExtra(user == null ? null : user.getUsername()
                        + "｜信用分 " + user.getCreditScore());
            }
        }
    }
}
