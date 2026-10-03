package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.constant.OrderStatus;
import com.campus.trade.common.exception.OrderException;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.OrderCreateDTO;
import com.campus.trade.dto.OrderStatusDTO;
import com.campus.trade.entity.Order;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.service.OrderModuleService;
import com.campus.trade.service.OrderService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserBehaviorService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.OrderDetailVO;
import com.campus.trade.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单交易业务实现（v0.08）。
 *
 * <p>关键点：</p>
 * <ol>
 *   <li>下单前校验商品存在、不是自己的商品、仍在售（3001 / 6002 / 6001）；</li>
 *   <li>用<b>条件更新</b>把商品从"在售"改成"交易中"（UPDATE ... WHERE status = 1），
 *       并发下只有一个买家能成功，天然防止"一物多卖"；</li>
 *   <li>订单保存商品标题/封面/价格快照，之后商品被改名或删除也不影响历史订单；</li>
 *   <li>取消 → 商品回到在售；完成 → 商品变为已售出，买卖双方信用分 +1；</li>
 *   <li>只有买卖双方能查看/操作订单，用 buyerId / sellerId 判断，杜绝越权。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderModuleServiceImpl implements OrderModuleService {

    /** 商品状态：在售 */
    private static final int PRODUCT_ON_SALE = 1;
    /** 商品状态：交易中 */
    private static final int PRODUCT_TRADING = 4;
    /** 商品状态：已售出 */
    private static final int PRODUCT_SOLD = 5;

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderService orderService;
    private final ProductService productService;
    private final UserService userService;
    /** v0.11 推荐模块：行为埋点 */
    private final UserBehaviorService userBehaviorService;

    /* ==================== 1. 创建订单 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderDetailVO create(OrderCreateDTO dto, Long buyerId) {
        // ① 商品必须存在
        Product product = productService.getById(dto.getProductId());
        if (product == null) {
            throw ProductException.notFound();
        }
        // ② 不能购买自己发布的商品
        if (Objects.equals(product.getSellerId(), buyerId)) {
            log.warn("[下单失败] 不能购买自己的商品 buyerId={} productId={}", buyerId, product.getId());
            throw OrderException.cannotBuyOwn();
        }
        // ③ 商品必须处于在售状态
        if (!Integer.valueOf(PRODUCT_ON_SALE).equals(product.getStatus())) {
            throw OrderException.productNotOnSale();
        }

        // ④ 条件更新锁定商品：只有仍是"在售"才能改成"交易中"，防一物多卖
        int locked = productService.getBaseMapper().update(null, new LambdaUpdateWrapper<Product>()
                .set(Product::getStatus, PRODUCT_TRADING)
                .eq(Product::getId, product.getId())
                .eq(Product::getStatus, PRODUCT_ON_SALE));
        if (locked == 0) {
            log.warn("[下单失败] 商品已被其他买家锁定 productId={}", product.getId());
            throw OrderException.productNotOnSale();
        }

        // ⑤ 生成订单（保存商品快照）
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setProductId(product.getId());
        order.setBuyerId(buyerId);
        order.setSellerId(product.getSellerId());
        order.setProductTitle(product.getTitle());
        order.setProductImage(product.getCoverImage());
        order.setAmount(product.getPrice());
        order.setStatus(OrderStatus.PENDING.getCode());
        order.setDeliveryType(dto.getDeliveryType() == null ? 1 : dto.getDeliveryType());
        order.setTradePlace(StringUtils.hasText(dto.getTradePlace()) ? dto.getTradePlace() : product.getTradePlace());
        order.setBuyerRemark(dto.getBuyerRemark());
        order.setDeleted(0);
        orderService.save(order);

        log.info("[下单成功] orderNo={} productId={} buyerId={} sellerId={} amount={}",
                order.getOrderNo(), product.getId(), buyerId, product.getSellerId(), product.getPrice());
        // v0.11 行为埋点：下单是最强兴趣信号（失败不影响主流程）
        try {
            userBehaviorService.record(buyerId, product.getId(), UserBehaviorService.TYPE_ORDER);
        } catch (Exception e) {
            log.warn("[行为埋点失败] 下单 userId={} productId={} 原因={}", buyerId, product.getId(), e.getMessage());
        }
        return toDetailVO(order, buyerId);
    }

    /* ==================== 2. 修改订单状态 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderDetailVO changeStatus(OrderStatusDTO dto, Long userId) {
        Integer target = dto.getStatus();
        // ① 目标状态只能是 已完成(3) / 已取消(4)
        boolean toFinish = OrderStatus.FINISHED.getCode().equals(target);
        boolean toCancel = OrderStatus.CANCELLED.getCode().equals(target);
        if (!toFinish && !toCancel) {
            throw OrderException.paramIllegal("status 只能为 3（已完成）或 4（已取消）；待交易(0) 是下单后的初始状态");
        }

        // ② 订单必须存在
        Order order = orderService.getById(dto.getOrderId());
        if (order == null) {
            throw OrderException.notFound();
        }
        // ③ 只有买卖双方能操作
        if (!Objects.equals(order.getBuyerId(), userId) && !Objects.equals(order.getSellerId(), userId)) {
            log.warn("[订单操作失败] 越权 orderId={} userId={}", order.getId(), userId);
            throw OrderException.noPermission();
        }
        // ④ 只有"待交易"的订单可以流转
        if (!OrderStatus.PENDING.getCode().equals(order.getStatus())) {
            throw OrderException.statusIllegal();
        }

        LocalDateTime now = LocalDateTime.now();
        Order update = new Order();
        update.setId(order.getId());
        update.setStatus(target);

        if (toFinish) {
            // 完成：记录完成时间 + 商品标记已售出 + 双方信用分 +1
            update.setFinishTime(now);
            orderService.updateById(update);

            productService.getBaseMapper().update(null, new LambdaUpdateWrapper<Product>()
                    .set(Product::getStatus, PRODUCT_SOLD)
                    .set(Product::getSoldTime, now)
                    .eq(Product::getId, order.getProductId()));

            userService.getBaseMapper().update(null, new LambdaUpdateWrapper<User>()
                    .setSql("credit_score = credit_score + 1")
                    .in(User::getId, List.of(order.getBuyerId(), order.getSellerId())));
            log.info("[订单完成] orderNo={} 买卖双方信用分 +1", order.getOrderNo());
        } else {
            // 取消：记录取消时间与原因 + 商品回到在售
            update.setCancelTime(now);
            update.setCancelReason(StringUtils.hasText(dto.getCancelReason()) ? dto.getCancelReason() : "用户取消");
            orderService.updateById(update);

            productService.getBaseMapper().update(null, new LambdaUpdateWrapper<Product>()
                    .set(Product::getStatus, PRODUCT_ON_SALE)
                    .eq(Product::getId, order.getProductId())
                    .eq(Product::getStatus, PRODUCT_TRADING));
            log.info("[订单取消] orderNo={} 原因={}", order.getOrderNo(), update.getCancelReason());
        }

        return toDetailVO(orderService.getById(order.getId()), userId);
    }

    /* ==================== 3. 订单列表 ==================== */

    @Override
    public PageResult<OrderVO> pageBuyList(Long buyerId, Integer status, long page, long size) {
        return pageOrders(buyerId, true, status, page, size);
    }

    @Override
    public PageResult<OrderVO> pageSellList(Long sellerId, Integer status, long page, long size) {
        return pageOrders(sellerId, false, status, page, size);
    }

    /* ==================== 4. 订单详情 ==================== */

    @Override
    public OrderDetailVO detail(Long orderId, Long userId) {
        Order order = orderService.getById(orderId);
        if (order == null) {
            throw OrderException.notFound();
        }
        // 权限校验：仅买卖双方可查看
        if (!Objects.equals(order.getBuyerId(), userId) && !Objects.equals(order.getSellerId(), userId)) {
            log.warn("[订单详情] 越权访问 orderId={} userId={}", orderId, userId);
            throw OrderException.noPermission();
        }
        return toDetailVO(order, userId);
    }

    /* ==================== 内部方法 ==================== */

    /** 下单号：yyyyMMddHHmmss + 4 位随机数 */
    private String generateOrderNo() {
        return LocalDateTime.now().format(ORDER_NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private PageResult<OrderVO> pageOrders(Long userId, boolean asBuyer, Integer status, long page, long size) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        if (asBuyer) {
            wrapper.eq(Order::getBuyerId, userId);
        } else {
            wrapper.eq(Order::getSellerId, userId);
        }
        wrapper.eq(status != null, Order::getStatus, status);
        wrapper.orderByDesc(Order::getCreateTime).orderByDesc(Order::getId);

        Page<Order> result = orderService.page(new Page<>(page, size), wrapper);
        return new PageResult<>(result.getTotal(), result.getPages(), result.getCurrent(), result.getSize(),
                toVOList(result.getRecords(), userId));
    }

    /** 列表 VO（批量补齐买卖双方昵称，避免 N+1 查询） */
    private List<OrderVO> toVOList(List<Order> orders, Long userId) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> userIds = orders.stream()
                .flatMap(o -> java.util.stream.Stream.of(o.getBuyerId(), o.getSellerId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        return orders.stream().map(order -> {
            OrderVO vo = new OrderVO();
            BeanUtils.copyProperties(order, vo);
            fillCommon(vo, order, userMap, userId);
            return vo;
        }).collect(Collectors.toList());
    }

    /** 详情 VO */
    private OrderDetailVO toDetailVO(Order order, Long userId) {
        OrderDetailVO vo = new OrderDetailVO();
        BeanUtils.copyProperties(order, vo);

        Map<Long, User> userMap = userService.listByIds(
                        List.of(order.getBuyerId(), order.getSellerId())).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        fillCommon(vo, order, userMap, userId);

        User buyer = userMap.get(order.getBuyerId());
        if (buyer != null) {
            vo.setBuyerNickname(buyer.getNickname());
            vo.setBuyerAvatar(buyer.getAvatar());
            vo.setBuyerCampus(buyer.getCampus());
            vo.setBuyerCreditScore(buyer.getCreditScore());
        }
        User seller = userMap.get(order.getSellerId());
        if (seller != null) {
            vo.setSellerNickname(seller.getNickname());
            vo.setSellerAvatar(seller.getAvatar());
            vo.setSellerCampus(seller.getCampus());
            vo.setSellerCreditScore(seller.getCreditScore());
        }

        // 商品当前状态（快照之外还想知道它现在是在售/交易中/已售出）
        Product product = productService.getById(order.getProductId());
        if (product != null) {
            vo.setProductStatus(product.getStatus());
            vo.setProductStatusLabel(productStatusLabel(product.getStatus()));
        }

        vo.setOperable(OrderStatus.PENDING.getCode().equals(order.getStatus()));
        return vo;
    }

    /** 填充列表与详情共用的字段 */
    private void fillCommon(OrderVO vo, Order order, Map<Long, User> userMap, Long userId) {
        vo.setStatusLabel(OrderStatus.labelOf(order.getStatus()));
        vo.setMyRole(Objects.equals(order.getBuyerId(), userId) ? "buyer" : "seller");

        User buyer = userMap.get(order.getBuyerId());
        User seller = userMap.get(order.getSellerId());
        if (buyer != null) {
            vo.setBuyerNickname(buyer.getNickname());
        }
        if (seller != null) {
            vo.setSellerNickname(seller.getNickname());
        }
        boolean isBuyer = Objects.equals(order.getBuyerId(), userId);
        vo.setCounterpartNickname(isBuyer
                ? (seller == null ? null : seller.getNickname())
                : (buyer == null ? null : buyer.getNickname()));
    }

    /** 商品状态中文名 */
    private String productStatusLabel(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待审核";
            case 1 -> "在售";
            case 2 -> "审核不通过";
            case 3 -> "已下架";
            case 4 -> "交易中";
            case 5 -> "已售出";
            default -> "未知(" + status + ")";
        };
    }
}
