package com.campus.trade.common.moderation;

import java.util.Collections;
import java.util.List;

/**
 * 机审结果：动作 + 命中的规则明细
 *
 * <p>命中的明细（{@link Hit}）会写进发布日志并可在报告中统计，
 * 用于评估机审的<b>漏报与误报</b>情况。</p>
 */
public final class ModerationResult {

    private final ModerationAction action;
    private final List<Hit> hits;

    private ModerationResult(ModerationAction action, List<Hit> hits) {
        this.action = action;
        this.hits = hits == null ? Collections.emptyList() : List.copyOf(hits);
    }

    public static ModerationResult pass() {
        return new ModerationResult(ModerationAction.PASS, Collections.emptyList());
    }

    public static ModerationResult of(ModerationAction action, List<Hit> hits) {
        return new ModerationResult(action, hits);
    }

    public ModerationAction getAction() {
        return action;
    }

    public List<Hit> getHits() {
        return hits;
    }

    public boolean isPass() {
        return action == ModerationAction.PASS;
    }

    public boolean isReview() {
        return action == ModerationAction.REVIEW;
    }

    public boolean isReject() {
        return action == ModerationAction.REJECT;
    }

    /** 可直接展示给用户的原因文案（多个命中用「、」连接） */
    public String reasonText() {
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(h.getReason());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ModerationResult{" + action + ", hits=" + hits + '}';
    }

    /** 单条命中明细 */
    public static final class Hit {
        private final String rule;      // 规则编号，如 R2-违禁词
        private final String field;     // 命中字段：title / description / price
        private final String matched;   // 命中的词或片段
        private final String reason;    // 给用户看的原因
        private final ModerationAction action;

        public Hit(String rule, String field, String matched, String reason, ModerationAction action) {
            this.rule = rule;
            this.field = field;
            this.matched = matched;
            this.reason = reason;
            this.action = action;
        }

        public String getRule() {
            return rule;
        }

        public String getField() {
            return field;
        }

        public String getMatched() {
            return matched;
        }

        public String getReason() {
            return reason;
        }

        public ModerationAction getAction() {
            return action;
        }

        @Override
        public String toString() {
            return rule + '(' + field + ':' + matched + ')';
        }
    }
}
