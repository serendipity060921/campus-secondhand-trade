package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.cache.CacheEvictor;
import com.campus.trade.common.exception.AdminException;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.exception.UserException;
import com.campus.trade.dto.AdminProductQueryDTO;
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
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.FavoriteService;
import com.campus.trade.service.MessageService;
import com.campus.trade.service.OnlineStatusService;
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
import com.campus.trade.websocket.ChatSessionRegistry;

/**
 * 管理端业务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>管理端每一条写操作都要<b>留审计日志</b>，而审计日志的价值取决于"状态校验是否严格"——
 * 因此本测试重点固定状态机与自我保护规则：</p>
 *
 * <ul>
 *   <li>★ 商品审核只允许对<b>待审核</b>商品执行（防重复审核、防对已售出商品改状态）；
 *       强制下架不允许作用在<b>交易中 / 已售出</b>的商品上；</li>
 *   <li>★ 用户状态：不能禁用<b>自己</b>、不能禁用<b>其他管理员</b>；禁用时还要
 *       <b>断开对方的 WebSocket 连接并置离线</b>（否则被禁用户仍能实时收发消息）；</li>
 *   <li>★ 举报只能处理<b>一次</b>（已处理的再提交要报错）；</li>
 *   <li>★ 看板趋势必须补齐为连续 7 天（缺的填 0），否则前端折线图断点；</li>
 *   <li>★ 写审计日志失败不能影响主业务（旁路逻辑）。</li>
 * </ul>
 */
class AdminServiceImplTest {

    private ProductService productService;
    private CategoryService categoryService;
    private UserService userService;
    private OrderService orderService;
    private FavoriteService favoriteService;
    private MessageService messageService;
    private ReportService reportService;
    private AdminLogService adminLogService;
    private ReportModuleService reportModuleService;
    private AdminStatMapper adminStatMapper;
    private CacheEvictor cacheEvictor;
    private OnlineStatusService onlineStatusService;
    private ChatSessionRegistry chatSessionRegistry;

    private AdminServiceImpl service;

