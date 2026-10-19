package com.campus.trade.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.exception.EvaluationException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.EvaluationCreateDTO;
import com.campus.trade.entity.Evaluation;
import com.campus.trade.entity.Order;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.service.EvaluationModuleService;
import com.campus.trade.service.EvaluationService;
import com.campus.trade.service.OrderService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.EvaluationSummaryVO;
import com.campus.trade.vo.EvaluationVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 评价模块业务实现（v0.18 / 第6批-㉑）。
 *
 * <p>核心规则（全部有单测固定）：</p>
 * <ol>
 *   <li><b>只有买卖双方能评</b>，且评价对象由订单推导 —— 前端只能传 orderId；</li>
 *   <li><b>只有已完成（status=3）的订单能评</b>；</li>
 *   <li><b>同一订单每人只能评一次</b>：先查再插，并发下由 <code>uk_order_from</code> 唯一索引兜底，
 *       冲突转成业务码 9004（而不是通用的"数据已存在 1002"）；</li>
 *   <li><b>信用分联动</b>：4~5 星 +1，3 星不变，1~2 星 -1，并夹在 [0, 150] 区间 ——
 *       这样一次差评不会把账号"打死"，一次好评也不会无限刷分（唯一索引保证每单只加一次）；</li>
 *   <li><b>匿名只隐藏展示</b>：anonymous=1 时昵称/头像清空，但 fromUserId 仍保留以便追溯。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationModuleServiceImpl implements EvaluationModuleService {

    /** 订单已完成 */
    private static final int ORDER_FINISHED = 3;
    /** 买家评卖家 */
    private static final int ROLE_BUYER_TO_SELLER = 1;
    /** 卖家评买家 */
    private static final int ROLE_SELLER_TO_BUYER = 2;
    /** 匿名占位昵称 */
    private static final String ANONYMOUS_NICKNAME = "匿名同学";
    /** 信用分下限 / 上限（避免一次差评打死、避免无限刷分） */
    private static final int CREDIT_MIN = 0;
    private static final int CREDIT_MAX = 150;

    private final EvaluationService evaluationService;
    private final OrderService orderService;
    private final UserService userService;
    private final ProductService productService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EvaluationVO submit(EvaluationCreateDTO dto, Long userId) {
        Integer rating = dto.getRating();
        if (rating == null || rating < 1 || rating > 5) {
            throw EvaluationException.ratingIllegal();
        }
        String content = dto.getContent() == null ? null : dto.getContent().trim();
        if (content != null && content.isEmpty()) {
            content = null;
        }
        if (content != null && content.length() > 500) {
            throw EvaluationException.contentTooLong();
        }

        Order order = orderService.getById(dto.getOrderId());
        if (order == null) {
            throw EvaluationException.orderNotFound();
        }

        // ① 判定评价者身份与对象：完全由订单推导，前端无法指定
        int role;
        Long toUserId;
        if (Objects.equals(order.getBuyerId(), userId)) {
            role = ROLE_BUYER_TO_SELLER;
            toUserId = order.getSellerId();
        } else if (Objects.equals(order.getSellerId(), userId)) {
            role = ROLE_SELLER_TO_BUYER;
            toUserId = order.getBuyerId();
        } else {
            throw EvaluationException.notParticipant();
        }

        // ② 只有已完成订单能评
        if (!Integer.valueOf(ORDER_FINISHED).equals(order.getStatus())) {
            throw EvaluationException.orderNotFinished();
        }

        // ③ 被评价人必须存在（理论上必然存在，但历史脏数据/账号注销时不能写出悬空评价）
        if (userService.getById(toUserId) == null) {
            throw EvaluationException.targetNotFound();
        }

        // ④ 同一订单每人一次：先查再插
        boolean exists = evaluationService.count(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getOrderId, order.getId())
                .eq(Evaluation::getFromUserId, userId)) > 0;
        if (exists) {
            throw EvaluationException.alreadyEvaluated();
        }

        Evaluation evaluation = new Evaluation();
        evaluation.setOrderId(order.getId());
        evaluation.setProductId(order.getProductId());
        evaluation.setFromUserId(userId);
        evaluation.setToUserId(toUserId);
        evaluation.setRole(role);
        evaluation.setRating(rating);
        evaluation.setContent(content);
        evaluation.setTags(dto.getTags() == null || dto.getTags().isBlank() ? null : dto.getTags().trim());
        evaluation.setAnonymous(Integer.valueOf(1).equals(dto.getAnonymous()) ? 1 : 0);
        evaluation.setDeleted(0);

        try {
            evaluationService.save(evaluation);
        } catch (DuplicateKeyException e) {
            // 并发兜底：唯一索引 uk_order_from
            log.warn("[评价冲突] 并发重复评价 orderId={} userId={}", order.getId(), userId);
            throw EvaluationException.alreadyEvaluated();
        }

        applyCreditScore(toUserId, rating);
        log.info("[评价提交] orderId={} from={} to={} role={} rating={} anonymous={}",
                order.getId(), userId, toUserId, role, rating, evaluation.getAnonymous());

        return toVOs(List.of(evaluation), userId).get(0);
    }

    /**
     * 信用分联动（详见类注释第 4 条）。
     *
     * <p>刻意不用 {@code credit + 1} 这种读改写：并发下会丢更新。
     * 这里用"带上限/下限的条件更新"，由数据库保证原子性；更新不到行说明已经触界，忽略即可。</p>
     */
    private void applyCreditScore(Long toUserId, int rating) {
        int delta = rating >= 4 ? 1 : (rating <= 2 ? -1 : 0);
        if (delta == 0) {
            return;
        }
        int rows = userService.getBaseMapper().update(null, new LambdaUpdateWrapper<User>()
                .setSql("credit_score = LEAST(GREATEST(COALESCE(credit_score, 100) + (" + delta + "), "
                        + CREDIT_MIN + "), " + CREDIT_MAX + ")")
                .eq(User::getId, toUserId));
        log.info("[信用分] userId={} delta={} 影响行数={}", toUserId, delta, rows);
    }

    @Override
    public PageResult<EvaluationVO> pageByProduct(Long productId, long page, long size, Long viewerId) {
        return page(new LambdaQueryWrapper<Evaluation>()
                        .eq(Evaluation::getProductId, productId),
                page, size, viewerId);
    }

    @Override
    public PageResult<EvaluationVO> pageReceived(Long userId, long page, long size, Long viewerId) {
        return page(new LambdaQueryWrapper<Evaluation>()
                        .eq(Evaluation::getToUserId, userId),
                page, size, viewerId);
    }

    @Override
    public PageResult<EvaluationVO> pageSent(long page, long size, Long userId) {
        return page(new LambdaQueryWrapper<Evaluation>()
                        .eq(Evaluation::getFromUserId, userId),
                page, size, userId);
    }

    private PageResult<EvaluationVO> page(LambdaQueryWrapper<Evaluation> wrapper,
                                          long page, long size, Long viewerId) {
        Page<Evaluation> result = evaluationService.page(new Page<>(page, size),
                wrapper.eq(Evaluation::getDeleted, 0).orderByDesc(Evaluation::getId));
        return new PageResult<>(result.getTotal(), result.getPages(), result.getCurrent(),
                result.getSize(), toVOs(result.getRecords(), viewerId));
    }

    @Override
    public EvaluationSummaryVO summaryByProduct(Long productId) {
        List<Evaluation> list = evaluationService.list(new LambdaQueryWrapper<Evaluation>()
                .select(Evaluation::getRating)
                .eq(Evaluation::getProductId, productId)
                .eq(Evaluation::getDeleted, 0));

        Map<Integer, Long> distribution = EvaluationSummaryVO.emptyDistribution();
        long sum = 0;
        long good = 0;
        for (Evaluation e : list) {
            Integer r = e.getRating();
            if (r == null || r < 1 || r > 5) {
                continue;
            }
            distribution.merge(r, 1L, Long::sum);
            sum += r;
            if (r >= 4) {
                good++;
            }
        }
        long count = distribution.values().stream().mapToLong(Long::longValue).sum();

        EvaluationSummaryVO vo = new EvaluationSummaryVO();
        vo.setTargetId(productId);
        vo.setCount(count);
        vo.setDistribution(distribution);
        vo.setAvgRating(count == 0 ? 0.0 : Math.round(sum * 10.0 / count) / 10.0);
        vo.setGoodRate(count == 0 ? 0.0 : Math.round(good * 1000.0 / count) / 10.0);
        return vo;
    }

    @Override
    public List<EvaluationVO> listByOrder(Long orderId, Long userId) {
        Order order = orderService.getById(orderId);
        if (order == null) {
            throw EvaluationException.orderNotFound();
        }
        // 只有买卖双方能看这笔订单的评价详情（含"我评过没"），避免任意人探测订单关系
        if (!Objects.equals(order.getBuyerId(), userId) && !Objects.equals(order.getSellerId(), userId)) {
            throw EvaluationException.notParticipant();
        }
        List<Evaluation> list = evaluationService.list(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getOrderId, orderId)
                .eq(Evaluation::getDeleted, 0)
                .orderByAsc(Evaluation::getId));
        return toVOs(list, userId);
    }

    /* ==================== 转换（批量取昵称/标题，避免 N+1） ==================== */

    private List<EvaluationVO> toVOs(List<Evaluation> list, Long viewerId) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = list.stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getFromUserId(), e.getToUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        Set<Long> productIds = list.stream().map(Evaluation::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Product> products = productIds.isEmpty() ? Map.of()
                : productService.listByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));

        List<EvaluationVO> result = new ArrayList<>(list.size());
        for (Evaluation e : list) {
            result.add(toVO(e, users, products, viewerId));
        }
        return result;
    }

    private EvaluationVO toVO(Evaluation e, Map<Long, User> users, Map<Long, Product> products, Long viewerId) {
        EvaluationVO vo = new EvaluationVO();
        vo.setId(e.getId());
        vo.setOrderId(e.getOrderId());
        vo.setProductId(e.getProductId());
        Product product = e.getProductId() == null ? null : products.get(e.getProductId());
        vo.setProductTitle(product == null ? null : product.getTitle());
        vo.setFromUserId(e.getFromUserId());
        vo.setToUserId(e.getToUserId());
        vo.setRole(e.getRole());
        vo.setRoleLabel(Integer.valueOf(ROLE_BUYER_TO_SELLER).equals(e.getRole()) ? "买家评卖家" : "卖家评买家");
        vo.setRating(e.getRating());
        vo.setContent(e.getContent());
        vo.setTags(e.getTags());
        vo.setAnonymous(e.getAnonymous());
        vo.setCreateTime(e.getCreateTime() == null ? LocalDateTime.now() : e.getCreateTime());
        vo.setMine(viewerId != null && Objects.equals(e.getFromUserId(), viewerId));
        vo.fillTagList();

        User from = e.getFromUserId() == null ? null : users.get(e.getFromUserId());
        User to = e.getToUserId() == null ? null : users.get(e.getToUserId());
        vo.setToNickname(to == null ? null : to.getNickname());
        if (Integer.valueOf(1).equals(e.getAnonymous())) {
            // 匿名：只隐藏展示层身份，fromUserId 仍保留以便追溯
            vo.setFromNickname(ANONYMOUS_NICKNAME);
            vo.setFromAvatar(null);
        } else {
            vo.setFromNickname(from == null ? null : from.getNickname());
            vo.setFromAvatar(from == null ? null : from.getAvatar());
        }
        return vo;
    }

    /** 供单测/调试用：只读的口径说明（无副作用） */
    public static Map<String, Object> creditRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("rating>=4", "+1");
        rule.put("rating==3", "0");
        rule.put("rating<=2", "-1");
        rule.put("range", CREDIT_MIN + "~" + CREDIT_MAX);
        return Collections.unmodifiableMap(rule);
    }
}
