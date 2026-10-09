package com.campus.trade.common.moderation;

/**
 * 机审动作（内容治理第一层：自动审核）
 *
 * <p>发布商品时由 {@link ContentModerator} 判定，三种结果对应三种处理：</p>
 * <ul>
 *   <li>{@link #PASS}   —— 未命中风险规则，直接上架（status = 1 在售）</li>
 *   <li>{@link #REVIEW} —— 命中可疑规则（如联系方式引流），转为「待审核」（status = 0），
 *                          交管理后台人工复核（内容治理第二层）</li>
 *   <li>{@link #REJECT} —— 命中明确违禁规则，直接拒绝发布并返回原因</li>
 * </ul>
 *
 * <p>设计意图：原实现只有一个全局开关 {@code campus.audit.enabled}，一开就是"全部商品都人审"。
 * 引入机审后改为<b>按商品风险决定是否需要人审</b>，兼顾校园场景的流通效率与内容合规。</p>
 */
public enum ModerationAction {
    /** 放行：直接上架 */
    PASS,
    /** 转人工复核：进入待审核 */
    REVIEW,
    /** 拒绝：不允许发布 */
    REJECT
}
