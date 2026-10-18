package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.exception.AdminException;
import com.campus.trade.dto.ReportCreateDTO;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.Report;
import com.campus.trade.entity.User;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.ReportService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.ReportVO;

/**
 * 举报模块业务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>举报是"用户对用户"的矛盾入口，规则简单但边界多：</p>
 * <ul>
 *   <li>★ 举报对象必须存在（商品或用户），且<b>不能举报自己</b>；</li>
 *   <li>★ 举报内容空白要转 NULL，不能写空串；</li>
 *   <li>★ <b>VO 转换里的 null 安全</b>：未处理的举报 {@code handleAdminId} 为 null，
 *       所以查昵称的 Map 必须是可接受 null 的 HashMap —— 用 {@code Map.of()} 会直接抛 NPE
 *       （v0.13 用例 F3 踩过，代码里也写了注释）。这里专门把它钉成回归测试；</li>
 *   <li>★ 原因/状态的标签映射要覆盖"未知值"（脏数据不能让接口 500）。</li>
 * </ul>
 */
class ReportModuleServiceImplTest {

    private ReportService reportService;
    private ProductService productService;
    private UserService userService;
    private ReportModuleServiceImpl service;

    private static final Long UID = 11L;

    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (Class<?> c : new Class<?>[]{Report.class, Product.class, User.class}) {
            TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""), c);
        }
    }

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        productService = mock(ProductService.class);
        userService = mock(UserService.class);
        service = new ReportModuleServiceImpl(reportService, productService, userService);
    }

    private ReportCreateDTO dto(int targetType, long targetId, int reasonType, String content) {
        ReportCreateDTO d = new ReportCreateDTO();
        d.setTargetType(targetType);
        d.setTargetId(targetId);
        d.setReasonType(reasonType);
        d.setContent(content);
        return d;
    }

    private Product product(long id, long sellerId) {
        Product p = new Product();
        p.setId(id);
        p.setSellerId(sellerId);
        p.setTitle("九成新教材");
        return p;
    }

    private User user(long id, String nickname, String username) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        u.setUsername(username);
        return u;
    }

    private Report report(long id, int targetType, long targetId, Integer handleAdminId, Integer status) {
        Report r = new Report();
        r.setId(id);
        r.setReporterId(UID);
        r.setTargetType(targetType);
        r.setTargetId(targetId);
        r.setReasonType(1);
        r.setStatus(status);
        r.setHandleAdminId(handleAdminId == null ? null : handleAdminId.longValue());
        r.setCreateTime(LocalDateTime.now());
        return r;
    }

    @Nested
    @DisplayName("一、提交举报：对象必须存在且不能是自己")
    class Submit {

        @Test
        @DisplayName("举报商品：商品不存在 → 报错")
        void productNotFound() {
            when(productService.getById(5L)).thenReturn(null);

            assertThrows(AdminException.class, () -> service.submit(dto(1, 5L, 1, "虚假"), UID));
            verify(reportService, never()).save(any());
        }

        @Test
        @DisplayName("★ 不能举报自己发布的商品")
        void cannotReportOwnProduct() {
            when(productService.getById(5L)).thenReturn(product(5L, UID));

            assertThrows(AdminException.class, () -> service.submit(dto(1, 5L, 1, "虚假"), UID));
            verify(reportService, never()).save(any());
        }

        @Test
        @DisplayName("★ 举报商品成功：状态为待处理、内容去空格、带上商品标题")
        void submitProduct() {
            when(productService.getById(5L)).thenReturn(product(5L, 99L));
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            ReportVO vo = service.submit(dto(1, 5L, 2, "  疑似违禁  "), UID);

            assertEquals("九成新教材", vo.getTargetTitle());
            assertEquals("商品", vo.getTargetTypeLabel());
            verify(reportService).save(org.mockito.ArgumentMatchers.argThat(r ->
                    r != null && Integer.valueOf(0).equals(r.getStatus())
                            && Long.valueOf(UID).equals(r.getReporterId())
                            && "疑似违禁".equals(r.getContent())));
        }

        @Test
        @DisplayName("★ 举报用户：目标不存在 → 报错；举报自己 → 报错")
        void submitUserRules() {
            when(userService.getById(8L)).thenReturn(null);
            assertThrows(AdminException.class, () -> service.submit(dto(2, 8L, 1, "辱骂"), UID));

            when(userService.getById(UID)).thenReturn(user(UID, "我自己", "me"));
            assertThrows(AdminException.class, () -> service.submit(dto(2, UID, 1, "辱骂"), UID));
        }

        @Test
        @DisplayName("举报用户成功：标题取昵称、附加信息取用户名")
        void submitUserOk() {
            when(userService.getById(8L)).thenReturn(user(8L, "骗子", "cheater"));
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            ReportVO vo = service.submit(dto(2, 8L, 3, "辱骂"), UID);

            assertEquals("骗子", vo.getTargetTitle());
            assertEquals("cheater", vo.getTargetExtra());
            assertEquals("用户", vo.getTargetTypeLabel());
        }

        @Test
        @DisplayName("★ 举报内容为空白 → 存 NULL，不存空串")
        void blankContentBecomesNull() {
            when(productService.getById(5L)).thenReturn(product(5L, 99L));
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            service.submit(dto(1, 5L, 1, "   "), UID);

            verify(reportService).save(org.mockito.ArgumentMatchers.argThat(r ->
                    r != null && r.getContent() == null));
        }
    }

    @Nested
    @DisplayName("二、我的举报记录")
    class Mine {

        @Test
        @DisplayName("分页返回自己的举报（按时间倒序，由 wrapper 保证）")
        void myReports() {
            Page<Report> page = new Page<>(1, 10);
            page.setRecords(List.of(report(1L, 1, 5L, null, 0)));
            page.setTotal(1L);
            when(reportService.page(any(Page.class), any())).thenReturn(page);
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            var result = service.myReports(1, 10, UID);

            assertEquals(1L, result.getTotal());
            assertEquals(1, result.getRecords().size());
        }
    }

    @Nested
    @DisplayName("三、★ VO 转换的 null 安全（v0.13 用例 F3 的回归）")
    class ToVO {

        @Test
        @DisplayName("★ 未处理的举报（handleAdminId 为 null）转换时不能 NPE")
        void nullHandleAdminIsSafe() {
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            List<ReportVO> vos = service.toVOList(List.of(report(1L, 1, 5L, null, 0)));

            assertEquals(1, vos.size());
            assertNull(vos.get(0).getHandleAdminName(), "未处理的举报没有处理人");
            assertNotNull(vos.get(0).getTargetTypeLabel());
        }

        @Test
        @DisplayName("已处理的举报带上处理人昵称")
        void handledHasAdminName() {
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));
            when(userService.getById(1L)).thenReturn(user(1L, "管理员", "admin"));

            List<ReportVO> vos = service.toVOList(List.of(report(1L, 1, 5L, 1, 1)));

            assertEquals("管理员", vos.get(0).getHandleAdminName());
            assertEquals("已处理", vos.get(0).getStatusLabel());
        }

        @Test
        @DisplayName("空列表直接返回空，不查库")
        void emptyList() {
            assertTrue(service.toVOList(List.of()).isEmpty());
            verify(userService, never()).getById(anyLong());
        }

        @Test
        @DisplayName("★ 原因/状态标签对未知值也要有兜底（脏数据不能让接口 500）")
        void labelsFallback() {
            Report weird = report(1L, 1, 5L, null, 99);
            weird.setReasonType(99);
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            ReportVO vo = service.toVOList(List.of(weird)).get(0);

            assertEquals("其他", vo.getReasonLabel());
            assertEquals("待处理", vo.getStatusLabel(), "未知状态归入待处理，便于管理员看到");
        }

        @Test
        @DisplayName("★ reasonType/status 为 null 时也有标签（不能 NPE）")
        void nullLabels() {
            Report r = report(1L, 1, 5L, null, null);
            r.setReasonType(null);
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            ReportVO vo = service.toVOList(List.of(r)).get(0);

            assertEquals("其他", vo.getReasonLabel());
            assertEquals("未知", vo.getStatusLabel());
        }

        @Test
        @DisplayName("三种原因映射正确")
        void reasonLabels() {
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));
            String[] expect = {"虚假信息", "违禁物品", "辱骂骚扰"};
            for (int i = 1; i <= 3; i++) {
                Report r = report(1L, 1, 5L, null, 0);
                r.setReasonType(i);
                assertEquals(expect[i - 1], service.toVOList(List.of(r)).get(0).getReasonLabel());
            }
        }

        @Test
        @DisplayName("忽略的举报标签为「已忽略」")
        void ignoredLabel() {
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            ReportVO vo = service.toVOList(List.of(report(1L, 1, 5L, null, 2))).get(0);

            assertEquals("已忽略", vo.getStatusLabel());
        }

        @Test
        @DisplayName("★ createTime 为 null 时用当前时间兜底（前端时间列不能是空的）")
        void nullCreateTimeFallback() {
            Report r = report(1L, 1, 5L, null, 0);
            r.setCreateTime(null);
            when(userService.getById(UID)).thenReturn(user(UID, "举报人", "stu_demo"));

            assertNotNull(service.toVOList(List.of(r)).get(0).getCreateTime());
        }
    }
}