    private static final Long ADMIN_ID = 1L;

    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (Class<?> c : new Class<?>[]{Product.class, User.class, Order.class, Report.class,
                AdminLog.class, Category.class, Favorite.class, Message.class}) {
            TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""), c);
        }
    }

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        categoryService = mock(CategoryService.class);
        userService = mock(UserService.class);
        orderService = mock(OrderService.class);
        favoriteService = mock(FavoriteService.class);
        messageService = mock(MessageService.class);
        reportService = mock(ReportService.class);
        adminLogService = mock(AdminLogService.class);
        reportModuleService = mock(ReportModuleService.class);
        adminStatMapper = mock(AdminStatMapper.class);
        cacheEvictor = mock(CacheEvictor.class);
        onlineStatusService = mock(OnlineStatusService.class);
        chatSessionRegistry = mock(ChatSessionRegistry.class);

        // 默认：统计类调用返回空，避免各用例重复打桩
        when(adminStatMapper.sumGmv()).thenReturn(BigDecimal.TEN);
        when(adminStatMapper.sumGmvSince(any())).thenReturn(BigDecimal.ONE);
        when(adminStatMapper.countNewUsersByDay(any())).thenReturn(new ArrayList<>());
        when(adminStatMapper.countNewProductsByDay(any())).thenReturn(new ArrayList<>());
        when(adminStatMapper.countOrdersByDay(any())).thenReturn(new ArrayList<>());
        when(adminStatMapper.countProductsByCategory()).thenReturn(new ArrayList<>());
        when(adminStatMapper.countOrdersByStatus()).thenReturn(new ArrayList<>());
        when(adminStatMapper.countProductsByStatus()).thenReturn(new ArrayList<>());
        when(categoryService.list()).thenReturn(new ArrayList<>());
        when(categoryService.listByIds(any())).thenReturn(new ArrayList<>());
        // 举报补标题时会按 targetType 查商品/用户；给空集合避免 mock 返回 null
        when(productService.listByIds(any())).thenReturn(new ArrayList<>());
        when(userService.listByIds(any())).thenReturn(new ArrayList<>());

        service = new AdminServiceImpl(productService, categoryService, userService, orderService,
                favoriteService, messageService, reportService, adminLogService,
                reportModuleService, adminStatMapper, cacheEvictor, onlineStatusService,
                chatSessionRegistry);
    }

    private Product product(long id, int status, long sellerId) {
        Product p = new Product();
        p.setId(id);
        p.setStatus(status);
        p.setSellerId(sellerId);
        p.setTitle("教材 " + id);
        p.setPrice(new BigDecimal("25.50"));
        return p;
    }

    private User user(long id, int role, int status) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setNickname("用户" + id);
        u.setRole(role);
        u.setStatus(status);
        u.setCreditScore(100);
        return u;
    }

    @SuppressWarnings("unchecked")
    private <T> Page<T> pageOf(List<T> records, long total) {
        Page<T> page = new Page<>(1, 10);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    /**
     * 举报 VO 夹具。
     * ★ 必须给 targetType / targetId 真值：补标题时按 targetType 分派，
     *   若两者为 null 会走 else 分支并触发 `Map.of().get(null)` → NPE
     *   （Map.of() 不接受 null 键）。生产库这两个字段非空，因此这不是产品缺陷，
     *   而是"夹具不像真实数据"导致的测试问题 —— 夹具要贴近真实数据。
     */
    private ReportVO reportVO() {
        ReportVO vo = new ReportVO();
        vo.setTargetType(1);
        vo.setTargetId(5L);
        return vo;
    }

    @Nested
    @DisplayName("一、看板")
    class Dashboard {

        @Test
        @DisplayName("★ 趋势补齐为连续 7 天（缺的填 0），避免前端折线断点")
        void trendFilledToSevenDays() {
            // 只给"今天"一条数据，其余 6 天应补 0
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            when(adminStatMapper.countNewUsersByDay(any()))
                    .thenReturn(List.of(new NameCountVO(today, 3L, BigDecimal.ZERO)));

            DashboardVO vo = service.dashboard();

            assertEquals(7, vo.getUserTrend().size(), "应为连续 7 天");
            assertEquals(7, vo.getProductTrend().size());
            assertEquals(7, vo.getOrderTrend().size());
            // 最后一天是今天且有值，前面是补的 0
            NameCountVO last = vo.getUserTrend().get(6);
            assertEquals(today, last.getLabel());
            assertEquals(3L, last.getValue());
            assertEquals(0L, vo.getUserTrend().get(0).getValue(), "空缺日期应补 0");
        }

        @Test
        @DisplayName("★ GMV 为 null 时按 0 处理（聚合函数无数据会返回 null）")
        void nullGmvBecomesZero() {
            when(adminStatMapper.sumGmv()).thenReturn(null);
            when(adminStatMapper.sumGmvSince(any())).thenReturn(null);

            DashboardVO vo = service.dashboard();

            assertEquals(BigDecimal.ZERO, vo.getOverview().getGmv());
            assertEquals(BigDecimal.ZERO, vo.getOverview().getGmv7d());
        }

        @Test
        @DisplayName("看板包含待审核与待处理举报（管理员的待办）")
        void pendingCounts() {
            when(productService.count((Wrapper<Product>) any())).thenReturn(2L);
            when(reportService.count((Wrapper<Report>) any())).thenReturn(1L);

            DashboardVO vo = service.dashboard();

            assertNotNull(vo.getOverview());
            assertNotNull(vo.getGeneratedAt(), "应带生成时间");
        }
    }

    @Nested
    @DisplayName("二、★ 商品审核的状态机")
    class Audit {

        private AuditProductDTO dto(long productId, boolean approve, String remark) {
            AuditProductDTO d = new AuditProductDTO();
            d.setProductId(productId);
            d.setApprove(approve);
            d.setRemark(remark);
            return d;
        }

        @Test
        @DisplayName("商品不存在 → 3001")
        void productNotFound() {
            when(productService.getById(5L)).thenReturn(null);

            assertThrows(ProductException.class, () -> service.auditProduct(dto(5L, true, null), ADMIN_ID));
            verify(productService, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 只能审核「待审核」商品：已上架/已下架/已售出都不行")
        void onlyPendingCanBeAudited() {
            for (int status : new int[]{1, 3, 5}) {
                when(productService.getById(5L)).thenReturn(product(5L, status, 99L));
                assertThrows(AdminException.class,
                        () -> service.auditProduct(dto(5L, true, null), ADMIN_ID),
                        "status=" + status + " 不应允许审核");
            }
            verify(cacheEvictor, never()).productChanged(anyLong());
        }

        @Test
        @DisplayName("★ 审核通过 → 上架 + 记录审核人与上架时间 + 缓存失效")
        void approvePutsOnSale() {
            when(productService.getById(5L)).thenReturn(product(5L, 0, 99L));

            AdminProductVO vo = service.auditProduct(dto(5L, true, "资料齐全"), ADMIN_ID);

            assertNotNull(vo);
            verify(productService).updateById(org.mockito.ArgumentMatchers.argThat(p ->
                    p != null && Integer.valueOf(1).equals(p.getStatus())
                            && ADMIN_ID.equals(p.getAuditorId()) && p.getShelfTime() != null));
            verify(cacheEvictor).productChanged(5L);
        }

        @Test
        @DisplayName("★ 审核驳回 → 状态置为驳回（不设上架时间）")
        void rejectSetsRejected() {
            when(productService.getById(5L)).thenReturn(product(5L, 0, 99L));

            service.auditProduct(dto(5L, false, "图片与描述不符"), ADMIN_ID);

            verify(productService).updateById(org.mockito.ArgumentMatchers.argThat(p ->
                    p != null && Integer.valueOf(2).equals(p.getStatus()) && p.getShelfTime() == null));
            verify(cacheEvictor).productChanged(5L);
        }

        @Test
        @DisplayName("★ 审核要写审计日志（谁、什么时候、对哪件商品、什么意见）")
        void writesAuditLog() {
            when(productService.getById(5L)).thenReturn(product(5L, 0, 99L));

            service.auditProduct(dto(5L, true, "资料齐全"), ADMIN_ID);

            verify(adminLogService).save(org.mockito.ArgumentMatchers.argThat(log ->
                    log != null && "AUDIT_PRODUCT".equals(log.getAction())
                            && ADMIN_ID.equals(log.getAdminId()) && Long.valueOf(5L).equals(log.getTargetId())));
        }

        @Test
        @DisplayName("★ 审计日志写入失败不影响审核结果（旁路逻辑要吞异常）")
        void logFailureDoesNotBreakAudit() {
            when(productService.getById(5L)).thenReturn(product(5L, 0, 99L));
            doThrow(new RuntimeException("日志表挂了")).when(adminLogService).save(any());

            assertNotNull(service.auditProduct(dto(5L, true, null), ADMIN_ID),
                    "日志失败不应让审核整体失败");
        }
    }

    @Nested
    @DisplayName("三、★ 强制下架不能动交易中的商品")
    class Offline {

        @Test
        @DisplayName("商品不存在 → 3001")
        void notFound() {
            when(productService.getById(5L)).thenReturn(null);
            assertThrows(ProductException.class, () -> service.offlineProduct(5L, ADMIN_ID, "违规"));
        }

        @Test
        @DisplayName("★ 交易中(4) / 已售出(5) 的商品不允许下架（涉及买家权益）")
        void cannotOfflineTradingOrSold() {
            for (int status : new int[]{4, 5}) {
                when(productService.getById(5L)).thenReturn(product(5L, status, 99L));
                assertThrows(ProductException.class,
                        () -> service.offlineProduct(5L, ADMIN_ID, "违规"),
                        "status=" + status + " 不应允许下架");
            }
            verify(productService, never()).updateById(any());
        }

        @Test
        @DisplayName("在售商品可以强制下架，并记录原因与缓存失效")
        void offlineOnSale() {
            when(productService.getById(5L)).thenReturn(product(5L, 1, 99L));

            service.offlineProduct(5L, ADMIN_ID, "违规商品");

            verify(productService).updateById(org.mockito.ArgumentMatchers.argThat(p ->
                    p != null && Integer.valueOf(3).equals(p.getStatus())));
            verify(cacheEvictor).productChanged(5L);
            verify(adminLogService).save(any());
        }

        @Test
        @DisplayName("不传原因时给默认说明「管理员下架」")
        void defaultReason() {
            when(productService.getById(5L)).thenReturn(product(5L, 1, 99L));

            service.offlineProduct(5L, ADMIN_ID, "   ");

            verify(productService).updateById(org.mockito.ArgumentMatchers.argThat(p ->
                    p != null && "管理员下架".equals(p.getAuditRemark())));
        }
    }

    @Nested
    @DisplayName("四、★ 用户状态：自我保护与实时连接清理")
    class UserStatus {

        private UserStatusDTO dto(long userId, int status) {
            UserStatusDTO d = new UserStatusDTO();
            d.setUserId(userId);
            d.setStatus(status);
            return d;
        }

        @Test
        @DisplayName("状态值只能是 0 或 1")
        void statusMustBeValid() {
            assertThrows(AdminException.class, () -> service.changeUserStatus(dto(9L, 7), ADMIN_ID));
        }

        @Test
        @DisplayName("用户不存在 → 报错")
        void userNotFound() {
            when(userService.getById(9L)).thenReturn(null);
            assertThrows(UserException.class, () -> service.changeUserStatus(dto(9L, 0), ADMIN_ID));
        }

        @Test
        @DisplayName("★ 不能禁用自己")
        void cannotDisableSelf() {
            when(userService.getById(ADMIN_ID)).thenReturn(user(ADMIN_ID, 1, 1));

            assertThrows(AdminException.class, () -> service.changeUserStatus(dto(ADMIN_ID, 0), ADMIN_ID));
            verify(userService, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 不能禁用其他管理员账号")
        void cannotDisableAnotherAdmin() {
            when(userService.getById(2L)).thenReturn(user(2L, 1, 1));

            assertThrows(AdminException.class, () -> service.changeUserStatus(dto(2L, 0), ADMIN_ID));
            verify(userService, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 禁用普通用户 → 断开其 WebSocket 连接并置离线（否则仍能收发消息）")
        void disableClosesRealtimeSessions() {
            when(userService.getById(9L)).thenReturn(user(9L, 0, 1));
            when(chatSessionRegistry.closeUser(9L)).thenReturn(2);

            service.changeUserStatus(dto(9L, 0), ADMIN_ID);

            verify(userService).updateById(org.mockito.ArgumentMatchers.argThat(u ->
                    u != null && Integer.valueOf(0).equals(u.getStatus())));
            verify(chatSessionRegistry).closeUser(9L);
            verify(onlineStatusService).offline(9L);
            verify(adminLogService).save(any());
        }

        @Test
        @DisplayName("启用用户不触碰实时连接")
        void enableDoesNotTouchSessions() {
            when(userService.getById(9L)).thenReturn(user(9L, 0, 0));

            service.changeUserStatus(dto(9L, 1), ADMIN_ID);

            verify(chatSessionRegistry, never()).closeUser(anyLong());
            verify(onlineStatusService, never()).offline(anyLong());
        }
    }

    @Nested
    @DisplayName("五、★ 举报只能处理一次")
    class Reports {

        private ReportHandleDTO dto(long reportId, int action) {
            ReportHandleDTO d = new ReportHandleDTO();
            d.setReportId(reportId);
            d.setAction(action);
            return d;
        }

        private Report report(long id, int status) {
            Report r = new Report();
            r.setId(id);
            r.setStatus(status);
            r.setTargetType(1);
            r.setTargetId(5L);
            return r;
        }

        @Test
        @DisplayName("举报不存在 → 报错")
        void notFound() {
            when(reportService.getById(3L)).thenReturn(null);
            assertThrows(AdminException.class, () -> service.handleReport(dto(3L, 1), ADMIN_ID));
        }

        @Test
        @DisplayName("★ 已处理的举报不能再次处理")
        void cannotHandleTwice() {
            when(reportService.getById(3L)).thenReturn(report(3L, 1));

            assertThrows(AdminException.class, () -> service.handleReport(dto(3L, 1), ADMIN_ID));
            verify(reportService, never()).updateById(any());
        }

        @Test
        @DisplayName("action=1 → 已处理；action=2 → 忽略，且各自有合适的默认说明")
        void handlesAndIgnores() {
            when(reportService.getById(3L)).thenReturn(report(3L, 0));
            when(reportService.getById(4L)).thenReturn(report(4L, 0));
            when(reportModuleService.toVOList(any())).thenReturn(List.of(reportVO()));

            service.handleReport(dto(3L, 1), ADMIN_ID);
            verify(reportService).updateById(org.mockito.ArgumentMatchers.argThat(r ->
                    r != null && Integer.valueOf(1).equals(r.getStatus()) && r.getHandleTime() != null));

            service.handleReport(dto(4L, 2), ADMIN_ID);
            verify(reportService).updateById(org.mockito.ArgumentMatchers.argThat(r ->
                    r != null && Integer.valueOf(2).equals(r.getStatus())));
        }

        @Test
        @DisplayName("举报列表按状态筛选并补上举报对象标题")
        void reportList() {
            when(reportService.page(any(Page.class), any())).thenReturn(pageOf(List.of(report(3L, 0)), 1L));
            when(reportModuleService.toVOList(any())).thenReturn(List.of(reportVO()));

            var result = service.reportList(0, 1, 10);

            assertEquals(1L, result.getTotal());
            verify(reportModuleService).toVOList(any());
        }
    }

    @Nested
    @DisplayName("六、列表与日志")
    class Lists {

        @Test
        @DisplayName("商品列表：状态/卖家/关键词都是可选条件，返回分页结构")
        void productList() {
            when(productService.page(any(Page.class), any())).thenReturn(pageOf(List.of(product(5L, 0, 99L)), 1L));

            AdminProductQueryDTO query = new AdminProductQueryDTO();
            query.setPage(1);
            query.setSize(10);
            query.setStatus(0);

            var result = service.productList(query);

            assertEquals(1L, result.getTotal());
            assertEquals(1, result.getRecords().size());
        }

        @Test
        @DisplayName("用户列表：返回分页结构")
        void userList() {
            when(userService.page(any(Page.class), any())).thenReturn(pageOf(List.of(user(9L, 0, 1)), 1L));

            var query = new com.campus.trade.dto.AdminUserQueryDTO();
            query.setPage(1);
            query.setSize(10);

            var result = service.userList(query);

            assertEquals(1L, result.getTotal());
            assertEquals(1, result.getRecords().size());
        }

        @Test
        @DisplayName("操作日志列表：按时间倒序分页")
        void logList() {
            AdminLog log = new AdminLog();
            log.setId(1L);
            when(adminLogService.page(any(Page.class), any())).thenReturn(pageOf(List.of(log), 1L));

            var result = service.logList(1, 10);

            assertEquals(1L, result.getTotal());
            assertTrue(result.getRecords().size() == 1);
        }

        @Test
        @DisplayName("★ 没有 HTTP 请求上下文时写审计日志也不能抛异常（单元测试/定时任务场景）")
        void writeLogWithoutRequestContext() {
            when(productService.getById(5L)).thenReturn(product(5L, 0, 99L));
            AuditProductDTO d = new AuditProductDTO();
            d.setProductId(5L);
            d.setApprove(true);

            assertNotNull(service.auditProduct(d, ADMIN_ID),
                    "无请求上下文时（拿不到 IP）也必须能正常审核");
        }
    }
}
