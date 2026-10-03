package com.campus.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理员操作日志（v0.13）。
 *
 * <p>记录管理端的关键写操作（审核商品、禁用用户、处理举报），
 * 用于责任追溯：出问题时能查到"谁在什么时候把哪件商品改成了什么状态"。</p>
 */
@Data
@TableName("admin_log")
public class AdminLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作管理员ID */
    private Long adminId;

    /** 管理员用户名（冗余，便于直接看日志） */
    private String adminName;

    /** 操作类型：AUDIT_PRODUCT / DISABLE_USER / ENABLE_USER / HANDLE_REPORT */
    private String action;

    /** 对象类型：PRODUCT / USER / REPORT */
    private String targetType;

    /** 对象ID */
    private Long targetId;

    /** 操作详情 */
    private String detail;

    /** 操作人IP */
    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
