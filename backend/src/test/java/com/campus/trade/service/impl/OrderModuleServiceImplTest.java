package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.cache.CacheEvictor;
import com.campus.trade.common.constant.OrderStatus;
import com.campus.trade.common.constant.ProductStatus;
import com.campus.trade.common.exception.OrderException;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.dto.OrderCreateDTO;
import com.campus.trade.dto.OrderStatusDTO;
import com.campus.trade.entity.Order;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.service.OrderService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserBehaviorService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.OrderDetailVO;
import com.campus.trade.vo.OrderVO;

/**
 * 订单交易状态机验证（v0.17）
 *
 * <p>订单是本站最核心、也最怕出错的链路：一旦"一物多卖"或"越权操作"漏掉，
 * 就是真实的经济纠纷。本测试用 Mockito 把外部依赖（商品、订单、用户、行为埋点、缓存）
 * 全部打桩，专注验证订单模块<b>自己的判断逻辑</b>：</p>
 *
 * <ol>
 *   <li><b>下单前置校验</b>：商品不存在 3001 / 不能买自己的 6002 / 不在售 6001；</li>
 *   <li>★ <b>防一物多卖</b>：靠"条件更新"把商品从在售改为交易中，影响行数为 0 即说明被别人抢先，
 *       此时必须抛异常、<b>不落订单、不清缓存</b>；</li>
 *   <li><b>状态流转</b>：只有待交易可流转；目标状态只能是已完成(3)或已取消(4)；
 *       完成 → 商品已售出 + 双方信用分 +1；取消 → 商品回到在售（且仅当它正处于交易中）；</li>
 *   <li><b>越权防护</b>：非买卖双方一律 6004；</li>
 *   <li><b>副作用不影响主流程</b>：行为埋点失败不能让下单失败。</li>
 * </ol>
 */
class OrderModuleServiceImplTest {

    private OrderService orderService;
    private ProductService productService;
    private UserService userService;
    private UserBehaviorService userBehaviorService;
    private CacheEvictor cacheEvictor;
    private BaseMapper<Product> productMapper;
    private BaseMapper<User> userMapper;

    private OrderModuleServiceImpl service;

    private static final Long BUYER = 100L;
    private static final Long SELLER = 200L;
    private static final Long PRODUCT_ID = 7L;

    /**
     * MyBatis-Plus 的 LambdaUpdateWrapper 在把 {@code Product::getStatus} 解析成列名时，
     * 需要该实体的 TableInfo 元数据。正常由 MyBatis 启动时自动注册；
     * 纯单元测试没有 Spring/MyBatis 环境，因此这里显式初始化，
     * 否则被测代码里的 {@code new LambdaUpdateWrapper<Product>().set(...)} 会直接抛异常。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Order.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Product.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {        orderService = mock(OrderService.class);
        productService = mock(ProductService.class);
        userService = mock(UserService.class);
        userBehaviorService = mock(UserBehaviorService.class);
        cacheEvictor = mock(CacheEvictor.class);
        productMapper = mock(BaseMapper.class);
        userMapper = mock(BaseMapper.class);

        when(productService.getBaseMapper()).thenReturn(productMapper);
        when(userService.getBaseMapper()).thenReturn(userMapper);

        service = new OrderModuleServiceImpl(orderService, productService, userService,
                userBehaviorService, cacheEvictor);
    }

    /* ---------------- 测试夹具 ---------------- */

    private Product onSaleProduct() {
        Product p = new Product();
        p.setId(PRODUCT_ID);
        p.setTitle("九成新《数据结构》教材");
        p.setCoverImage("/upload/a.png");
        p.setPrice(new BigDecimal("25.50"));
        p.setStatus(ProductStatus.ON_SALE.getCode());
        p.setSellerId(SELLER);
        p.setTradePlace("东校区图书馆门口");
        return p;
    }

