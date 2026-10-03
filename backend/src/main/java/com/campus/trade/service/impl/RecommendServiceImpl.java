package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.config.RecommendProperties;
import com.campus.trade.dto.ItemCoOccurrenceDTO;
import com.campus.trade.dto.ProductPopularityDTO;
import com.campus.trade.entity.Category;
import com.campus.trade.entity.Favorite;
import com.campus.trade.entity.Order;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.entity.UserBehavior;
import com.campus.trade.mapper.UserBehaviorMapper;
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.FavoriteService;
import com.campus.trade.service.OrderService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.RecommendService;
import com.campus.trade.service.UserBehaviorService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.RecommendItemVO;
import com.campus.trade.vo.RecommendResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推荐服务实现（v0.11）。
 *
 * <p>整体流程（每一步都在 {@code basis} 里留下可观测的说明，方便测试与答辩演示）：</p>
 * <pre>
 *   候选集（在售商品，按热度截断到 candidatePoolSize）
 *        ↓  过滤：自己发布的 / 已收藏 / 已下单
 *   三路召回打分：Item-CF、内容匹配、热门度
 *        ↓  min-max 归一化
 *   加权融合  score = w_cf·cf + w_content·content + w_hot·hot
 *        ↓  仅浏览过的商品乘惩罚系数
 *   排序 → 分类多样性截断 → TopN → 生成推荐理由
 * </pre>
 *
 * <p>复杂度：设候选商品数 C、用户行为数 B、共现对数为 K，
 * 则一次推荐为 O(C + B + K)，K 由 SQL 按"用户交互过的商品"限定后聚合，
 * 不会随全量行为线性膨胀。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements RecommendService {

    /** 在售状态 */
    private static final int STATUS_ON_SALE = 1;
    /** 订单状态：待交易 / 已完成（这两类都算"已买过"，不再推荐） */
    private static final int ORDER_PENDING = 0;
    private static final int ORDER_FINISHED = 3;

    private final ProductService productService;
    private final CategoryService categoryService;
    private final UserService userService;
    private final FavoriteService favoriteService;
    private final OrderService orderService;
    private final UserBehaviorService userBehaviorService;
    private final UserBehaviorMapper userBehaviorMapper;
    private final RecommendProperties props;

    // ------------------------------------------------------------------ 对外接口

    @Override
    public RecommendResultVO recommend(Long userId, Integer size, String strategy) {
        String mode = normalizeStrategy(strategy);
        int n = normalizeSize(size, props.getDefaultSize());
        List<Product> candidates = loadCandidates();
        RecommendResultVO result = new RecommendResultVO();
        result.setBasis(new ArrayList<>());

        if (candidates.isEmpty()) {
            result.setStrategy(mode);
            result.setStrategyLabel(strategyLabel(mode));
            result.setPersonalized(false);
            result.setTotal(0);
            result.getBasis().add("在售商品为空，无候选集");
            return result;
        }
        result.getBasis().add("在售候选商品 " + candidates.size() + " 件");

        // 排除集合：自己发布的 / 已收藏 / 已下单（所有策略一致，保证对比实验公平）
        Set<Long> excluded = new HashSet<>();
        if (userId != null) {
            candidates.stream().filter(p -> userId.equals(p.getSellerId()))
                    .forEach(p -> excluded.add(p.getId()));
            Set<Long> favorited = favoriteProductIds(userId);
            Set<Long> ordered = orderedProductIds(userId);
            excluded.addAll(favorited);
            excluded.addAll(ordered);
            result.getBasis().add("过滤条件：排除自己发布 "
                    + candidates.stream().filter(p -> userId.equals(p.getSellerId())).count()
                    + " 件、已收藏 " + favorited.size() + " 件、已下单 " + ordered.size() + " 件");
        }

        // 行为样本
        List<UserBehavior> behaviors = (props.isEnabled() && userId != null)
                ? userBehaviorService.listByUser(userId) : List.of();

        // 纯热门策略 / 冷启动（未登录、无行为）→ 热门推荐
        if ("hot".equals(mode)) {
            return hotResult(candidates, excluded, n, userId, "指定策略：纯热门排序（基线）", mode);
        }
        if (behaviors.isEmpty()) {
            return hotResult(candidates, excluded, n, userId,
                    userId == null ? "未登录，按热门推荐冷启动" : "该用户暂无行为记录，按热门推荐冷启动", "hot");
        }

        // ---------- 三路召回打分 ----------
        double[] weights = resolveWeights(mode);
        Map<Long, Double> popularity = loadPopularity();
        double halfLife = Math.max(1.0, props.getHalfLifeDays());

        Map<Long, Double> hotRaw = rawHotScores(candidates, popularity, halfLife);
        CfResult cf = rawCfScores(behaviors, popularity, halfLife);
        Map<Long, Double> contentRaw = rawContentScores(candidates, behaviors, userId, halfLife);

        Map<Long, Double> hotNorm = normalize(hotRaw);
        Map<Long, Double> cfNorm = normalize(cf.scores);
        Map<Long, Double> contentNorm = normalize(contentRaw);

        result.getBasis().add("召回策略：" + strategyLabel(mode));
        result.getBasis().add("行为样本 " + behaviors.size() + " 条（浏览 "
                + countType(behaviors, UserBehaviorService.TYPE_VIEW) + " / 收藏 "
                + countType(behaviors, UserBehaviorService.TYPE_FAVORITE) + " / 私信 "
                + countType(behaviors, UserBehaviorService.TYPE_MESSAGE) + " / 下单 "
                + countType(behaviors, UserBehaviorService.TYPE_ORDER) + "）");
        result.getBasis().add("协同过滤：用户交互过 " + cf.sourceCount + " 件商品，命中相似物品对 "
                + cf.pairs + " 组");
        result.getBasis().add(String.format("融合权重：协同过滤 %.2f / 内容匹配 %.2f / 热门度 %.2f",
                weights[0], weights[1], weights[2]));

        // 仅浏览过（未收藏、未下单）→ 降权
        Set<Long> onlySeen = behaviors.stream()
                .filter(b -> Integer.valueOf(UserBehaviorService.TYPE_VIEW).equals(b.getBehaviorType()))
                .map(UserBehavior::getProductId).collect(Collectors.toSet());

        // ---------- 融合 ----------
        List<Scored> scored = new ArrayList<>();
        for (Product p : candidates) {
            if (excluded.contains(p.getId())) {
                continue;
            }
            double cfScore = cfNorm.getOrDefault(p.getId(), 0.0);
            double contentScore = contentNorm.getOrDefault(p.getId(), 0.0);
            double hotScore = hotNorm.getOrDefault(p.getId(), 0.0);
            double score = weights[0] * cfScore + weights[1] * contentScore + weights[2] * hotScore;
            if (onlySeen.contains(p.getId())) {
                score *= props.getSeenPenalty();
            }
            scored.add(new Scored(p, score, cfScore, contentScore, hotScore));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        // ---------- 多样性截断 + TopN ----------
        List<RecommendItemVO> items = selectWithDiversity(scored, n);

        // ---------- 理由与画像 ----------
        Map<Long, String> categoryNames = loadCategoryNames(items.stream()
                .map(i -> i.getCategoryId()).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> sourceTitles = loadProductTitles(cf.bestSource.values());
        String userCampus = loadUserCampus(userId);
        for (RecommendItemVO item : items) {
            fillReason(item, cf, sourceTitles, categoryNames, userCampus, categoryPrefNames(behaviors));
        }

        result.setStrategy(mode);
        result.setStrategyLabel(strategyLabel(mode));
        result.setPersonalized(true);
        result.setProfileDesc(buildProfileDesc(behaviors, categoryNames, halfLife));
        result.setTotal(items.size());
        result.setItems(items);
        log.info("[推荐] userId={} 策略={} 候选={} 行为={} 相似对={} 返回={} 画像={}",
                userId, mode, candidates.size(), behaviors.size(), cf.pairs, items.size(), result.getProfileDesc());
        return result;
    }

    /** 策略归一化与校验：非法值直接 400 */
    private String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "auto";
        }
        String mode = strategy.trim().toLowerCase();
        // hybrid-cf / hybrid-content 是两组"偏协同 / 偏内容"的融合权重预设，
        // 用于参数敏感性分析（离线实验）与线上 A/B 测试
        if (!List.of("auto", "hot", "content", "cf", "hybrid", "hybrid-cf", "hybrid-content").contains(mode)) {
            throw ProductException.paramIllegal(
                    "strategy 只能为 auto / hot / content / cf / hybrid / hybrid-cf / hybrid-content");
        }
        return mode;
    }

    /** 策略中文名（用于结果与文档） */
    private String strategyLabel(String mode) {
        switch (mode) {
            case "hot":
                return "热门推荐（基线）";
            case "content":
                return "内容匹配召回";
            case "cf":
                return "协同过滤召回";
            case "hybrid":
                return "多路融合（默认权重）";
            case "hybrid-cf":
                return "融合·偏协同过滤";
            case "hybrid-content":
                return "融合·偏内容匹配";
            default:
                return "个性化推荐（自动权重）";
        }
    }

    /**
     * 策略 → 三路权重。
     * <p>{@code auto} 与 {@code hybrid} 使用配置文件里的融合权重；其余为消融/敏感性预设，
     * 用于离线对比实验与线上 A/B 测试。</p>
     */
    private double[] resolveWeights(String mode) {
        switch (mode) {
            case "cf":
                return new double[]{1.0, 0.0, 0.0};
            case "content":
                return new double[]{0.0, 1.0, 0.0};
            case "hot":
                return new double[]{0.0, 0.0, 1.0};
            case "hybrid-cf":
                return new double[]{0.70, 0.20, 0.10};
            case "hybrid-content":
                return new double[]{0.30, 0.60, 0.10};
            default:
                return new double[]{props.getCfWeight(), props.getContentWeight(), props.getHotWeight()};
        }
    }

    @Override
    public RecommendResultVO similar(Long productId, Integer size) {
        int n = normalizeSize(size, props.getSimilarSize());
        Product current = productService.getById(productId);
        if (current == null) {
            throw ProductException.notFound();
        }

        RecommendResultVO result = new RecommendResultVO();
        result.setBasis(new ArrayList<>());
        result.setStrategy("similar");
        result.setStrategyLabel("相似商品");
        result.setPersonalized(false);

        List<Product> candidates = loadCandidates();
        Map<Long, Double> popularity = loadPopularity();
        double halfLife = Math.max(1.0, props.getHalfLifeDays());

        // 只以当前商品为"来源物品"计算共现相似度
        Map<Long, Double> interest = new HashMap<>();
        interest.put(productId, 1.0);
        List<ItemCoOccurrenceDTO> co = userBehaviorMapper.selectCoOccurrence(List.of(productId));
        Map<Long, Double> scores = new HashMap<>();
        Set<Long> candidateIds = candidates.stream().map(Product::getId).collect(Collectors.toSet());
        int pairs = 0;
        int offSale = 0;
        for (ItemCoOccurrenceDTO row : co) {
            if (row.getTargetId().equals(productId)) {
                continue;
            }
            int popSource = Math.max(1, popularity.getOrDefault(row.getSourceId(), 1.0).intValue());
            int popTarget = Math.max(1, popularity.getOrDefault(row.getTargetId(), 1.0).intValue());
            double sim = row.getCoCount() / Math.sqrt((double) popSource * popTarget);
            if (sim < props.getSimilarThreshold()) {
                continue;
            }
            pairs++;
            if (!candidateIds.contains(row.getTargetId())) {
                // 相似但已下架 / 已售出 / 交易中：不参与推荐，仅计数用于说明
                offSale++;
                continue;
            }
            scores.merge(row.getTargetId(), sim, Double::sum);
        }
        Map<Long, Double> simNorm = normalize(scores);
        Map<Long, Double> hotNorm = normalize(rawHotScores(candidates, popularity, halfLife));
        result.getBasis().add("以商品 " + productId + " 为来源，命中相似物品 " + pairs + " 个"
                + "（其中 " + offSale + " 件已下架/售出，不在候选集）");

        List<Scored> scored = new ArrayList<>();
        for (Product p : candidates) {
            if (p.getId().equals(productId)) {
                continue;
            }
            double simScore = simNorm.getOrDefault(p.getId(), 0.0);
            double hotScore = hotNorm.getOrDefault(p.getId(), 0.0);
            // 相似度为主，热门度为辅（相似度缺失时由同分类热门补齐）
            double score = simScore > 0 ? 0.8 * simScore + 0.2 * hotScore : 0.0;
            scored.add(new Scored(p, score, simScore, 0.0, hotScore));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        List<RecommendItemVO> items = selectWithDiversity(scored, n);
        Set<Long> picked = items.stream().map(RecommendItemVO::getProductId).collect(Collectors.toSet());

        // 相似物品不足 → 用"同类目"热门商品补齐（内容兜底）。
        // 同类目的口径：优先同一个二级分类；若当前商品是二级分类，则放宽到同一父分类下的兄弟分类
        // （例如"公共课教材"→"专业课教材"），避免相关推荐整块为空。
        if (items.size() < n) {
            Map<Long, String> acceptable = acceptableCategories(current);
            List<Scored> fallback = scored.stream()
                    .filter(s -> !picked.contains(s.product.getId())
                            && s.product.getCategoryId() != null
                            && acceptable.containsKey(s.product.getCategoryId()))
                    .sorted(Comparator.comparingDouble((Scored s) -> s.hot).reversed())
                    .collect(Collectors.toList());
            for (Scored s : fallback) {
                if (items.size() >= n) {
                    break;
                }
                RecommendItemVO vo = toVO(s);
                vo.setSourceType(2);
                vo.setSourceLabel("同类目热门");
                vo.setReason("同属「" + acceptable.getOrDefault(s.product.getCategoryId(), "相关") + "」的热门商品");
                items.add(vo);
                picked.add(vo.getProductId());
            }
            if (!fallback.isEmpty()) {
                result.getBasis().add("相似物品不足，用同分类热门补齐 " + Math.min(fallback.size(), n) + " 件");
            }
        }

        Map<Long, String> sourceTitles = loadProductTitles(List.of(productId));
        String sourceTitle = sourceTitles.get(productId);
        Map<Long, String> categoryNames = loadCategoryNames(items.stream()
                .map(RecommendItemVO::getCategoryId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        for (RecommendItemVO item : items) {
            if (item.getSourceType() == null) {
                item.setSourceType(1);
                item.setSourceLabel("协同过滤");
            }
            if (item.getSourceType() == 1) {
                item.setReason(sourceTitle == null ? "和当前商品相似"
                        : "和《" + sourceTitle + "》相似");
            }
            List<String> reasons = new ArrayList<>();
            reasons.add(item.getReason());
            if (item.getScore() != null && item.getScore() > 0) {
                reasons.add(String.format("相似度得分 %.2f", item.getScore()));
            }
            item.setReasons(reasons);
            if (categoryNames.containsKey(item.getCategoryId())) {
                item.setCategoryName(categoryNames.get(item.getCategoryId()));
            }
        }

        result.setTotal(items.size());
        result.setItems(items);
        log.info("[相似商品] productId={} 相似对={} 返回={}", productId, pairs, items.size());
        return result;
    }

    // ------------------------------------------------------------------ 召回打分

    /** 热门度原始分：浏览量 + 收藏量 + 行为人数（对数压缩），再乘时间新鲜度 */
    private Map<Long, Double> rawHotScores(List<Product> candidates, Map<Long, Double> popularity, double halfLife) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Product p : candidates) {
            double view = Math.log1p(p.getViewCount() == null ? 0 : p.getViewCount());
            double fav = Math.log1p(p.getFavoriteCount() == null ? 0 : p.getFavoriteCount());
            double pop = Math.log1p(popularity.getOrDefault(p.getId(), 0.0));
            double fresh = timeDecay(p.getCreateTime(), halfLife);
            scores.put(p.getId(), (0.50 * view + 0.30 * fav + 0.20 * pop) * (0.70 + 0.30 * fresh));
        }
        return scores;
    }

    /** Item-CF 原始分：score(j) = Σ_i interest(i) · sim(i, j) */
    private CfResult rawCfScores(List<UserBehavior> behaviors, Map<Long, Double> popularity, double halfLife) {
        CfResult result = new CfResult();
        Map<Long, Double> interest = new HashMap<>();
        for (UserBehavior b : behaviors) {
            double decay = timeDecay(b.getUpdateTime() == null ? b.getCreateTime() : b.getUpdateTime(), halfLife);
            double count = Math.sqrt(Math.max(1, b.getBehaviorCount() == null ? 1 : b.getBehaviorCount()));
            double value = (b.getWeight() == null ? 1.0 : b.getWeight().doubleValue()) * count * decay;
            interest.merge(b.getProductId(), value, Double::sum);
        }
        result.sourceCount = interest.size();
        if (interest.isEmpty()) {
            return result;
        }

        List<ItemCoOccurrenceDTO> rows = userBehaviorMapper.selectCoOccurrence(interest.keySet());
        for (ItemCoOccurrenceDTO row : rows) {
            if (row.getSourceId() == null || row.getTargetId() == null
                    || row.getSourceId().equals(row.getTargetId())) {
                continue;
            }
            int popSource = Math.max(1, popularity.getOrDefault(row.getSourceId(), 1.0).intValue());
            int popTarget = Math.max(1, popularity.getOrDefault(row.getTargetId(), 1.0).intValue());
            double sim = row.getCoCount() / Math.sqrt((double) popSource * popTarget);
            if (sim < props.getSimilarThreshold()) {
                continue;
            }
            double contribution = interest.getOrDefault(row.getSourceId(), 0.0) * sim;
            if (contribution <= 0) {
                continue;
            }
            result.pairs++;
            result.scores.merge(row.getTargetId(), contribution, Double::sum);
            if (contribution > result.bestContribution.getOrDefault(row.getTargetId(), 0.0)) {
                result.bestContribution.put(row.getTargetId(), contribution);
                result.bestSource.put(row.getTargetId(), row.getSourceId());
            }
        }
        return result;
    }

    /** 内容匹配原始分：0.55×分类偏好 + 0.30×价格相近度 + 0.15×校区一致（+校区加成） */
    private Map<Long, Double> rawContentScores(List<Product> candidates, List<UserBehavior> behaviors,
                                               Long userId, double halfLife) {
        Map<Long, Double> categoryPref = new HashMap<>();
        double weightedPriceSum = 0.0;
        double weightSum = 0.0;
        List<Double> prices = new ArrayList<>();
        Set<Long> productIds = behaviors.stream().map(UserBehavior::getProductId).collect(Collectors.toSet());
        Map<Long, Product> historyProducts = productIds.isEmpty() ? Map.of()
                : productService.listByIds(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));

        for (UserBehavior b : behaviors) {
            double decay = timeDecay(b.getUpdateTime() == null ? b.getCreateTime() : b.getUpdateTime(), halfLife);
            double value = (b.getWeight() == null ? 1.0 : b.getWeight().doubleValue()) * decay;
            if (b.getCategoryId() != null) {
                categoryPref.merge(b.getCategoryId(), value, Double::sum);
            }
            Product hp = historyProducts.get(b.getProductId());
            if (hp != null && hp.getPrice() != null) {
                weightedPriceSum += value * hp.getPrice().doubleValue();
                weightSum += value;
                prices.add(hp.getPrice().doubleValue());
            }
        }

        double maxCategoryPref = categoryPref.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        // 价格偏好：加权均值 + 标准差（样本不足时用全局均价与固定尺度兜底）
        double avgPrice = weightSum > 0 ? weightedPriceSum / weightSum
                : candidates.stream().filter(p -> p.getPrice() != null)
                        .mapToDouble(p -> p.getPrice().doubleValue()).average().orElse(50.0);
        double variance = 0.0;
        if (weightSum > 0) {
            for (Product hp : historyProducts.values()) {
                if (hp.getPrice() != null) {
                    variance += Math.pow(hp.getPrice().doubleValue() - avgPrice, 2);
                }
            }
            variance /= Math.max(1, historyProducts.size());
        }
        double sigma = Math.max(5.0, Math.sqrt(variance));
        String userCampus = loadUserCampus(userId);

        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Product p : candidates) {
            double categoryScore = maxCategoryPref > 0 && p.getCategoryId() != null
                    ? categoryPref.getOrDefault(p.getCategoryId(), 0.0) / maxCategoryPref : 0.0;
            double price = p.getPrice() == null ? avgPrice : p.getPrice().doubleValue();
            double priceScore = Math.exp(-Math.abs(price - avgPrice) / (sigma + 1.0));
            double campusScore = (userCampus != null && userCampus.equals(p.getCampus())) ? 1.0 : 0.0;
            double score = 0.55 * categoryScore + 0.30 * priceScore + 0.15 * campusScore;
            if (campusScore > 0) {
                score = Math.min(1.0, score + props.getCampusBonus());
            }
            scores.put(p.getId(), score);
        }
        return scores;
    }

    // ------------------------------------------------------------------ 结果组装

    /** 多样性截断：同一分类最多 categoryCap 条，不足部分再用溢出项补齐 */
    private List<RecommendItemVO> selectWithDiversity(List<Scored> sorted, int n) {
        int cap = Math.max(1, props.getCategoryCap());
        Map<Long, Integer> categoryCount = new HashMap<>();
        List<Scored> overflow = new ArrayList<>();
        List<RecommendItemVO> items = new ArrayList<>();
        for (Scored s : sorted) {
            if (s.score <= 0) {
                continue;
            }
            Long categoryId = s.product.getCategoryId();
            int used = categoryCount.getOrDefault(categoryId, 0);
            if (used < cap) {
                categoryCount.put(categoryId, used + 1);
                items.add(toVO(s));
                if (items.size() >= n) {
                    return items;
                }
            } else {
                overflow.add(s);
            }
        }
        for (Scored s : overflow) {
            if (items.size() >= n) {
                break;
            }
            items.add(toVO(s));
        }
        return items;
    }

    private RecommendItemVO toVO(Scored s) {
        Product p = s.product;
        RecommendItemVO vo = new RecommendItemVO();
        vo.setProductId(p.getId());
        vo.setTitle(p.getTitle());
        vo.setPrice(p.getPrice());
        vo.setOriginalPrice(p.getOriginalPrice());
        vo.setCoverImage(p.getCoverImage());
        vo.setCategoryId(p.getCategoryId());
        vo.setSellerId(p.getSellerId());
        vo.setConditionLevel(p.getConditionLevel());
        vo.setCampus(p.getCampus());
        vo.setStatus(p.getStatus());
        vo.setViewCount(p.getViewCount());
        vo.setFavoriteCount(p.getFavoriteCount());
        vo.setCreateTime(p.getCreateTime());
        vo.setScore(round(s.score));
        vo.setCfScore(round(s.cf));
        vo.setContentScore(round(s.content));
        vo.setHotScore(round(s.hot));
        return vo;
    }

    /** 生成推荐理由与来源标签（可解释性） */
    private void fillReason(RecommendItemVO item, CfResult cf, Map<Long, String> sourceTitles,
                            Map<Long, String> categoryNames, String userCampus, List<String> prefCategoryNames) {
        List<String> reasons = new ArrayList<>();
        double cfScore = item.getCfScore() == null ? 0 : item.getCfScore();
        double contentScore = item.getContentScore() == null ? 0 : item.getContentScore();
        double hotScore = item.getHotScore() == null ? 0 : item.getHotScore();
        double max = Math.max(cfScore, Math.max(contentScore, hotScore));

        if (max <= 0.05) {
            item.setSourceType(3);
            item.setSourceLabel("热门推荐");
            item.setReason("近期热门商品");
            reasons.add("热度高：浏览量 " + item.getViewCount() + "、收藏 " + item.getFavoriteCount());
        } else if (scoreGap(cfScore, contentScore) < 0.10 && scoreGap(cfScore, hotScore) < 0.10) {
            item.setSourceType(4);
            item.setSourceLabel("混合召回");
            item.setReason("综合你的浏览与收藏偏好推荐");
            reasons.add(String.format("协同 %.2f + 内容 %.2f + 热门 %.2f", cfScore, contentScore, hotScore));
        } else if (cfScore >= contentScore && cfScore >= hotScore) {
            item.setSourceType(1);
            item.setSourceLabel("协同过滤");
            Long sourceId = cf.bestSource.get(item.getProductId());
            String sourceTitle = sourceId == null ? null : sourceTitles.get(sourceId);
            item.setReason(sourceTitle == null ? "和你关注过的商品相似"
                    : "和你关注过的《" + truncate(sourceTitle) + "》相似");
            reasons.add("物品协同过滤：共现相似度命中");
        } else if (contentScore >= hotScore) {
            item.setSourceType(2);
            item.setSourceLabel("内容匹配");
            String categoryName = categoryNames.get(item.getCategoryId());
            item.setReason(categoryName != null ? "你常看「" + categoryName + "」分类"
                    : "符合你的价格偏好");
            reasons.add("内容匹配：分类 / 价格偏好命中");
        } else {
            item.setSourceType(3);
            item.setSourceLabel("热门推荐");
            item.setReason("近期热门商品");
            reasons.add("热门度召回");
        }

        if (userCampus != null && userCampus.equals(item.getCampus())) {
            reasons.add("同校区（" + userCampus + "）");
        }
        if (item.getCreateTime() != null
                && Duration.between(item.getCreateTime(), LocalDateTime.now()).toDays() <= 3) {
            reasons.add("最近 3 天新发布");
        }
        if (item.getFavoriteCount() != null && item.getFavoriteCount() > 0) {
            reasons.add(item.getFavoriteCount() + " 人收藏");
        }
        if (!prefCategoryNames.isEmpty()) {
            reasons.add("你的兴趣分类：" + String.join("、", prefCategoryNames));
        }
        item.setReasons(reasons);
    }

    private RecommendResultVO hotResult(List<Product> candidates, Set<Long> excluded, int n, Long userId,
                                        String why, String mode) {
        RecommendResultVO result = new RecommendResultVO();
        result.setBasis(new ArrayList<>());
        result.setStrategy(mode);
        result.setStrategyLabel(strategyLabel(mode));
        result.setPersonalized(false);
        result.getBasis().add(why);
        result.getBasis().add("排序依据：浏览量 + 收藏量 + 行为人数 + 时间新鲜度");
        if (userId != null && !excluded.isEmpty()) {
            result.getBasis().add("已排除自己发布 / 已收藏 / 已下单的商品 " + excluded.size() + " 件");
        }

        Map<Long, Double> popularity = loadPopularity();
        double halfLife = Math.max(1.0, props.getHalfLifeDays());
        Map<Long, Double> hotRaw = rawHotScores(candidates, popularity, halfLife);
        List<Scored> scored = new ArrayList<>();
        for (Product p : candidates) {
            if (excluded.contains(p.getId())) {
                continue;
            }
            scored.add(new Scored(p, hotRaw.getOrDefault(p.getId(), 0.0), 0.0, 0.0,
                    hotRaw.getOrDefault(p.getId(), 0.0)));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        int limit = userId == null ? Math.max(n, props.getColdStartSize()) : n;
        List<RecommendItemVO> items = selectWithDiversity(scored, Math.min(limit, props.getMaxSize()));
        Map<Long, String> categoryNames = loadCategoryNames(items.stream()
                .map(RecommendItemVO::getCategoryId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        String campus = loadUserCampus(userId);
        for (RecommendItemVO item : items) {
            item.setCategoryName(categoryNames.get(item.getCategoryId()));
            item.setSourceType(3);
            item.setSourceLabel("热门推荐");
            List<String> reasons = new ArrayList<>();
            item.setReason(campus != null && campus.equals(item.getCampus()) ? "同校区热门商品" : "近期热门商品");
            reasons.add(item.getReason());
            reasons.add("浏览 " + item.getViewCount() + " · 收藏 " + item.getFavoriteCount());
            item.setReasons(reasons);
        }
        result.setTotal(items.size());
        result.setItems(items);
        return result;
    }

    private String buildProfileDesc(List<UserBehavior> behaviors, Map<Long, String> fallbackNames, double halfLife) {
        Map<Long, Double> categoryPref = new HashMap<>();
        List<Product> history = productService.listByIds(behaviors.stream()
                .map(UserBehavior::getProductId).collect(Collectors.toSet()));
        Map<Long, Product> byId = history.stream().collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        double min = Double.MAX_VALUE;
        double max = 0;
        for (UserBehavior b : behaviors) {
            double decay = timeDecay(b.getUpdateTime() == null ? b.getCreateTime() : b.getUpdateTime(), halfLife);
            double value = (b.getWeight() == null ? 1.0 : b.getWeight().doubleValue()) * decay;
            if (b.getCategoryId() != null) {
                categoryPref.merge(b.getCategoryId(), value, Double::sum);
            }
            Product p = byId.get(b.getProductId());
            if (p != null && p.getPrice() != null) {
                double price = p.getPrice().doubleValue();
                min = Math.min(min, price);
                max = Math.max(max, price);
            }
        }
        List<Long> topCategories = categoryPref.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(2).map(Map.Entry::getKey).collect(Collectors.toList());
        Map<Long, String> names = topCategories.isEmpty() ? Map.of() : loadCategoryNames(new HashSet<>(topCategories));
        List<String> nameList = topCategories.stream().map(names::get)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        if (!nameList.isEmpty()) {
            sb.append("常看：").append(String.join("、", nameList));
        }
        if (min != Double.MAX_VALUE) {
            sb.append(sb.length() > 0 ? "；" : "").append(String.format("价位 ￥%.0f~%.0f", min, max));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------ 工具方法

    /** 在售候选商品（按热度排序后截断，避免全表参与精排） */
    private List<Product> loadCandidates() {
        Page<Product> page = productService.page(new Page<>(1, Math.max(1, props.getCandidatePoolSize()), false),
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getStatus, STATUS_ON_SALE)
                        .orderByDesc(Product::getViewCount)
                        .orderByDesc(Product::getId));
        return page.getRecords();
    }

    /** 商品 -> 产生过行为的去重用户数 */
    private Map<Long, Double> loadPopularity() {
        Map<Long, Double> map = new HashMap<>();
        for (ProductPopularityDTO row : userBehaviorMapper.selectPopularity()) {
            map.put(row.getProductId(), row.getUserCount() == null ? 0.0 : row.getUserCount().doubleValue());
        }
        return map;
    }

    private Set<Long> favoriteProductIds(Long userId) {
        return favoriteService.list(new LambdaQueryWrapper<Favorite>().eq(Favorite::getUserId, userId))
                .stream().map(Favorite::getProductId).collect(Collectors.toSet());
    }

    private Set<Long> orderedProductIds(Long userId) {
        return orderService.list(new LambdaQueryWrapper<Order>()
                        .eq(Order::getBuyerId, userId)
                        .in(Order::getStatus, ORDER_PENDING, ORDER_FINISHED))
                .stream().map(Order::getProductId).collect(Collectors.toSet());
    }

    /**
     * "同类目"可接受分类集合（分类ID -> 分类名），用于相关推荐的内容兜底。
     *
     * <p>规则：</p>
     * <ol>
     *   <li>包含自身分类；</li>
     *   <li>若自身是二级分类 → 放宽到<b>同一父分类下的兄弟分类</b>（如"公共课教材"↔"专业课教材"）；</li>
     *   <li>若自身是一级分类 → 放宽到其<b>所有子分类</b>。</li>
     * </ol>
     * <p>不这样放宽的话，稀疏数据下"相关推荐"很容易整块为空，属于体验缺陷。</p>
     */
    private Map<Long, String> acceptableCategories(Product current) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (current.getCategoryId() == null) {
            return map;
        }
        Category self = categoryService.getById(current.getCategoryId());
        if (self == null) {
            return map;
        }
        map.put(self.getId(), self.getName());
        Long parentId = self.getParentId();
        if (parentId != null && parentId != 0) {
            Category parent = categoryService.getById(parentId);
            if (parent != null) {
                map.put(parent.getId(), parent.getName());
            }
            categoryService.list(new LambdaQueryWrapper<Category>()
                            .eq(Category::getParentId, parentId))
                    .forEach(c -> map.put(c.getId(), c.getName()));
        } else {
            categoryService.list(new LambdaQueryWrapper<Category>()
                            .eq(Category::getParentId, self.getId()))
                    .forEach(c -> map.put(c.getId(), c.getName()));
        }
        return map;
    }

    private Map<Long, String> loadCategoryNames(Set<Long> ids) {        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return categoryService.listByIds(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
    }

    private Map<Long, String> loadProductTitles(java.util.Collection<Long> ids) {
        Set<Long> set = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (set.isEmpty()) {
            return Map.of();
        }
        return productService.listByIds(set).stream()
                .collect(Collectors.toMap(Product::getId, Product::getTitle, (a, b) -> a));
    }

    private String loadUserCampus(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userService.getById(userId);
        return user == null ? null : user.getCampus();
    }

    private List<String> categoryPrefNames(List<UserBehavior> behaviors) {
        Set<Long> ids = behaviors.stream().map(UserBehavior::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return List.of();
        }
        return loadCategoryNames(ids).values().stream().limit(2).collect(Collectors.toList());
    }

    private long countType(List<UserBehavior> behaviors, int type) {
        return behaviors.stream().filter(b -> Integer.valueOf(type).equals(b.getBehaviorType())).count();
    }

    /** 时间衰减：0.5 ^ (距今天数 / 半衰期)，下限 0.05 */
    private double timeDecay(LocalDateTime time, double halfLifeDays) {
        if (time == null) {
            return 0.5;
        }
        double days = Math.max(0, Duration.between(time, LocalDateTime.now()).toMinutes() / 1440.0);
        return Math.max(0.05, Math.pow(0.5, days / halfLifeDays));
    }

    /** min-max 归一化到 [0,1]；全部相等时按"有值即 1"处理 */
    private Map<Long, Double> normalize(Map<Long, Double> raw) {
        Map<Long, Double> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        double min = raw.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = raw.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double range = max - min;
        for (Map.Entry<Long, Double> e : raw.entrySet()) {
            double v;
            if (range < 1e-9) {
                v = max > 0 ? 1.0 : 0.0;
            } else {
                v = (e.getValue() - min) / range;
            }
            out.put(e.getKey(), Math.max(0.0, Math.min(1.0, v)));
        }
        return out;
    }

    private int normalizeSize(Integer size, int fallback) {
        int n = size == null || size <= 0 ? fallback : size;
        return Math.min(n, Math.max(1, props.getMaxSize()));
    }

    private double scoreGap(double a, double b) {
        return Math.abs(a - b);
    }

    private Double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private String truncate(String s) {
        return s.length() > 14 ? s.substring(0, 14) + "…" : s;
    }

    /** 单次推荐内部的中间结果 */
    private static class Scored {
        final Product product;
        final double score;
        final double cf;
        final double content;
        final double hot;

        Scored(Product product, double score, double cf, double content, double hot) {
            this.product = product;
            this.score = score;
            this.cf = cf;
            this.content = content;
            this.hot = hot;
        }
    }

    /** 协同过滤中间结果 */
    private static class CfResult {
        final Map<Long, Double> scores = new HashMap<>();
        final Map<Long, Long> bestSource = new HashMap<>();
        final Map<Long, Double> bestContribution = new HashMap<>();
        int sourceCount = 0;
        int pairs = 0;
    }
}
