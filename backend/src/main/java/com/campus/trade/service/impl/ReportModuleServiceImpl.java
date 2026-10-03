package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.exception.AdminException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.ReportCreateDTO;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.Report;
import com.campus.trade.entity.User;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.ReportModuleService;
import com.campus.trade.service.ReportService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.ReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 举报业务实现（v0.13，用户侧）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportModuleServiceImpl implements ReportModuleService {

    /** 举报对象类型 */
    private static final int TARGET_PRODUCT = 1;
    private static final int TARGET_USER = 2;
    /** 待处理 */
    private static final int STATUS_PENDING = 0;

    private final ReportService reportService;
    private final ProductService productService;
    private final UserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportVO submit(ReportCreateDTO dto, Long userId) {
        String targetTitle;
        String targetExtra = null;

        if (TARGET_PRODUCT == dto.getTargetType()) {
            Product product = productService.getById(dto.getTargetId());
            if (product == null) {
                throw AdminException.reportTargetNotFound();
            }
            if (Objects.equals(product.getSellerId(), userId)) {
                throw AdminException.cannotReportSelf();
            }
            targetTitle = product.getTitle();
        } else {
            User target = userService.getById(dto.getTargetId());
            if (target == null) {
                throw AdminException.reportTargetNotFound();
            }
            if (Objects.equals(target.getId(), userId)) {
                throw AdminException.cannotReportSelf();
            }
            targetTitle = target.getNickname();
            targetExtra = target.getUsername();
        }

        Report report = new Report();
        report.setReporterId(userId);
        report.setTargetType(dto.getTargetType());
        report.setTargetId(dto.getTargetId());
        report.setReasonType(dto.getReasonType());
        report.setContent(StringUtils.hasText(dto.getContent()) ? dto.getContent().trim() : null);
        report.setImageUrl(dto.getImageUrl());
        report.setStatus(STATUS_PENDING);
        report.setDeleted(0);
        reportService.save(report);

        log.info("[提交举报] id={} 举报人={} 类型={} 对象={} 原因={}",
                report.getId(), userId, dto.getTargetType(), dto.getTargetId(), dto.getReasonType());

        ReportVO vo = toVO(report, nameMapOf(userId));
        vo.setTargetTitle(targetTitle);
        vo.setTargetExtra(targetExtra);
        return vo;
    }

    @Override
    public PageResult<ReportVO> myReports(int page, int size, Long userId) {
        Page<Report> result = reportService.page(new Page<>(page, size),
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getReporterId, userId)
                        .orderByDesc(Report::getCreateTime));
        return new PageResult<>(result.getTotal(), result.getPages(), result.getCurrent(), result.getSize(),
                toVOList(result.getRecords()));
    }

    /* ==================== 转换工具（管理端复用同一套标签逻辑） ==================== */

    @Override
    public List<ReportVO> toVOList(List<Report> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = new HashMap<>();
        records.stream().map(Report::getReporterId).filter(Objects::nonNull).distinct()
                .forEach(id -> names.put(id, nicknameOf(id)));
        records.stream().map(Report::getHandleAdminId).filter(Objects::nonNull).distinct()
                .forEach(id -> names.put(id, nicknameOf(id)));
        return records.stream().map(r -> toVO(r, names)).collect(Collectors.toList());
    }

    private ReportVO toVO(Report report, Map<Long, String> nameMap) {
        ReportVO vo = new ReportVO();
        vo.setId(report.getId());
        vo.setReporterId(report.getReporterId());
        vo.setReporterName(nameMap.get(report.getReporterId()));
        vo.setTargetType(report.getTargetType());
        vo.setTargetTypeLabel(TARGET_PRODUCT == (report.getTargetType() == null ? 0 : report.getTargetType())
                ? "商品" : "用户");
        vo.setTargetId(report.getTargetId());
        vo.setReasonType(report.getReasonType());
        vo.setReasonLabel(reasonLabel(report.getReasonType()));
        vo.setContent(report.getContent());
        vo.setImageUrl(report.getImageUrl());
        vo.setStatus(report.getStatus());
        vo.setStatusLabel(statusLabel(report.getStatus()));
        vo.setHandleAdminId(report.getHandleAdminId());
        // 注意：未处理的举报 handle_admin_id 为 null，必须判空后再查；
        // 且不能用 Map.of() 创建的不可变 Map（它对 null 查询会直接抛 NPE，v0.13 测试用例 F3 踩过）
        vo.setHandleAdminName(report.getHandleAdminId() == null ? null : nameMap.get(report.getHandleAdminId()));
        vo.setHandleResult(report.getHandleResult());
        vo.setHandleTime(report.getHandleTime());
        vo.setCreateTime(report.getCreateTime() == null ? LocalDateTime.now() : report.getCreateTime());
        return vo;
    }

    private String nicknameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userService.getById(userId);
        return user == null ? null : user.getNickname();
    }

    /**
     * 构造"用户ID -> 昵称"映射。
     * <p>用 {@link HashMap} 而不是 {@code Map.of()}：后者是不可变 Map，
     * 既不允许 null 值、也不允许用 null 去 get，会在"举报尚未处理"时抛 NPE。</p>
     */
    private Map<Long, String> nameMapOf(Long userId) {
        Map<Long, String> map = new HashMap<>();
        map.put(userId, nicknameOf(userId));
        return map;
    }

    private String reasonLabel(Integer reasonType) {
        if (reasonType == null) {
            return "其他";
        }
        return switch (reasonType) {
            case 1 -> "虚假信息";
            case 2 -> "违禁物品";
            case 3 -> "辱骂骚扰";
            default -> "其他";
        };
    }

    private String statusLabel(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "已处理";
            case 2 -> "已忽略";
            default -> "待处理";
        };
    }
}
