package com.campus.trade.service;

import com.campus.trade.common.moderation.ModerationResult;
import com.campus.trade.entity.ModerationLog;
import com.campus.trade.mapper.ModerationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 机审记录落库服务（v0.18 / C3）。
 *
 * <p><b>为什么单独一个 Bean + 独立事务</b>：机审结论为「拒绝」时，发布方法会抛业务异常，
 * 外层事务随之回滚。如果机审记录与业务同事务，日志会被一起回滚掉 ——
 * 结果是"拒了但查不到"，统计口径直接失真。</p>
 *
 * <p>因此这里用 {@code REQUIRES_NEW} 开一个独立事务：无论外层发布成功还是被拒，
 * 机审记录都会留存。另外，独立事务要求调用方通过 <b>Spring 代理</b>调用本 Bean
 * （不能是自己类内的方法），所以它被单独抽出来而不是写在商品服务里。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationLogService {

    /** 标题在日志表里的最大长度（与 moderation_log.title 一致） */
    private static final int TITLE_MAX = 255;
    /** 命中明细分段长度上限 */
    private static final int HITS_MAX = 1000;
    /** 规则编号列长度上限 */
    private static final int RULE_MAX = 200;
    /** 原因列长度上限 */
    private static final int REASON_MAX = 500;

    private final ModerationLogMapper moderationLogMapper;

    /**
     * 记录一次机审结论。
     *
     * @param sellerId      发布者ID
     * @param productId     商品ID（被拒绝时商品尚未创建，传 null）
     * @param title         被审标题
     * @param result        机审结果
     * @param auditRequired 最终是否进入人工审核（审核开关打开时，放行也可能要人审）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long sellerId, Long productId, String title,
                       ModerationResult result, boolean auditRequired) {
        try {
            ModerationLog entity = new ModerationLog();
            entity.setSellerId(sellerId);
            entity.setProductId(productId);
            entity.setTitle(truncate(title, TITLE_MAX));
            entity.setAction(result.getAction().name());
            entity.setHitCount(result.getHits().size());
            entity.setRiskRule(truncate(ruleNames(result), RULE_MAX));
            entity.setHits(truncate(hitDetail(result), HITS_MAX));
            entity.setReason(truncate(result.reasonText(), REASON_MAX));
            entity.setAuditRequired(auditRequired ? 1 : 0);
            entity.setCreateTime(LocalDateTime.now());
            moderationLogMapper.insert(entity);
        } catch (Exception e) {
            // 落库失败不能影响发布主流程（机审结论已经通过日志留痕，此处只降级记录）
            log.warn("[机审落库失败] sellerId={} action={} 原因={}", sellerId,
                    result == null ? null : result.getAction(), e.getMessage());
        }
    }

    /** 命中的规则编号（去重保序），如 R1-违禁词,R2-联系方式 */
    private String ruleNames(ModerationResult result) {
        Set<String> rules = result.getHits().stream()
                .map(ModerationResult.Hit::getRule)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return rules.isEmpty() ? null : String.join(",", rules);
    }

    /** 命中明细：rule|field|matched，多条用 ; 连接 */
    private String hitDetail(ModerationResult result) {
        if (result.getHits().isEmpty()) {
            return null;
        }
        return result.getHits().stream()
                .map(h -> h.getRule() + "|" + h.getField() + "|" + h.getMatched())
                .collect(Collectors.joining(";"));
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
