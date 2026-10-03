package com.campus.trade.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 举报视图（v0.13）。
 */
@Data
public class ReportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /* 举报人 */
    private Long reporterId;
    private String reporterName;

    /* 举报对象 */
    private Integer targetType;
    private String targetTypeLabel;
    private Long targetId;
    private String targetTitle;
    private String targetExtra;

    /* 举报内容 */
    private Integer reasonType;
    private String reasonLabel;
    private String content;
    private String imageUrl;

    /* 处理情况 */
    private Integer status;
    private String statusLabel;
    private Long handleAdminId;
    private String handleAdminName;
    private String handleResult;
    private LocalDateTime handleTime;

    private LocalDateTime createTime;
}