    private OrderCreateDTO createDTO() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setProductId(PRODUCT_ID);
        return dto;
    }

    private Order pendingOrder() {
        Order o = new Order();
        o.setId(55L);
        o.setOrderNo("202610151200001234");
        o.setProductId(PRODUCT_ID);
        o.setBuyerId(BUYER);
        o.setSellerId(SELLER);
        o.setProductTitle("九成新《数据结构》教材");
        o.setAmount(new BigDecimal("25.50"));
        o.setStatus(OrderStatus.PENDING.getCode());
        return o;
    }

    private OrderStatusDTO statusDTO(Long orderId, int status, String reason) {
        OrderStatusDTO dto = new OrderStatusDTO();
        dto.setOrderId(orderId);
        dto.setStatus(status);
        dto.setCancelReason(reason);
        return dto;
    }

    private List<User> twoUsers() {
        User buyer = new User();
        buyer.setId(BUYER);
        buyer.setNickname("买家同学");
        buyer.setCampus("东校区");
        buyer.setCreditScore(100);
        User seller = new User();
        seller.setId(SELLER);
        seller.setNickname("卖家同学");
        seller.setCampus("西校区");
        seller.setCreditScore(100);
        return List.of(buyer, seller);
    }

    /** 让"条件更新"成功（默认）或返回指定影响行数 */
    private void stubLock(int affectedRows) {
        when(productMapper.update(isNull(), any())).thenReturn(affectedRows);
    }

    @Nested
    @DisplayName("一、下单校验（商品存在 / 不能买自己的 / 必须在售）")
    class CreateValidation {

        @Test
        @DisplayName("商品不存在 → 3001，且不落订单")
        void productNotFound() {
            when(productService.getById(PRODUCT_ID)).thenReturn(null);

            ProductException e = assertThrows(ProductException.class,
                    () -> service.create(createDTO(), BUYER));

            assertEquals(3001, e.getCode());
            verify(orderService, never()).save(any());
        }

        @Test
        @DisplayName("不能购买自己发布的商品 → 6002")
        void cannotBuyOwn() {
            when(productService.getById(PRODUCT_ID)).thenReturn(onSaleProduct());

            OrderException e = assertThrows(OrderException.class,
                    () -> service.create(createDTO(), SELLER));

            assertEquals(6002, e.getCode());
            verify(orderService, never()).save(any());
        }

        @Test
        @DisplayName("商品不在售（如交易中）→ 6001")
        void productNotOnSale() {
            Product p = onSaleProduct();
            p.setStatus(ProductStatus.TRADING.getCode());
            when(productService.getById(PRODUCT_ID)).thenReturn(p);

            OrderException e = assertThrows(OrderException.class,
                    () -> service.create(createDTO(), BUYER));

            assertEquals(6001, e.getCode());
            verify(orderService, never()).save(any());
        }
    }

    @Nested
    @DisplayName("二、★ 防一物多卖：条件更新影响 0 行时必须失败")
    class PreventDoubleSell {

        @Test
        @DisplayName("被其他买家抢先锁定 → 6001，且不落订单、不清缓存")
        void lockFailedMeansSoldOut() {
            when(productService.getById(PRODUCT_ID)).thenReturn(onSaleProduct());
            stubLock(0);   // 条件更新没改到任何行：商品已不是"在售"

            OrderException e = assertThrows(OrderException.class,
                    () -> service.create(createDTO(), BUYER));

            assertEquals(6001, e.getCode());
            verify(orderService, never()).save(any());
            verify(cacheEvictor, never()).productChanged(anyLong());
        }

        @Test
        @DisplayName("锁定成功（影响 1 行）→ 正常落订单")
        void lockSucceeded() {
            when(productService.getById(PRODUCT_ID)).thenReturn(onSaleProduct());
            stubLock(1);
            when(userService.listByIds(anyCollection())).thenReturn(twoUsers());

            OrderDetailVO vo = service.create(createDTO(), BUYER);

            assertNotNull(vo);
            verify(orderService).save(any(Order.class));
        }
    }

    @Nested
    @DisplayName("三、下单成功：快照、订单号、默认值与联动")
    class CreateSuccess {

        private Order captureSavedOrder() {
            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderService).save(captor.capture());
            return captor.getValue();
        }

        @BeforeEach
        void happyPath() {
            when(productService.getById(PRODUCT_ID)).thenReturn(onSaleProduct());
            stubLock(1);
            when(userService.listByIds(anyCollection())).thenReturn(twoUsers());
        }

        @Test
        @DisplayName("保存商品快照（标题/封面/价格），商品改名或删除也不影响历史订单")
        void savesProductSnapshot() {
            service.create(createDTO(), BUYER);
            Order saved = captureSavedOrder();

            assertEquals("九成新《数据结构》教材", saved.getProductTitle());
            assertEquals("/upload/a.png", saved.getProductImage());
            assertEquals(new BigDecimal("25.50"), saved.getAmount());
            assertEquals(PRODUCT_ID, saved.getProductId());
        }

        @Test
        @DisplayName("订单初始状态为「待交易」，买卖双方取自商品与当前用户")
        void initialStatusAndParties() {
            service.create(createDTO(), BUYER);
            Order saved = captureSavedOrder();

            assertEquals(OrderStatus.PENDING.getCode(), saved.getStatus());
            assertEquals(BUYER, saved.getBuyerId());
            assertEquals(SELLER, saved.getSellerId());
            assertEquals(0, saved.getDeleted());
        }

        @Test
        @DisplayName("订单号格式：14 位时间戳 + 4 位随机数（共 18 位数字），且两次不同")
        void orderNoFormat() {
            service.create(createDTO(), BUYER);
            String first = captureSavedOrder().getOrderNo();

            org.mockito.Mockito.clearInvocations(orderService);
            service.create(createDTO(), BUYER);
            String second = captureSavedOrder().getOrderNo();

            assertTrue(first.matches("\\d{18}"), "订单号应为 18 位数字：" + first);
            assertNotEquals(first, second, "两次下单不应生成相同订单号");
        }

        @Test
        @DisplayName("未指定交易地点时取商品默认地点；交付方式缺省为 1")
        void defaultsFromProduct() {
            service.create(createDTO(), BUYER);
            Order saved = captureSavedOrder();

            assertEquals("东校区图书馆门口", saved.getTradePlace());
            assertEquals(1, saved.getDeliveryType());
        }

        @Test
        @DisplayName("显式传入交易地点时以传入值为准")
        void explicitTradePlaceWins() {
            OrderCreateDTO dto = createDTO();
            dto.setTradePlace("西校区食堂门口");
            dto.setDeliveryType(2);
            dto.setBuyerRemark("下午三点后有空");

            service.create(dto, BUYER);
            Order saved = captureSavedOrder();

            assertEquals("西校区食堂门口", saved.getTradePlace());
            assertEquals(2, saved.getDeliveryType());
            assertEquals("下午三点后有空", saved.getBuyerRemark());
        }

        @Test
        @DisplayName("下单后触发商品缓存失效（列表与详情都要刷新）")
        void evictsProductCache() {
            service.create(createDTO(), BUYER);
            verify(cacheEvictor).productChanged(PRODUCT_ID);
        }

        @Test
        @DisplayName("★ 行为埋点失败不影响下单（埋点是旁路，不能拖垮主流程）")
        void behaviorFailureDoesNotBreakOrder() {
            org.mockito.Mockito.doThrow(new RuntimeException("埋点服务不可用"))
                    .when(userBehaviorService).record(anyLong(), anyLong(), anyInt());

            OrderDetailVO vo = service.create(createDTO(), BUYER);

            assertNotNull(vo, "埋点异常不应导致下单失败");
            verify(orderService).save(any(Order.class));
            verify(cacheEvictor).productChanged(PRODUCT_ID);
        }

        @Test
        @DisplayName("详情 VO 带上买卖双方昵称与商品当前状态")
        void detailVoFilled() {
            OrderDetailVO vo = service.create(createDTO(), BUYER);

            assertEquals("买家同学", vo.getBuyerNickname());
            assertEquals("卖家同学", vo.getSellerNickname());
            assertEquals(ProductStatus.ON_SALE.getCode(), vo.getProductStatus());
            assertEquals("在售", vo.getProductStatusLabel());
            assertEquals("buyer", vo.getMyRole());
        }
    }

    @Nested
    @DisplayName("四、状态流转校验（目标状态 / 存在性 / 越权 / 前置状态）")
    class ChangeStatusValidation {

        @BeforeEach
        void existingPendingOrder() {
            when(orderService.getById(55L)).thenReturn(pendingOrder());
        }

        @Test
        @DisplayName("目标状态只能为 3（已完成）或 4（已取消）")
        void onlyFinishOrCancel() {
            for (int bad : new int[]{0, 1, 2, 5, 9}) {
                OrderException e = assertThrows(OrderException.class,
                        () -> service.changeStatus(statusDTO(55L, bad, null), BUYER),
                        "status=" + bad + " 应被拒绝");
                assertEquals(400, e.getCode());
                assertTrue(e.getMessage().contains("3"), "提示应说明合法取值：" + e.getMessage());
            }
            verify(orderService, never()).updateById(any());
        }

        @Test
        @DisplayName("订单不存在 → 6003")
        void orderNotFound() {
            when(orderService.getById(999L)).thenReturn(null);
            OrderException e = assertThrows(OrderException.class,
                    () -> service.changeStatus(statusDTO(999L, 3, null), BUYER));
            assertEquals(6003, e.getCode());
        }

        @Test
        @DisplayName("第三方（既非买家也非卖家）操作 → 6004，且不改任何数据")
        void thirdPartyForbidden() {
            OrderException e = assertThrows(OrderException.class,
                    () -> service.changeStatus(statusDTO(55L, 3, null), 999L));

            assertEquals(6004, e.getCode());
            verify(orderService, never()).updateById(any());
            verify(cacheEvictor, never()).productChanged(anyLong());
        }

        @Test
        @DisplayName("订单已不是「待交易」（如已完成）→ 6005，禁止重复流转")
        void illegalPrecedingStatus() {
            Order finished = pendingOrder();
            finished.setStatus(OrderStatus.FINISHED.getCode());
            when(orderService.getById(55L)).thenReturn(finished);

            OrderException e = assertThrows(OrderException.class,
                    () -> service.changeStatus(statusDTO(55L, 4, null), BUYER));

            assertEquals(6005, e.getCode());
            verify(orderService, never()).updateById(any());
        }
    }

    @Nested
    @DisplayName("五、完成订单：商品已售出 + 双方信用分 +1")
    class FinishOrder {

        @BeforeEach
        void pendingOrderReady() {
            when(orderService.getById(55L)).thenReturn(pendingOrder());
            when(userService.listByIds(anyCollection())).thenReturn(twoUsers());
        }

        @Test
        @DisplayName("订单状态更新为已完成并记录完成时间")
        void orderMarkedFinished() {
            service.changeStatus(statusDTO(55L, 3, null), BUYER);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderService).updateById(captor.capture());
            Order updated = captor.getValue();

            assertEquals(OrderStatus.FINISHED.getCode(), updated.getStatus());
            assertNotNull(updated.getFinishTime(), "完成时间必须记录");
        }

        @Test
        @DisplayName("★ 商品被标记为已售出（status=5）并写入售出时间")
        void productMarkedSold() {
            service.changeStatus(statusDTO(55L, 3, null), SELLER);

            ArgumentCaptor<LambdaUpdateWrapper<Product>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(productMapper).update(isNull(), captor.capture());

            LambdaUpdateWrapper<Product> w = captor.getValue();
            w.getSqlSegment();   // 先取一次 SQL 片段，where 条件里的参数才会进入 paramNameValuePairs
            assertTrue(w.getSqlSet().contains("status"), "应更新商品状态字段");
            assertTrue(w.getParamNameValuePairs().containsValue(ProductStatus.SOLD.getCode()),
                    "商品状态应更新为已售出(5)：" + w.getParamNameValuePairs());
        }

        @Test
        @DisplayName("★ 买卖双方信用分同时 +1（条件作用于两个人）")
        void bothCreditsIncremented() {
            service.changeStatus(statusDTO(55L, 3, null), BUYER);

            ArgumentCaptor<LambdaUpdateWrapper<User>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(userMapper).update(isNull(), captor.capture());

            LambdaUpdateWrapper<User> w = captor.getValue();
            w.getSqlSegment();   // 触发 where 条件物化（in 条件里的买卖双方 id）
            String sqlSet = w.getSqlSet();
            assertTrue(sqlSet != null && sqlSet.contains("credit_score"),
                    "信用分应通过 SQL 自增：" + sqlSet);
            assertTrue(w.getParamNameValuePairs().containsValue(BUYER));
            assertTrue(w.getParamNameValuePairs().containsValue(SELLER));
        }

        @Test
        @DisplayName("完成后同样触发商品缓存失效")
        void evictsCacheAfterFinish() {
            service.changeStatus(statusDTO(55L, 3, null), BUYER);
            verify(cacheEvictor).productChanged(PRODUCT_ID);
        }

        @Test
        @DisplayName("卖家也能确认完成（买卖双方权限对等）")
        void sellerCanFinish() {
            service.changeStatus(statusDTO(55L, 3, null), SELLER);
            verify(orderService).updateById(any(Order.class));
        }
    }

    @Nested
    @DisplayName("六、取消订单：商品回到在售")
    class CancelOrder {

        @BeforeEach
        void pendingOrderReady() {
            when(orderService.getById(55L)).thenReturn(pendingOrder());
            when(userService.listByIds(anyCollection())).thenReturn(twoUsers());
        }

        @Test
        @DisplayName("订单状态更新为已取消并记录取消时间")
        void orderMarkedCancelled() {
            service.changeStatus(statusDTO(55L, 4, "临时有事"), BUYER);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderService).updateById(captor.capture());
            Order updated = captor.getValue();

            assertEquals(OrderStatus.CANCELLED.getCode(), updated.getStatus());
            assertNotNull(updated.getCancelTime());
            assertEquals("临时有事", updated.getCancelReason());
        }

        @Test
        @DisplayName("未填取消原因时给出默认原因，避免历史记录空白")
        void defaultCancelReason() {
            service.changeStatus(statusDTO(55L, 4, "   "), BUYER);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderService).updateById(captor.capture());
            assertEquals("用户取消", captor.getValue().getCancelReason());
        }

        @Test
        @DisplayName("★ 商品回到在售（status=1），且条件是「当前正处于交易中」")
        void productBackOnSale() {
            service.changeStatus(statusDTO(55L, 4, null), BUYER);

            ArgumentCaptor<LambdaUpdateWrapper<Product>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(productMapper).update(isNull(), captor.capture());
            LambdaUpdateWrapper<Product> wrapper = captor.getValue();
            String where = wrapper.getSqlSegment();   // 触发 where 条件物化，参数才会进入 paramNameValuePairs
            assertTrue(where.contains("status"), "更新条件里应包含商品状态判断（防覆盖别人的状态）：" + where);

            assertTrue(wrapper.getParamNameValuePairs().containsValue(ProductStatus.ON_SALE.getCode()),
                    "应把商品改回在售(1)：" + wrapper.getParamNameValuePairs());
            assertTrue(wrapper.getParamNameValuePairs().containsValue(ProductStatus.TRADING.getCode()),
                    "更新条件应限定商品当前为交易中(4)，避免覆盖别人的状态：" + wrapper.getParamNameValuePairs());
        }

        @Test
        @DisplayName("取消不涉及信用分（只有完成才加分）")
        void cancelDoesNotTouchCredit() {
            service.changeStatus(statusDTO(55L, 4, null), BUYER);
            verify(userMapper, never()).update(isNull(), any());
        }

        @Test
        @DisplayName("取消后同样触发商品缓存失效")
        void evictsCacheAfterCancel() {
            service.changeStatus(statusDTO(55L, 4, null), SELLER);
            verify(cacheEvictor).productChanged(PRODUCT_ID);
        }
    }

    @Nested
    @DisplayName("七、订单详情权限与列表")
    class DetailAndList {

        @Test
        @DisplayName("订单不存在 → 6003")
        void detailNotFound() {
            when(orderService.getById(1L)).thenReturn(null);
            assertEquals(6003, assertThrows(OrderException.class,
                    () -> service.detail(1L, BUYER)).getCode());
        }

        @Test
        @DisplayName("非买卖双方查看详情 → 6004")
        void detailForbidden() {
            when(orderService.getById(55L)).thenReturn(pendingOrder());
            assertEquals(6004, assertThrows(OrderException.class,
                    () -> service.detail(55L, 999L)).getCode());
        }

        @Test
        @DisplayName("买家与卖家都能查看详情，且 myRole 正确")
        void bothPartiesCanView() {
            when(orderService.getById(55L)).thenReturn(pendingOrder());
            when(userService.listByIds(anyCollection())).thenReturn(twoUsers());
            when(productService.getById(PRODUCT_ID)).thenReturn(onSaleProduct());

            assertEquals("buyer", service.detail(55L, BUYER).getMyRole());
            assertEquals("seller", service.detail(55L, SELLER).getMyRole());
        }

        @Test
        @DisplayName("商品状态标签覆盖各状态（含未知值兜底）")
        void productStatusLabels() {
            when(orderService.getById(55L)).thenReturn(pendingOrder());
            when(userService.listByIds(anyCollection())).thenReturn(twoUsers());

            for (Object[] pair : new Object[][]{
                    {ProductStatus.PENDING.getCode(), "待审核"},
                    {ProductStatus.ON_SALE.getCode(), "在售"},
                    {ProductStatus.REJECTED.getCode(), "审核不通过"},
                    {ProductStatus.OFF_SHELF.getCode(), "已下架"},
                    {ProductStatus.TRADING.getCode(), "交易中"},
                    {ProductStatus.SOLD.getCode(), "已售出"},
                    {99, "未知(99)"},
            }) {
                Product p = onSaleProduct();
                p.setStatus((Integer) pair[0]);
                when(productService.getById(PRODUCT_ID)).thenReturn(p);
                assertEquals(pair[1], service.detail(55L, BUYER).getProductStatusLabel(),
                        "状态 " + pair[0] + " 的标签应为 " + pair[1]);
            }
        }

        @Test
        @DisplayName("我买到的列表：按买家维度返回，并补齐对方昵称与我的角色")
        void buyList() {
            Page<Order> page = new Page<>(1, 10);
            page.setTotal(1);
            page.setRecords(List.of(pendingOrder()));
            when(orderService.page(any(), any())).thenReturn(page);
            when(userService.listByIds(anyCollection())).thenReturn(twoUsers());

            var result = service.pageBuyList(BUYER, null, 1, 10);

            assertEquals(1L, result.getTotal());
            assertEquals(1, result.getRecords().size());
            OrderVO vo = result.getRecords().get(0);
            assertEquals("buyer", vo.getMyRole());
            assertEquals("卖家同学", vo.getCounterpartNickname());
            assertEquals("待交易", vo.getStatusLabel());
        }

        @Test
        @DisplayName("我卖出的列表：按卖家维度返回，对方是买家")
        void sellList() {
            Page<Order> page = new Page<>(1, 10);
            page.setTotal(1);
            page.setRecords(List.of(pendingOrder()));
            when(orderService.page(any(), any())).thenReturn(page);
            when(userService.listByIds(anyCollection())).thenReturn(twoUsers());

            var result = service.pageSellList(SELLER, OrderStatus.PENDING.getCode(), 1, 10);

            OrderVO vo = result.getRecords().get(0);
            assertEquals("seller", vo.getMyRole());
            assertEquals("买家同学", vo.getCounterpartNickname());
        }

        @Test
        @DisplayName("空列表不查询用户（避免无意义的批量查询）")
        void emptyListSkipsUserLookup() {
            Page<Order> page = new Page<>(1, 10);
            page.setTotal(0);
            page.setRecords(Collections.emptyList());
            when(orderService.page(any(), any())).thenReturn(page);

            var result = service.pageBuyList(BUYER, null, 1, 10);

            assertTrue(result.getRecords().isEmpty());
            verify(userService, never()).listByIds(anyCollection());
        }
    }
}
