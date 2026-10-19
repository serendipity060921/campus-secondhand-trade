package com.campus.trade.common.constant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 订单状态机验证（v0.18）—— ★ TDD：本文件先于 OrderStatusFlow 的实现编写。
 *
 * <p>背景：数据库里 orders.status 定义了 6 态（0 待付款/待交易、1 待交付、2 待收货、
 * 3 已完成、4 已取消、5 已退款），但此前业务只实现 0→3 / 0→4，
 * 1/2/5 是"定义了但永不产生"的死状态 —— 答辩稿里承诺的"订单四态"就指这件事。</p>
 *
 * <p>目标状态机（买卖双方在线下见面前后的真实节奏）：</p>
 * <pre>
 *   待交易(0) ──卖家「标记可交付」──▶ 待交付(1) ──卖家「已交付」──▶ 待收货(2) ──买家「确认收货」──▶ 已完成(3)
 *        │                              │                          │
 *        └──双方「取消订单」──▶ 已取消(4)  └────双方「退款」────┴──▶ 已退款(5)
 *
 *   商品状态联动：0→1、1→2 保持「交易中」；2→3 变「已售出」；4、5 回到「在售」
 * </pre>
 *
 * <p>谁有权操作（防止越权推进别人的订单）：</p>
 * <ul>
 *   <li>1 待交付、2 待收货：<b>只有卖家</b>能推进（他掌握交付动作）</li>
 *   <li>3 已完成：<b>只有买家</b>能确认收货（钱货两清的最后一步在买家手里）</li>
 *   <li>4 取消、5 退款：买卖双方都可以发起</li>
 * </ul>
 */
class OrderStatusFlowTest {

    private static final int PENDING = 0;
    private static final int DELIVERING = 1;
    private static final int RECEIVING = 2;
    private static final int FINISHED = 3;
    private static final int CANCELLED = 4;
    private static final int REFUNDED = 5;

    @BeforeAll
    static void banner() {
        System.out.println("[TDD] OrderStatusFlowTest：红阶段运行（实现应尚未完成）");
    }

