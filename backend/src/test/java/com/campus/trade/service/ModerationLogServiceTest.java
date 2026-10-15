package com.campus.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.campus.trade.common.moderation.ModerationAction;
import com.campus.trade.common.moderation.ModerationResult;
import com.campus.trade.entity.ModerationLog;
import com.campus.trade.mapper.ModerationLogMapper;

/**
 * 机审记录落库服务验证（v0.18 / C3）
 *
 * <p>落库的价值在于"统计口径有据可查"，因此本测试锁定三件事：</p>
 * <ol>
 *   <li>三种机审结论（放行 / 转审 / 拒绝）都能正确落库，字段映射不串位；</li>
 *   <li>命中规则要去重保序，明细可读（便于按规则统计与排查）；</li>
 *   <li>★ 落库失败<b>不能影响发布主流程</b> —— 数据库写日志属于旁路，
 *       异常必须被吞掉并降级为告警，否则一次统计故障会导致用户发不出商品。</li>
 * </ol>
 */
class ModerationLogServiceTest {

    private ModerationLogMapper mapper;
    private ModerationLogService service;

    private static final Long SELLER = 9L;

    @BeforeEach
    void setUp() {
        mapper = mock(ModerationLogMapper.class);
        service = new ModerationLogService(mapper);
    }

    private ModerationLog capture() {
        ArgumentCaptor<ModerationLog> captor = ArgumentCaptor.forClass(ModerationLog.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private ModerationResult.Hit hit(String rule, String field, String matched, String reason,
                                     ModerationAction action) {
        return new ModerationResult.Hit(rule, field, matched, reason, action);
    }

    @Test
    @DisplayName("放行：action=PASS、命中数为 0、规则与明细为空")
    void passRecorded() {
        service.record(SELLER, null, "九成新《数据结构》教材", ModerationResult.pass(), false);

        ModerationLog log = capture();
        assertEquals("PASS", log.getAction());
        assertEquals(0, log.getHitCount());
        assertNull(log.getRiskRule(), "没有命中时规则列应为空");
        assertNull(log.getHits());
        assertEquals(0, log.getAuditRequired());
        assertEquals(SELLER, log.getSellerId());
        assertNotNull(log.getCreateTime(), "必须记录机审时间（趋势统计的基础）");
    }

    @Test
    @DisplayName("转人工复核：action=REVIEW，规则与明细都要落库")
    void reviewRecorded() {
        ModerationResult result = ModerationResult.of(ModerationAction.REVIEW, List.of(
                hit("R2-联系方式", "title", "13812345678", "标题疑似引流（13812345678）",
                        ModerationAction.REVIEW)));

        service.record(SELLER, null, "低价出教材 13812345678", result, true);

        ModerationLog log = capture();
        assertEquals("REVIEW", log.getAction());
        assertEquals(1, log.getHitCount());
        assertEquals("R2-联系方式", log.getRiskRule());
        assertEquals("R2-联系方式|title|13812345678", log.getHits());
        assertTrue(log.getReason().contains("13812345678"));
        assertEquals(1, log.getAuditRequired(), "转审必然进入人工审核");
    }

    @Test
    @DisplayName("拒绝：action=REJECT，原因文本落库（用户能看到什么，后台就能查到什么）")
    void rejectRecorded() {
        ModerationResult result = ModerationResult.of(ModerationAction.REJECT, List.of(
                hit("R1-违禁词", "title", "代写", "标题包含违规内容「代写」", ModerationAction.REJECT)));

        service.record(SELLER, null, "代写课程论文", result, false);

        ModerationLog log = capture();
        assertEquals("REJECT", log.getAction());
        assertEquals("R1-违禁词", log.getRiskRule());
        assertTrue(log.getReason().contains("代写"));
    }

    @Test
    @DisplayName("命中多条规则时：规则去重且保持出现顺序（便于按规则聚合统计）")
    void rulesDeduplicatedAndOrdered() {
        ModerationResult result = ModerationResult.of(ModerationAction.REJECT, List.of(
                hit("R1-违禁词", "title", "代写", "标题包含违规内容", ModerationAction.REJECT),
                hit("R2-联系方式", "description", "138", "描述疑似引流", ModerationAction.REVIEW),
                hit("R1-违禁词", "description", "包过", "描述包含违规内容", ModerationAction.REJECT)));

        service.record(SELLER, null, "代写包过 13812345678", result, false);

        ModerationLog log = capture();
        assertEquals("R1-违禁词,R2-联系方式", log.getRiskRule(), "应去重且保持首次出现的顺序");
        assertEquals(3, log.getHitCount());
        assertEquals(3, log.getHits().split(";").length, "明细应逐条保留");
    }

    @Test
    @DisplayName("超长标题被截断保存，不因长度超限而写入失败")
    void longTitleTruncated() {
        String longTitle = "测".repeat(500);

        service.record(SELLER, null, longTitle, ModerationResult.pass(), false);

        ModerationLog log = capture();
        assertTrue(log.getTitle().length() <= 255, "标题应截断到列长度以内：" + log.getTitle().length());
        assertTrue(log.getTitle().endsWith("…"), "截断应有省略号标记");
    }

    @Test
    @DisplayName("★ 落库失败不影响调用方（抛异常会被吞掉并降级告警）")
    void databaseFailureSwallowed() {
        doThrow(new RuntimeException("数据库连接失败")).when(mapper).insert(any(ModerationLog.class));

        // 不应抛出任何异常
        service.record(SELLER, null, "正常商品", ModerationResult.pass(), false);

        verify(mapper).insert(any(ModerationLog.class));
    }
}
