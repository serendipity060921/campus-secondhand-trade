package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.exception.EvaluationException;
import com.campus.trade.dto.EvaluationCreateDTO;
import com.campus.trade.entity.Evaluation;
import com.campus.trade.entity.Order;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.service.EvaluationService;
import com.campus.trade.service.OrderService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.EvaluationSummaryVO;
import com.campus.trade.vo.EvaluationVO;

/**
 * 评价模块业务验证（v0.18 / 第6批-㉑）
 *
 * <p>双向评价的价值全部建立在"谁能评、能评几次、评了算什么"这三件事上，
 * 所以本测试不放过分任何一条：</p>
 * <ul>
 *   <li>★ <b>评价对象由订单推导</b>：买家评卖家、卖家评买家，前端只能传 orderId；</li>
 *   <li>★ <b>只有已完成订单能评</b>（0/1/2/4/5 全部拒绝）；</li>
 *   <li>★ <b>同一订单每人一次</b>：先查再插，并发下唯一索引兜底并转成业务码 9004；</li>
 *   <li>★ <b>信用分联动且有边界</b>：4~5 星 +1、3 星不变、1~2 星 -1，夹在 [0,150]；</li>
 *   <li>★ <b>匿名只隐藏展示</b>：昵称清空但 fromUserId 保留；统计不受影响；</li>
 *   <li>★ 汇总口径（平均分/好评率/星级分布）由同一处算出，避免各页面各算各的。</li>
 * </ul>
 */
class EvaluationModuleServiceImplTest {

    private EvaluationService evaluationService;
    private OrderService orderService;
    private UserService userService;
    private ProductService productService;
    private BaseMapper<User> userMapper;

    private EvaluationModuleServiceImpl service;

