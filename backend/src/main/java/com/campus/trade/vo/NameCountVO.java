package com.campus.trade.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 通用「名称-数值」统计项（v0.13）。
 *
 * <p>用于看板的按天趋势、分类分布、状态分布等图表数据；ECharts 可直接消费。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NameCountVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 名称 / 日期（图表 X 轴） */
    private String label;

    /** 数量（图表 Y 轴） */
    private Long value;

    /** 金额（可选，用于成交额趋势） */
    private BigDecimal amount;

    public NameCountVO(String label, Long value) {
        this.label = label;
        this.value = value;
    }
}
