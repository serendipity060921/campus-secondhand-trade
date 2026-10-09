package com.campus.trade.common.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 内容机审器的单元验证（内容治理第一层）
 *
 * <p>★ TDD 实践：本测试文件先于实现编写（先红后绿）。
 * 对应需求：US-ADMIN-00 内容自动审核（发布时按规则判定是否放行/转人审/拒绝）。</p>
 *
 * <p>运行：{@code ./mvnw test}（项目首次引入 Java 单元测试）</p>
 *
 * <p>重点覆盖三件事：<br>
 * ① <b>放行不误伤</b>：正常商品必须 PASS —— 这是保证既有端到端测试全绿的前提；<br>
 * ② <b>违禁要拦住</b>：明确违规内容 REJECT；<br>
 * ③ <b>可疑转人审</b>：联系方式引流等 REVIEW；并验证常见规避写法（全角、插空格）仍能被识别。</p>
 */
class ContentModeratorTest {

    private final ContentModerator moderator = new ContentModerator();

    private ModerationAction action(String title, String desc) {
        return moderator.moderate(title, desc, 1L, new BigDecimal("88.50")).getAction();
    }

    @Nested
    @DisplayName("一、正常商品必须放行（避免误报影响正常发布）")
    class Pass {

        @Test
        @DisplayName("普通教材类商品 → PASS")
        void normalProduct() {
            ModerationResult r = moderator.moderate("高等数学教材（上册）九成新",
                    "大一用过，有少量笔记，东校区自提", 1L, new BigDecimal("25.50"));
            assertEquals(ModerationAction.PASS, r.getAction());
            assertTrue(r.isPass());
            assertTrue(r.getHits().isEmpty());
            assertEquals("", r.reasonText());
        }

        @Test
        @DisplayName("数码、生活、运动等其他分类的正常商品 → PASS")
        void otherCategories() {
            assertEquals(ModerationAction.PASS,
                    action("iPad 第 9 代 64G", "毕业出，功能正常，可当面验货"));
            assertEquals(ModerationAction.PASS,
                    action("宿舍小台灯 护眼款", "用了半年，无损坏"));
            assertEquals(ModerationAction.PASS,
                    action("篮球 斯伯丁 7 号", "打过几次，成色好"));
        }

        @Test
        @DisplayName("描述为空（选填）→ PASS，不抛异常")
        void emptyDescription() {
            assertEquals(ModerationAction.PASS, action("英语四级真题册", null));
            assertEquals(ModerationAction.PASS, action("英语四级真题册", ""));
        }

        @Test
        @DisplayName("标题含正常数字与符号（如价格、成色）→ PASS")
        void normalNumbers() {
            assertEquals(ModerationAction.PASS, action("考研数学 1000 题（99 新）", "原价 68 元，现 20 元"));
        }
    }

    @Nested
    @DisplayName("二、明确违禁内容必须拒绝")
    class Reject {

        @Test
        @DisplayName("论文代写类 → REJECT")
        void ghostWriting() {
            ModerationResult r = moderator.moderate("专业代写毕业论文，包过",
                    "各科都可代写，价格好谈", 1L, new BigDecimal("500.00"));
            assertEquals(ModerationAction.REJECT, r.getAction());
            assertTrue(r.isReject());
            assertFalse(r.reasonText().isEmpty());
        }

        @Test
        @DisplayName("证件、发票类 → REJECT")
        void illegalDocuments() {
            assertEquals(ModerationAction.REJECT, action("代办各类证件", "身份证、学生证均可"));
            assertEquals(ModerationAction.REJECT, action("代开发票", "正规发票，点数低"));
        }

        @Test
        @DisplayName("管制刀具、涉枪涉毒类 → REJECT")
        void dangerousGoods() {
            assertEquals(ModerationAction.REJECT, action("出售管制刀具", "开刃，可邮寄"));
            assertEquals(ModerationAction.REJECT, action("转手气枪", "配件齐全"));
        }

        @Test
        @DisplayName("赌博、色情类 → REJECT")
        void gamblingAndPorn() {
            assertEquals(ModerationAction.REJECT, action("出售赌博机", "可改分"));
            assertEquals(ModerationAction.REJECT, action("成人视频资源", "网盘发货"));
        }
    }

    @Nested
    @DisplayName("三、可疑内容转人工复核")
    class Review {

        @Test
        @DisplayName("描述里留手机号 → REVIEW（引流嫌疑）")
        void phoneNumber() {
            ModerationResult r = moderator.moderate("四级词汇书", "有意联系 13812345678", 1L,
                    new BigDecimal("15.00"));
            assertEquals(ModerationAction.REVIEW, r.getAction());
            assertTrue(r.isReview());
            assertFalse(r.reasonText().isEmpty());
        }

        @Test
        @DisplayName("微信号 / QQ 号 / 外链 → REVIEW")
        void otherContacts() {
            assertEquals(ModerationAction.REVIEW, action("闲置键盘", "加微信 abc123456 详聊"));
            assertEquals(ModerationAction.REVIEW, action("闲置显示器", "QQ 12345678 联系"));
            assertEquals(ModerationAction.REVIEW, action("闲置耳机", "详情见 http://example.com/item"));
        }

        @Test
        @DisplayName("价格异常偏高 → REVIEW（人工判断是否真实）")
        void abnormalPrice() {
            ModerationResult r = moderator.moderate("普通水杯", "正常描述", 1L, new BigDecimal("999999.00"));
            assertEquals(ModerationAction.REVIEW, r.getAction());
        }
    }

    @Nested
    @DisplayName("四、规避写法仍应被识别（机审的价值所在）")
    class Evasion {

        @Test
        @DisplayName("全角字符/大小写混写 → 归一化后仍命中")
        void fullWidth() {
            assertEquals(ModerationAction.REJECT, action("专业代写论文", "包过"));
            assertEquals(ModerationAction.REJECT, action("代办各类证件", "价格低"));
        }

        @Test
        @DisplayName("词中插入空格或符号 → 归一化后仍命中")
        void insertedSeparators() {
            assertEquals(ModerationAction.REJECT, action("代 写 论 文", "包过"));
            assertEquals(ModerationAction.REJECT, action("代*写*论*文", "包过"));
        }

        @Test
        @DisplayName("命中明细可用于统计与解释（规则号、字段、命中词）")
        void hitsAreReported() {
            ModerationResult r = moderator.moderate("专业代写论文", "联系 13812345678", 1L,
                    new BigDecimal("100.00"));
            assertEquals(ModerationAction.REJECT, r.getAction());
            assertFalse(r.getHits().isEmpty(), "应当记录命中明细，便于统计漏报/误报");
            assertNotNull(r.getHits().get(0).getRule());
            assertNotNull(r.getHits().get(0).getField());
            assertFalse(r.reasonText().isEmpty(), "必须给出可展示给用户的原因");
        }

        @Test
        @DisplayName("同一输入多次判定结果一致（纯函数、无副作用）")
        void deterministic() {
            String t = "闲置自行车 九成新";
            assertEquals(action(t, "东校区自提"), action(t, "东校区自提"));
        }
    }
}
