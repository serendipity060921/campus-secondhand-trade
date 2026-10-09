package com.campus.trade.common.moderation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 内容机审器（内容治理第一层：发布时自动审核）
 *
 * <p>与后台人工审核（第二层）配合：机审放行的商品直接上架，命中可疑规则的转为「待审核」
 * 交管理员复核，命中明确违禁的直接拒绝发布。</p>
 *
 * <p><b>规则表</b></p>
 * <ul>
 *   <li>R1 违禁词 —— 论文代写、代办证件、发票、管制刀具、涉枪涉毒、赌博、色情等 → <b>拒绝</b></li>
 *   <li>R2 联系方式引流 —— 手机号、微信号、QQ、外链、二维码 → <b>转人工复核</b></li>
 *   <li>R3 价格异常 —— 超过阈值（默认 10 万元）→ <b>转人工复核</b>；非正数 → <b>拒绝</b></li>
 * </ul>
 *
 * <p><b>为何要归一化</b>：真实场景里违规内容常写成"代 写""代*写""全角字符"来规避匹配。
 * 因此匹配前统一做全角转半角、去符号空白、转小写，再与词表比对。</p>
 *
 * <p>本类为纯函数实现（不依赖数据库与网络），因此可单元测试、可在 CI 中回归；
 * 测试见 {@code src/test/java/.../ContentModeratorTest.java}（TDD：先写测试后写实现）。</p>
 */
@Component
public class ContentModerator {

    /** R3：售价超过该值转人工复核（正常校园二手物品不会到这个量级） */
    private static final BigDecimal PRICE_REVIEW_THRESHOLD = new BigDecimal("100000");

    /** R1 违禁词：命中即拒绝。措辞刻意取得具体，避免误伤正常商品（如只写"刀具"会误伤菜刀） */
    private static final List<String> REJECT_WORDS = Arrays.asList(
            // 学术作弊
            "代写", "代考", "替考", "论文代写", "包过",
            // 证件与票据
            "代办", "办证", "假证", "假身份证", "发票", "增值税发票",
            // 危险物品
            "管制刀具", "弹簧刀", "匕首", "气枪", "仿真枪", "枪支", "弹药", "甩棍",
            // 违禁品
            "毒品", "冰毒", "大麻", "摇头丸", "违禁药品", "野味", "保护动物",
            // 赌博色情
            "赌博", "博彩", "六合彩", "赌场", "老虎机", "成人视频", "色情", "裸聊"
    );

    /** R2 联系方式引流的文本关键词（命中即转人工复核） */
    private static final List<String> REVIEW_WORDS = Arrays.asList(
            "微信", "vx", "v信", "威信", "加我", "扫码", "二维码", "http", "www."
    );

    /** R2 手机号（中国大陆 11 位） */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");

    /** R2 QQ 号：qq 后可有少量分隔符再接 5 位以上数字 */
    private static final Pattern QQ = Pattern.compile("qq\\D{0,4}\\d{5,}");

    /**
     * 对一条商品做机审
     *
     * @param title       商品标题
     * @param description 商品描述（可为空）
     * @param categoryId  分类 id（当前规则表未使用，预留给"禁售类目"扩展）
     * @param price       售价
     * @return 机审结果（PASS / REVIEW / REJECT），命中明细见 {@link ModerationResult#getHits()}
     */
    public ModerationResult moderate(String title, String description, Long categoryId, BigDecimal price) {
        List<ModerationResult.Hit> hits = new ArrayList<>();

        String titleNorm = normalize(title);
        String descNorm = normalize(description);

        // ── R1 违禁词（拒绝）──────────────────────────────
        for (String word : REJECT_WORDS) {
            String w = normalize(word);
            if (!w.isEmpty() && titleNorm.contains(w)) {
                hits.add(new ModerationResult.Hit("R1-违禁词", "title", word,
                        "标题包含违规内容「" + word + "」", ModerationAction.REJECT));
            }
            if (!w.isEmpty() && descNorm.contains(w)) {
                hits.add(new ModerationResult.Hit("R1-违禁词", "description", word,
                        "描述包含违规内容「" + word + "」", ModerationAction.REJECT));
            }
        }

        // ── R2 联系方式引流（转人工复核）────────────────────
        addIfMatch(hits, PHONE, titleNorm, "title", "title", "标题疑似留手机号");
        addIfMatch(hits, PHONE, descNorm, "description", "description", "描述疑似留手机号");
        addIfMatch(hits, QQ, titleNorm, "title", "title", "标题疑似留 QQ 号");
        addIfMatch(hits, QQ, descNorm, "description", "description", "描述疑似留 QQ 号");
        for (String word : REVIEW_WORDS) {
            String w = normalize(word);
            if (!w.isEmpty() && titleNorm.contains(w)) {
                hits.add(new ModerationResult.Hit("R2-联系方式", "title", word,
                        "标题疑似引流（" + word + "）", ModerationAction.REVIEW));
            }
            if (!w.isEmpty() && descNorm.contains(w)) {
                hits.add(new ModerationResult.Hit("R2-联系方式", "description", word,
                        "描述疑似引流（" + word + "）", ModerationAction.REVIEW));
            }
        }

        // ── R3 价格异常 ───────────────────────────────────
        if (price != null) {
            if (price.signum() <= 0) {
                hits.add(new ModerationResult.Hit("R3-价格异常", "price", price.toPlainString(),
                        "价格必须大于 0", ModerationAction.REJECT));
            } else if (price.compareTo(PRICE_REVIEW_THRESHOLD) > 0) {
                hits.add(new ModerationResult.Hit("R3-价格异常", "price", price.toPlainString(),
                        "价格明显偏高，需人工确认", ModerationAction.REVIEW));
            }
        }

        if (hits.isEmpty()) {
            return ModerationResult.pass();
        }
        // 只要有"拒绝"命中就拒绝；否则转人工复核
        boolean anyReject = hits.stream().anyMatch(h -> h.getAction() == ModerationAction.REJECT);
        return ModerationResult.of(anyReject ? ModerationAction.REJECT : ModerationAction.REVIEW, hits);
    }

    private void addIfMatch(List<ModerationResult.Hit> hits, Pattern pattern, String text,
                            String field, String ruleKey, String reason) {
        if (text.isEmpty()) {
            return;
        }
        java.util.regex.Matcher m = pattern.matcher(text);
        if (m.find()) {
            hits.add(new ModerationResult.Hit("R2-联系方式", field, m.group(),
                    reason + "：" + m.group(), ModerationAction.REVIEW));
        }
    }

    /**
     * 归一化：全角转半角 → 去掉所有非字母数字与汉字的字符（空格、*、.、-、· 等）→ 转小写。
     * 目的：让"代 写""代*写""全角字符"这类规避写法仍能被词表命中。
     */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            char ch = c;
            if (ch == '\u3000') {                 // 全角空格
                continue;
            }
            if (ch >= '\uFF01' && ch <= '\uFF5E') { // 全角 ASCII → 半角
                ch = (char) (ch - 0xFEE0);
            }
            if (Character.isLetterOrDigit(ch) || (ch >= '\u4E00' && ch <= '\u9FFF')) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }
}