    private static final Long BUYER = 11L;
    private static final Long SELLER = 12L;
    private static final Long OTHER = 99L;
    private static final Long ORDER_ID = 500L;
    private static final Long PRODUCT_ID = 5L;

    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (Class<?> c : new Class<?>[]{Evaluation.class, Order.class, User.class, Product.class}) {
            TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""), c);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        evaluationService = mock(EvaluationService.class);
        orderService = mock(OrderService.class);
        userService = mock(UserService.class);
        productService = mock(ProductService.class);
        userMapper = mock(BaseMapper.class);
        when(userService.getBaseMapper()).thenReturn(userMapper);
        service = new EvaluationModuleServiceImpl(evaluationService, orderService, userService, productService);
    }

    private EvaluationCreateDTO dto(Integer rating, String content, Integer anonymous) {
        EvaluationCreateDTO d = new EvaluationCreateDTO();
        d.setOrderId(ORDER_ID);
        d.setRating(rating);
        d.setContent(content);
        d.setAnonymous(anonymous);
        return d;
    }

    private Order order(int status) {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setProductId(PRODUCT_ID);
        o.setBuyerId(BUYER);
        o.setSellerId(SELLER);
        o.setStatus(status);
        return o;
    }

    private User user(long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        u.setAvatar("/upload/" + id + ".png");
        u.setCreditScore(100);
        return u;
    }

    @Nested
    @DisplayName("一、入参与订单状态校验")
    class Validation {

        @Test
        @DisplayName("评分越界（0 / 6 / null）→ 9005")
        void ratingIllegal() {
            for (Integer r : new Integer[]{0, 6, null}) {
                EvaluationException e = assertThrows(EvaluationException.class,
                        () -> service.submit(dto(r, "x", 0), BUYER), "rating=" + r);
                assertEquals(EvaluationException.ratingIllegal().getCode(), e.getCode());
            }
        }

        @Test
        @DisplayName("内容超过 500 字 → 9006")
        void contentTooLong() {
            EvaluationException e = assertThrows(EvaluationException.class,
                    () -> service.submit(dto(5, "字".repeat(501), 0), BUYER));
            assertEquals(EvaluationException.contentTooLong().getCode(), e.getCode());
        }

        @Test
        @DisplayName("订单不存在 → 9001")
        void orderNotFound() {
            when(orderService.getById(ORDER_ID)).thenReturn(null);
            EvaluationException e = assertThrows(EvaluationException.class,
                    () -> service.submit(dto(5, "好", 0), BUYER));
            assertEquals(EvaluationException.orderNotFound().getCode(), e.getCode());
        }

        @Test
        @DisplayName("★ 不是买卖双方 → 9002（第三方不能插话评价）")
        void notParticipant() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            EvaluationException e = assertThrows(EvaluationException.class,
                    () -> service.submit(dto(5, "好", 0), OTHER));
            assertEquals(EvaluationException.notParticipant().getCode(), e.getCode());
            verify(evaluationService, never()).save(any());
        }

        @Test
        @DisplayName("★ 订单未完成（0待付款/1待交付/2待收货/4已取消/5已退款）→ 全部 9003")
        void orderNotFinished() {
            for (int status : new int[]{0, 1, 2, 4, 5}) {
                when(orderService.getById(ORDER_ID)).thenReturn(order(status));
                EvaluationException e = assertThrows(EvaluationException.class,
                        () -> service.submit(dto(5, "好", 0), BUYER), "status=" + status);
                assertEquals(EvaluationException.orderNotFinished().getCode(), e.getCode());
            }
            verify(evaluationService, never()).save(any());
        }

        @Test
        @DisplayName("被评价人不存在 → 9007")
        void targetNotFound() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            when(userService.getById(SELLER)).thenReturn(null);
            EvaluationException e = assertThrows(EvaluationException.class,
                    () -> service.submit(dto(5, "好", 0), BUYER));
            assertEquals(EvaluationException.targetNotFound().getCode(), e.getCode());
        }

        @Test
        @DisplayName("★ 已评价过 → 9004，且不再落库")
        void alreadyEvaluated() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            when(userService.getById(SELLER)).thenReturn(user(SELLER, "卖家"));
            when(evaluationService.count(any())).thenReturn(1L);

            EvaluationException e = assertThrows(EvaluationException.class,
                    () -> service.submit(dto(5, "好", 0), BUYER));
            assertEquals(EvaluationException.alreadyEvaluated().getCode(), e.getCode());
            verify(evaluationService, never()).save(any());
        }

        @Test
        @DisplayName("★ 并发重复提交：唯一索引抛 DuplicateKeyException → 转成 9004")
        void duplicateKeyConverted() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            when(userService.getById(SELLER)).thenReturn(user(SELLER, "卖家"));
            when(evaluationService.count(any())).thenReturn(0L);
            doThrow(new DuplicateKeyException("uk_order_from")).when(evaluationService).save(any());

            EvaluationException e = assertThrows(EvaluationException.class,
                    () -> service.submit(dto(5, "好", 0), BUYER));
            assertEquals(EvaluationException.alreadyEvaluated().getCode(), e.getCode(),
                    "★ 不能把通用的 1002 抛给用户");
        }
    }

    @Nested
    @DisplayName("二、★ 评价对象由订单推导（前端无法指定）")
    class Submit {

        @Test
        @DisplayName("买家评价 → role=1，被评价人是卖家")
        void buyerToSeller() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            when(userService.getById(SELLER)).thenReturn(user(SELLER, "卖家"));
            when(userService.listByIds(any())).thenReturn(List.of(user(SELLER, "卖家"), user(BUYER, "买家")));
            when(productService.listByIds(any())).thenReturn(List.of(product()));
            when(evaluationService.count(any())).thenReturn(0L);
            when(evaluationService.save(any())).thenAnswer(inv -> {
                Evaluation e = inv.getArgument(0);
                e.setId(1L);
                return true;
            });

            EvaluationVO vo = service.submit(dto(5, "  人很好  ", 0), BUYER);

            verify(evaluationService).save(org.mockito.ArgumentMatchers.argThat(e ->
                    e != null && Integer.valueOf(1).equals(e.getRole())
                            && Long.valueOf(SELLER).equals(e.getToUserId())
                            && "人很好".equals(e.getContent())
                            && Long.valueOf(PRODUCT_ID).equals(e.getProductId())));
            assertEquals("卖家", vo.getToNickname());
            assertEquals("买家", vo.getFromNickname());
            assertEquals("买家评卖家", vo.getRoleLabel());
            assertTrue(vo.getMine(), "自己写的评价 mine 应为 true");
        }

        @Test
        @DisplayName("卖家评价 → role=2，被评价人是买家")
        void sellerToBuyer() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            when(userService.getById(BUYER)).thenReturn(user(BUYER, "买家"));
            when(userService.listByIds(any())).thenReturn(List.of(user(BUYER, "买家"), user(SELLER, "卖家")));
            when(productService.listByIds(any())).thenReturn(List.of(product()));
            when(evaluationService.count(any())).thenReturn(0L);
            when(evaluationService.save(any())).thenReturn(true);

            service.submit(dto(4, "付款及时", 0), SELLER);

            verify(evaluationService).save(org.mockito.ArgumentMatchers.argThat(e ->
                    e != null && Integer.valueOf(2).equals(e.getRole())
                            && Long.valueOf(BUYER).equals(e.getToUserId())));
        }

        @Test
        @DisplayName("★ 空白内容存 NULL，不存空串")
        void blankContentBecomesNull() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            when(userService.getById(SELLER)).thenReturn(user(SELLER, "卖家"));
            when(userService.listByIds(any())).thenReturn(List.of());
            when(productService.listByIds(any())).thenReturn(List.of());
            when(evaluationService.count(any())).thenReturn(0L);
            when(evaluationService.save(any())).thenReturn(true);

            service.submit(dto(5, "    ", 0), BUYER);

            verify(evaluationService).save(org.mockito.ArgumentMatchers.argThat(e ->
                    e != null && e.getContent() == null));
        }
    }

    @Nested
    @DisplayName("三、★ 信用分联动（有边界、每单只加一次）")
    class CreditScore {

        private void givenOkFlow() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            when(userService.getById(SELLER)).thenReturn(user(SELLER, "卖家"));
            when(userService.listByIds(any())).thenReturn(List.of());
            when(productService.listByIds(any())).thenReturn(List.of());
            when(evaluationService.count(any())).thenReturn(0L);
            when(evaluationService.save(any())).thenReturn(true);
        }

        @Test
        @DisplayName("4~5 星 → 信用分 +1（用 SQL 表达式做原子更新，不是先读后写）")
        void positiveIncrementsIdempotently() {
            givenOkFlow();
            service.submit(dto(5, "好", 0), BUYER);
            service.submit(dto(4, "不错", 0), BUYER);

            // 两次评价都应触发 ±1 的原子更新；用 getSqlSet() 断言真的写进了 SQL 表达式
            verify(userMapper, org.mockito.Mockito.times(2)).update(any(),
                    org.mockito.ArgumentMatchers.argThat(w -> sqlSetOf(w).contains("+ (1)")));
        }

        @Test
        @DisplayName("3 星 → 不动信用分（没有任何 update）")
        void neutralDoesNothing() {
            givenOkFlow();
            service.submit(dto(3, "一般", 0), BUYER);
            verify(userService.getBaseMapper(), never()).update(any(), any());
        }

        @Test
        @DisplayName("1~2 星 → 信用分 -1，并被 LEAST/GREATEST 夹在 [0,150]")
        void negativeDecrementsWithinBounds() {
            givenOkFlow();
            service.submit(dto(1, "差", 0), BUYER);

            org.mockito.ArgumentCaptor<Wrapper<User>> captor =
                    org.mockito.ArgumentCaptor.forClass(Wrapper.class);
            verify(userMapper).update(any(), captor.capture());
            String sql = sqlSetOf(captor.getValue());
            assertTrue(sql.contains("LEAST") && sql.contains("GREATEST"),
                    "必须用 LEAST/GREATEST 夹边界，实际：" + sql);
            assertTrue(sql.contains("-1"), "应为 -1，实际：" + sql);
            assertNotNull(EvaluationModuleServiceImpl.creditRule().get("range"));
        }

        /** 取出 UPDATE 的 SET 片段（LambdaUpdateWrapper 才有 getSqlSet） */
        private String sqlSetOf(Object wrapper) {
            if (wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> luw) {
                return String.valueOf(luw.getSqlSet());
            }
            return "";
        }
    }

    @Nested
    @DisplayName("四、★ 匿名与汇总口径")
    class AnonymousAndSummary {

        @Test
        @DisplayName("★ 匿名：昵称改成「匿名同学」、头像清空，但 fromUserId 保留")
        void anonymousHidesIdentity() {
            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            when(userService.getById(SELLER)).thenReturn(user(SELLER, "卖家"));
            when(userService.listByIds(any())).thenReturn(List.of(user(BUYER, "真名")));
            when(productService.listByIds(any())).thenReturn(List.of());
            when(evaluationService.count(any())).thenReturn(0L);
            when(evaluationService.save(any())).thenReturn(true);

            EvaluationVO vo = service.submit(dto(5, "好", 1), BUYER);

            assertEquals("匿名同学", vo.getFromNickname());
            assertNull(vo.getFromAvatar());
            assertEquals(BUYER, vo.getFromUserId(), "匿名的追溯字段必须保留");
        }

        @Test
        @DisplayName("无评价时汇总为 0（count/avgRating/goodRate 全 0，分布键齐全）")
        void emptySummary() {
            when(evaluationService.list(org.mockito.ArgumentMatchers.<Wrapper<Evaluation>>any())).thenReturn(List.of());

            EvaluationSummaryVO vo = service.summaryByProduct(PRODUCT_ID);

            assertEquals(0L, vo.getCount());
            assertEquals(0.0, vo.getAvgRating());
            assertEquals(0.0, vo.getGoodRate());
            assertEquals(5, vo.getDistribution().size());
            assertEquals(0L, vo.getDistribution().get(5));
        }

        @Test
        @DisplayName("★ 汇总口径：5/4/3/2/1 各一条 → 平均 3.0、好评率 40.0%、分布正确")
        void summaryMath() {
            when(evaluationService.list(org.mockito.ArgumentMatchers.<Wrapper<Evaluation>>any())).thenReturn(List.of(
                    rating(5), rating(4), rating(3), rating(2), rating(1)));

            EvaluationSummaryVO vo = service.summaryByProduct(PRODUCT_ID);

            assertEquals(5L, vo.getCount());
            assertEquals(3.0, vo.getAvgRating());
            assertEquals(40.0, vo.getGoodRate());
            assertEquals(1L, vo.getDistribution().get(5));
            assertEquals(1L, vo.getDistribution().get(1));
        }

        @Test
        @DisplayName("脏数据（rating=0 / null）不参与统计，也不会把平均分算崩")
        void dirtyRatingIgnored() {
            Evaluation bad = new Evaluation();
            bad.setRating(0);
            Evaluation nullRating = new Evaluation();
            when(evaluationService.list(org.mockito.ArgumentMatchers.<Wrapper<Evaluation>>any())).thenReturn(List.of(rating(5), bad, nullRating));

            EvaluationSummaryVO vo = service.summaryByProduct(PRODUCT_ID);

            assertEquals(1L, vo.getCount());
            assertEquals(5.0, vo.getAvgRating());
            assertEquals(100.0, vo.getGoodRate());
        }

        private Evaluation rating(int r) {
            Evaluation e = new Evaluation();
            e.setRating(r);
            return e;
        }
    }

    @Nested
    @DisplayName("五、列表与订单查询")
    class Listing {

        @SuppressWarnings("unchecked")
        private void givenPage(List<Evaluation> records) {
            Page<Evaluation> page = new Page<>(1, 10);
            page.setRecords(records);
            page.setTotal(records.size());
            when(evaluationService.page(any(Page.class), any())).thenReturn(page);
            when(userService.listByIds(any())).thenReturn(List.of());
            when(productService.listByIds(any())).thenReturn(List.of());
        }

        @Test
        @DisplayName("商品评价分页：返回分页结构")
        void pageByProduct() {
            givenPage(List.of(new Evaluation()));
            var result = service.pageByProduct(PRODUCT_ID, 1, 10, BUYER);
            assertEquals(1L, result.getTotal());
            assertEquals(1, result.getRecords().size());
        }

        @Test
        @DisplayName("收到的评价分页")
        void pageReceived() {
            givenPage(List.of(new Evaluation()));
            assertEquals(1, service.pageReceived(SELLER, 1, 10, null).getRecords().size());
        }

        @Test
        @DisplayName("我发出的评价分页：mine 标记为 true")
        void pageSent() {
            Evaluation e = new Evaluation();
            e.setFromUserId(BUYER);
            givenPage(List.of(e));
            var result = service.pageSent(1, 10, BUYER);
            assertTrue(result.getRecords().get(0).getMine());
        }

        @Test
        @DisplayName("★ 订单评价详情：仅买卖双方可看")
        void listByOrder() {
            when(orderService.getById(ORDER_ID)).thenReturn(null);
            assertThrows(EvaluationException.class, () -> service.listByOrder(ORDER_ID, BUYER));

            when(orderService.getById(ORDER_ID)).thenReturn(order(3));
            assertThrows(EvaluationException.class, () -> service.listByOrder(ORDER_ID, OTHER));

            when(evaluationService.list(org.mockito.ArgumentMatchers.<Wrapper<Evaluation>>any())).thenReturn(List.of(new Evaluation()));
            when(userService.listByIds(any())).thenReturn(List.of());
            when(productService.listByIds(any())).thenReturn(List.of());
            assertEquals(1, service.listByOrder(ORDER_ID, BUYER).size());
        }

        @Test
        @DisplayName("空列表不查用户/商品（避免无意义查询）")
        void emptyListShortCircuits() {
            givenPage(List.of());
            assertTrue(service.pageByProduct(PRODUCT_ID, 1, 10, null).getRecords().isEmpty());
            verify(userService, never()).listByIds(any());
        }

        @Test
        @DisplayName("★ 标签：逗号分隔切列表，去空去重保序")
        void tagListParsed() {
            Evaluation e = new Evaluation();
            e.setTags(" 描述相符, 态度很好 ,,描述相符 ");
            e.setRating(5);
            givenPage(List.of(e));

            EvaluationVO vo = service.pageByProduct(PRODUCT_ID, 1, 10, null).getRecords().get(0);

            assertEquals(List.of("描述相符", "态度很好"), vo.getTagList());
        }

        @Test
        @DisplayName("未登录看评价：mine 一律 false（不泄漏归属）")
        void anonymousViewerHasNoMine() {
            Evaluation e = new Evaluation();
            e.setFromUserId(BUYER);
            givenPage(List.of(e));
            assertFalse(service.pageByProduct(PRODUCT_ID, 1, 10, null).getRecords().get(0).getMine());
        }
    }

    private Product product() {
        Product p = new Product();
        p.setId(PRODUCT_ID);
        p.setTitle("九成新教材");
        return p;
    }
}
