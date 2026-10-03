package com.campus.trade.service;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.ReportCreateDTO;
import com.campus.trade.vo.ReportVO;

/**
 * 举报业务（v0.13，面向普通用户）。
 *
 * <p>用户可以举报违规商品或不良用户，管理端在后台处理（见 AdminService）。</p>
 */
public interface ReportModuleService {

    /**
     * 提交举报。
     *
     * @throws com.campus.trade.common.exception.AdminException 举报对象不存在(8007) / 不能举报自己的内容(8006)
     */
    ReportVO submit(ReportCreateDTO dto, Long userId);

    /** 我的举报记录（分页） */
    PageResult<ReportVO> myReports(int page, int size, Long userId);

    /**
     * 批量把举报实体转换为 VO（含举报人/处理人昵称、举报对象标题、标签）。
     * <p>v0.13：管理端举报列表复用同一套转换逻辑，避免两处维护标签映射。</p>
     */
    java.util.List<ReportVO> toVOList(java.util.List<com.campus.trade.entity.Report> records);
}