    @Nested
    @DisplayName("一、允许的流转")
    class Allowed {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "0, 1",   // 待交易 → 待交付（卖家标记可交付）
                "0, 3",   // 待交易 → 已完成（US-ORDER-03 AC1：线下当面交易一步完成）
                "0, 4",   // 待交易 → 已取消
                "1, 2",   // 待交付 → 待收货（卖家已交付）
                "1, 5",   // 待交付 → 已退款
                "2, 3",   // 待收货 → 已完成（买家确认收货）
                "2, 5",   // 待收货 → 已退款
        })
        @DisplayName("合法流转必须被允许")
        void allowedTransitions(int from, int to) {
            assertTrue(OrderStatusFlow.canTransit(from, to),
                    "应允许 " + from + " → " + to);
        }
    }

    @Nested
    @DisplayName("二、禁止的流转")
    class Denied {

        @ParameterizedTest(name = "{0} → {1} 应被拒绝")
        @CsvSource({
                "0, 2",   // 不能跳过交付
                "0, 5",   // 未开始时谈不上退款
                "1, 3",   // 不能跳过收货
                "1, 4",   // 已进入交付阶段应走退款而非取消
                "2, 4",   // 同上
                "2, 1",   // 不能回退
                "3, 0",   // 终态不能改动
                "4, 3",   // 已取消不能复活
                "5, 3",   // 已退款不能复活
                "1, 1",   // 自流转无意义
        })
        @DisplayName("非法流转必须被拒绝")
        void deniedTransitions(int from, int to) {
            assertFalse(OrderStatusFlow.canTransit(from, to),
                    "应拒绝 " + from + " → " + to);
        }

        @Test
        @DisplayName("未知状态码一律拒绝（不抛异常、不放过）")
        void unknownCodesRejected() {
            assertFalse(OrderStatusFlow.canTransit(99, PENDING), "99 → 0");
            assertFalse(OrderStatusFlow.canTransit(PENDING, 99), "0 → 99");
            assertFalse(OrderStatusFlow.canTransit(-1, 3), "-1 → 3");
            assertFalse(OrderStatusFlow.isTerminal(99), "99 不应被视为终态");
        }
    }

    @Nested
    @DisplayName("三、终态判定")
    class Terminal {

        @ParameterizedTest(name = "{0} 是终态")
        @ValueSource(ints = {FINISHED, CANCELLED, REFUNDED})
        @DisplayName("已完成 / 已取消 / 已退款 是终态")
        void terminal(int status) {
            assertTrue(OrderStatusFlow.isTerminal(status), status + " 应是终态");
        }

        @ParameterizedTest(name = "{0} 不是终态")
        @ValueSource(ints = {PENDING, DELIVERING, RECEIVING})
        @DisplayName("待交易 / 待交付 / 待收货 不是终态")
        void notTerminal(int status) {
            assertFalse(OrderStatusFlow.isTerminal(status), status + " 不应是终态");
        }
    }

    @Nested
    @DisplayName("四、谁能推进（越权防护）")
    class Actor {

        @Test
        @DisplayName("待交付与待收货：只有卖家能推进")
        void sellerOnly() {
            assertTrue(OrderStatusFlow.actorAllowed(DELIVERING, true), "卖家可以标记可交付");
            assertFalse(OrderStatusFlow.actorAllowed(DELIVERING, false), "买家不能替卖家标记可交付");
            assertTrue(OrderStatusFlow.actorAllowed(RECEIVING, true), "卖家可以确认已交付");
            assertFalse(OrderStatusFlow.actorAllowed(RECEIVING, false), "买家不能替卖家确认已交付");
        }

        @Test
        @DisplayName("★ 已完成：买卖双方都可以确认完成（与 US-ORDER-03 的既有需求一致）")
        void finishAllowedForBoth() {
            // 这一条最初被我写成"只有买家能确认收货"，跑全量测试时与 US-ORDER-03
            //（"作为卖家，我希望在完成线下交付后确认交易完成"）以及既有的
            // OrderModuleServiceImplTest.sellerCanFinish 冲突 —— 既有需求是权威，改规则不改需求。
            assertTrue(OrderStatusFlow.actorAllowed(FINISHED, false), "买家可以确认完成");
            assertTrue(OrderStatusFlow.actorAllowed(FINISHED, true), "卖家也可以确认完成");
        }

        @Test
        @DisplayName("取消与退款：买卖双方都可以发起")
        void bothSides() {
            assertTrue(OrderStatusFlow.actorAllowed(CANCELLED, true), "卖家可取消");
            assertTrue(OrderStatusFlow.actorAllowed(CANCELLED, false), "买家可取消");
            assertTrue(OrderStatusFlow.actorAllowed(REFUNDED, true), "卖家可退款");
            assertTrue(OrderStatusFlow.actorAllowed(REFUNDED, false), "买家可申请退款");
        }
    }

    @Nested
    @DisplayName("五、商品状态联动")
    class ProductSync {

        @ParameterizedTest(name = "推进到 {0} → {1}")
        @CsvSource({
                "1, TRADING",
                "2, TRADING",
                "3, SOLD",
                "4, ON_SALE",
                "5, ON_SALE",
        })
        @DisplayName("交付/收货阶段商品仍交易中；完成变已售出；取消或退款回到在售")
        void productStatusAfter(int to, String expected) {
            assertEquals(expected, OrderStatusFlow.productStatusAfter(to),
                    "推进到 " + to + " 的商品状态");
        }

        @Test
        @DisplayName("未知目标状态不产生商品变更（返回空串，由调用方拒绝）")
        void unknownTarget() {
            assertEquals("", OrderStatusFlow.productStatusAfter(99));
        }
    }

    @Nested
    @DisplayName("六、动作文案（前端按钮）")
    class Labels {

        @ParameterizedTest(name = "{0} → {1} 的文案不为空且互不相同")
        @CsvSource({
                "0, 1", "1, 2", "2, 3", "0, 4", "1, 5", "2, 5"
        })
        @DisplayName("每个合法流转都有明确的按钮文案")
        void labels(int from, int to) {
            String label = OrderStatusFlow.actionLabel(from, to);
            assertFalse(label == null || label.isBlank(), from + "→" + to + " 应有文案");
        }

        @Test
        @DisplayName("具体文案：标记可交付 / 已交付 / 确认收货 / 取消订单 / 申请退款")
        void exactLabels() {
            assertEquals("标记可交付", OrderStatusFlow.actionLabel(0, 1));
            assertEquals("确认已交付", OrderStatusFlow.actionLabel(1, 2));
            assertEquals("确认收货", OrderStatusFlow.actionLabel(2, 3));
            assertEquals("取消订单", OrderStatusFlow.actionLabel(0, 4));
            assertEquals("申请退款", OrderStatusFlow.actionLabel(1, 5));
            assertEquals("申请退款", OrderStatusFlow.actionLabel(2, 5));
        }

        @Test
        @DisplayName("非法流转没有文案")
        void illegalHasNoLabel() {
            assertEquals("", OrderStatusFlow.actionLabel(1, 3));
            assertEquals("", OrderStatusFlow.actionLabel(3, 0));
        }
    }
}
