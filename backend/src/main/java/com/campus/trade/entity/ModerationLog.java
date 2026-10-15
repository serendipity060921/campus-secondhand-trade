package com.campus.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容机审记录（v0.18 / C3）。
 *
 * <p>把机审结论从"只写应用日志"升级为"落库可查"，从而支持：</p>
 * <ul>
 *   <li>按任意时间范围聚合放行率 / 转审率 / 拒绝率；</li>
 *   <li>按规则维度看命中分布（哪条规则拦得最多）；</li>
 *   <li>出趋势（按天统计，观察违规内容的变化）。</li>
 * </ul>
 *
 * <p>本表是**只追加**的事实表：机审记录属于审计留痕，不设逻辑删除列。</p>
 */
@Data
@TableName("moderation_log")
public class ModerationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 发布者ID */
    private Long sellerId;

    /** 商品ID（被拒绝时商品未创建，此处为空） */
    private Long productId;

    /** 被审标题（超长截断） */
    private String title;

    /** 机审结论：PASS / REVIEW / REJECT */
    private String action;

    /** 命中的规则编号，多条用逗号连接 */
    private String riskRule;

    /** 命中条数 */
    private Integer hitCount;

    /** 命中明细：rule|field|matched，多条用 ; 连接 */
    private String hits;

    /** 给用户看的原因文本 */
    private String reason;

    /** 是否进入人工审核：0否 1是 */
    private Integer auditRequired;

    /** 机审时间 */
    private LocalDateTime createTime;
}
