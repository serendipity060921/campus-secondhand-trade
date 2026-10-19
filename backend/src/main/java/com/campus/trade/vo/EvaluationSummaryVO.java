package com.campus.trade.vo;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * 评价汇总（v0.18 / 第6批-㉑）。
 *
 * <p>商品详情页与个人主页都要显示"多少条评价、平均几星、好评率多少"，
 * 这些数字必须由同一处口径算出来，否则两个页面会对不上。</p>
 *
 * <p>口径说明（写进 VO 注释，避免以后各算各的）：</p>
 * <ul>
 *   <li>{@code avgRating}：平均星级，保留 1 位小数；无评价时为 0.0；</li>
 *   <li>{@code goodRate}：<b>4~5 星</b>占全部评价的百分比，保留 1 位小数；无评价时 0.0；</li>
 *   <li>{@code distribution}：5→1 星各自的条数，键为星数，缺的补 0。</li>
 * </ul>
 */
@Data
public class EvaluationSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 汇总主体：商品ID 或 用户ID（按调用接口决定） */
    private Long targetId;

    /** 评价总条数 */
    private Long count;

    /** 平均星级（1 位小数，无评价为 0.0） */
    private Double avgRating;

    /** 好评率（4~5 星占比，百分数，1 位小数） */
    private Double goodRate;

    /** 星级分布：5→1 星条数（缺的补 0） */
    private Map<Integer, Long> distribution = new LinkedHashMap<>();

    /** 建一个空分布（5→1 全 0），保证前端拿到的键永远齐全 */
    public static Map<Integer, Long> emptyDistribution() {
        Map<Integer, Long> map = new LinkedHashMap<>();
        for (int i = 5; i >= 1; i--) {
            map.put(i, 0L);
        }
        return map;
    }
}
