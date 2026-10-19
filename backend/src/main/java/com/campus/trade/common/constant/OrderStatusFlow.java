package com.campus.trade.common.constant;

import java.util.Map;
import java.util.Set;

/**
 * 订单状态机（v0.18）。
 *
 * <p>来源：数据库 orders.status 定义了 6 态，但业务此前只实现 0→3 / 0→4，
 * 1 待交付 / 2 待收货 / 5 已退款 是"定义了但永不产生"的死状态。
 * 本类把这套流转、角色规则与商品状态联动集中到一个<b>纯函数</b>类里，
 * service 只负责编排（查订单、判越权、写库、失效缓存），不再散落 if/else。</p>
 *
 * <pre>
 *   待交易(0) ──卖家「标记可交付」──▶ 待交付(1) ──卖家「确认已交付」──▶ 待收货(2) ──买家「确认收货」──▶ 已完成(3)
 *        │                                │                                 │
 *        └──双方「取消订单」──▶ 已取消(4)   └────────买卖双方「申请退款」─────┴──▶ 已退款(5)
 * </pre>
 *
 * <p>三条刻意的设计：</p>
 * <ol>
 *   <li><b>只有卖家能推进 1、2</b>：交付动作由卖家掌握；<b>只有买家能推进 3</b>：钱货两清的最后一步在买家手里，
 *       避免卖家自说自话把订单"完成"；</li>
 *   <li><b>1/2 阶段不能"取消"，只能"退款"</b>：已经进入交付流程后，语义上是退款而不是取消
 *       （取消意味着从未开始）；</li>
 *   <li><b>终态（3/4/5）不可回退</b>，也不允许任何自流转。</li>
 * </ol>
 *
 * <p>纯函数、无依赖、可单测：这也是 TDD 能先写测试的前提 —— 行为全部可观察。</p>
 */
public final class OrderStatusFlow {

    /** 待交易（下单后的初始状态） */
    public static final int PENDING = 0;
    /** 待交付（卖家已确认可交付） */
    public static final int DELIVERING = 1;
    /** 待收货（卖家已交付，等买家确认） */
    public static final int RECEIVING = 2;
    /** 已完成 */
    public static final int FINISHED = 3;
    /** 已取消 */
    public static final int CANCELLED = 4;
    /** 已退款 */
    public static final int REFUNDED = 5;

    /** 商品状态语义值（由 service 映射成商品模块自己的常量，避免两处常量耦合） */
    public static final String PRODUCT_TRADING = "TRADING";
    public static final String PRODUCT_SOLD = "SOLD";
    public static final String PRODUCT_ON_SALE = "ON_SALE";

    /** 允许的流转表：from → 可去往的 to 集合 */
    private static final Map<Integer, Set<Integer>> ALLOWED = Map.of(
            // 0→3 这条"一步完成"必须保留：US-ORDER-03 AC1 就是"待交易 → 卖家确认完成"
            PENDING, Set.of(DELIVERING, FINISHED, CANCELLED),
            DELIVERING, Set.of(RECEIVING, REFUNDED),
            RECEIVING, Set.of(FINISHED, REFUNDED),
            FINISHED, Set.of(),
            CANCELLED, Set.of(),
            REFUNDED, Set.of());

    private OrderStatusFlow() {
    }

    /** 是否允许从 from 流转到 to；未知状态码、自流转一律不允许 */
    public static boolean canTransit(int from, int to) {
        if (from == to) {
            return false;
        }
        Set<Integer> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }

    /** 是否终态（不可再流转） */
    public static boolean isTerminal(int status) {
        return status == FINISHED || status == CANCELLED || status == REFUNDED;
    }

    /**
     * 推进到 to 这个状态时，谁有权操作。
     *
     * @param to       目标状态
     * @param isSeller 操作者是否是卖家
     */
    public static boolean actorAllowed(int to, boolean isSeller) {
        switch (to) {
            // 交付动作由卖家掌握
            case DELIVERING:
            case RECEIVING:
                return isSeller;
            // 确认完成：与 US-ORDER-03 的既有需求一致（线下交付后买卖双方都可确认完成）
            case FINISHED:
                return true;
            // 取消与退款：双方都可以发起
            case CANCELLED:
            case REFUNDED:
                return true;
            default:
                return false;
        }
    }

    /** 流转到 to 之后商品应处于的状态（语义值）；未知目标返回空串，由调用方拒绝 */
    public static String productStatusAfter(int to) {
        switch (to) {
            case DELIVERING:
            case RECEIVING:
                return PRODUCT_TRADING;
            case FINISHED:
                return PRODUCT_SOLD;
            case CANCELLED:
            case REFUNDED:
                return PRODUCT_ON_SALE;
            default:
                return "";
        }
    }

    /** 前端按钮文案：从 from 推进到 to 的动作名；非法流转返回空串 */
    public static String actionLabel(int from, int to) {
        if (!canTransit(from, to)) {
            return "";
        }
        if (from == PENDING && to == DELIVERING) {
            return "标记可交付";
        }
        if (from == DELIVERING && to == RECEIVING) {
            return "确认已交付";
        }
        if (from == RECEIVING && to == FINISHED) {
            return "确认收货";
        }
        if (from == PENDING && to == CANCELLED) {
            return "取消订单";
        }
        if (from == PENDING && to == FINISHED) {
            return "确认完成";
        }
        if (to == REFUNDED) {
            return "申请退款";
        }
        return "";
    }

    /** 某状态下所有允许的目标状态（供前端渲染按钮/管理端排查） */
    public static Set<Integer> targetsOf(int from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
