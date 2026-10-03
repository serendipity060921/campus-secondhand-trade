package com.campus.trade.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 物品共现聚合结果（v0.11 推荐模块内部用）。
 *
 * <p>sim(source, target) = coCount / sqrt(pop(source) * pop(target))</p>
 */
@Data
public class ItemCoOccurrenceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户交互过的商品（相似度的"来源"物品） */
    private Long sourceId;

    /** 候选商品（相似度的"目标"物品） */
    private Long targetId;

    /** 同时与两者产生过行为的去重用户数 */
    private Integer coCount;
}
