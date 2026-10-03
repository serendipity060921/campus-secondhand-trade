package com.campus.trade.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台数据看板（v0.13）。
 *
 * <p>一次性返回看板所需的全部数据，前端用 ECharts 直接渲染：
 * 概览卡片 + 近 7 天趋势折线 + 分类分布饼图 + 状态分布环形图 + 待办数量。</p>
 */
@Data
public class DashboardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 概览指标（卡片） */
    private Overview overview = new Overview();

    /** 近 7 天趋势（用户/商品/订单/成交额） */
    private List<NameCountVO> userTrend = new ArrayList<>();
    private List<NameCountVO> productTrend = new ArrayList<>();
    private List<NameCountVO> orderTrend = new ArrayList<>();

    /** 商品分类分布（饼图） */
    private List<NameCountVO> categoryDist = new ArrayList<>();

    /** 订单状态分布（环形图） */
    private List<NameCountVO> orderStatusDist = new ArrayList<>();

    /** 商品状态分布（环形图） */
    private List<NameCountVO> productStatusDist = new ArrayList<>();

    /** 数据生成时间（前端展示"数据截至"） */
    private String generatedAt;

    /**
     * 概览指标。
     */
    @Data
    public static class Overview implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 用户总数 */
        private Long userCount = 0L;
        /** 商品总数 */
        private Long productCount = 0L;
        /** 在售商品数 */
        private Long onSaleCount = 0L;
        /** 订单总数 */
        private Long orderCount = 0L;
        /** 已完成订单数 */
        private Long finishedOrderCount = 0L;
        /** 累计成交额（已完成订单金额合计） */
        private BigDecimal gmv = BigDecimal.ZERO;
        /** 近 7 天成交额 */
        private BigDecimal gmv7d = BigDecimal.ZERO;
        /** 今日新增用户 */
        private Long todayNewUsers = 0L;
        /** 今日新增商品 */
        private Long todayNewProducts = 0L;
        /** 今日新增订单 */
        private Long todayNewOrders = 0L;
        /** 待审核商品数（待办） */
        private Long pendingAuditCount = 0L;
        /** 待处理举报数（待办） */
        private Long pendingReportCount = 0L;
        /** 私信/留言总数 */
        private Long messageCount = 0L;
        /** 收藏总数 */
        private Long favoriteCount = 0L;
    }
}
