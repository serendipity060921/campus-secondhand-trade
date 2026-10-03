package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.ProductPublishDTO;
import com.campus.trade.dto.ProductQueryDTO;
import com.campus.trade.entity.Category;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.ProductImage;
import com.campus.trade.entity.User;
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.ProductImageService;
import com.campus.trade.service.ProductModuleService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserBehaviorService;
import com.campus.trade.common.cache.CacheEvictor;
import com.campus.trade.common.cache.CacheKeys;
import com.campus.trade.config.CacheProperties;
import com.campus.trade.service.CacheService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.ProductDetailVO;
import com.campus.trade.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品核心业务实现（v0.05）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductModuleServiceImpl implements ProductModuleService {

    /** 商品状态常量（与 db_schema.sql 中 product.status 注释一致） */
    private static final int STATUS_PENDING = 0;      // 待审核
    private static final int STATUS_ON_SALE = 1;      // 在售（上架）
    private static final int STATUS_OFF_SHELF = 3;    // 已下架
    private static final int STATUS_TRADING = 4;      // 交易中
    private static final int STATUS_SOLD = 5;         // 已售出
    /** 单个商品最多图片数 */
    private static final int MAX_IMAGE_COUNT = 9;

    private final ProductService productService;
    private final ProductImageService productImageService;
    private final CategoryService categoryService;
    private final UserService userService;
    /** v0.11 推荐模块：行为埋点 */
    private final UserBehaviorService userBehaviorService;
    /** v0.12：缓存（列表 / 详情）、统一失效入口与缓存参数 */
    private final CacheService cacheService;
    private final CacheKeys cacheKeys;
    private final CacheEvictor cacheEvictor;
    private final CacheProperties cacheProperties;

    /* ==================== 1. 发布商品 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductDetailVO publish(ProductPublishDTO dto, Long sellerId) {
        // ① 分类必须存在（数据库有外键，这里先给出友好提示）
        Category category = categoryService.getById(dto.getCategoryId());
        if (category == null) {
            throw ProductException.categoryNotFound();
        }

        List<String> imageUrls = dto.getImageUrls() == null ? Collections.emptyList() : dto.getImageUrls();

        // ② 组装商品
        Product product = new Product();
        product.setTitle(dto.getTitle().trim());
        product.setDescription(dto.getDescription());
        product.setCategoryId(dto.getCategoryId());
        product.setSellerId(sellerId);
        product.setPrice(dto.getPrice());
        product.setOriginalPrice(dto.getOriginalPrice());
        product.setConditionLevel(dto.getConditionLevel());
        product.setCampus(dto.getCampus());
        product.setTradePlace(dto.getTradePlace());
        // v0.05 直接上架；等后台审核里程碑（v0.07）上线后，这里改成 STATUS_PENDING(0) 即可
        product.setStatus(STATUS_ON_SALE);
        product.setViewCount(0);
        product.setFavoriteCount(0);
        product.setShelfTime(LocalDateTime.now());
        product.setCoverImage(imageUrls.isEmpty() ? null : imageUrls.get(0));
        product.setDeleted(0);

        productService.save(product);

        // ③ 图片信息写入 product_image 表
        if (!imageUrls.isEmpty()) {
            saveImages(product.getId(), imageUrls, 0);
        }

        log.info("[商品发布] id={} title={} sellerId={} 图片数={}",
                product.getId(), product.getTitle(), sellerId, imageUrls.size());
        // v0.12 缓存失效：新商品上架会影响商品列表与推荐结果
        cacheEvictor.productChanged(product.getId());
        return toDetailVO(product, category, false);
    }

    /* ==================== 2. 商品分页列表 ==================== */

    @Override
    public PageResult<ProductVO> pageList(ProductQueryDTO query) {
        // v0.12：列表按"查询条件指纹"缓存（首页 QPS 最高、且同一批条件重复查询多）
        String cacheKey = cacheKeys.productList(cacheKeys.conditionOf(cacheKeys.params(
                "page", query.getPage(), "size", query.getSize(), "categoryId", query.getCategoryId(),
                "keyword", query.getKeyword(), "minPrice", query.getMinPrice(),
                "maxPrice", query.getMaxPrice(), "sort", query.getSort())));
        if (cacheService.exists(cacheKey)) {
            PageResult<ProductVO> cached = cacheService.get(cacheKey, PageResult.class);
            if (cached != null) {
                log.debug("[缓存命中] 商品列表 key={}", cacheKey);
                return cached;
            }
        }

        LambdaQueryWrapper<Product> wrapper = buildBaseWrapper(query);
        // 列表只返回上架中的商品
        wrapper.eq(Product::getStatus, STATUS_ON_SALE);

        Page<Product> page = productService.page(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        PageResult<ProductVO> result = new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(),
                page.getSize(), toVOList(page.getRecords()));
        cacheService.set(cacheKey, result, cacheProperties.getProductListTtl());
        return result;
    }

    @Override
    public PageResult<ProductVO> pageMine(ProductQueryDTO query, Long sellerId) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getSellerId, sellerId)
                .eq(query.getStatus() != null, Product::getStatus, query.getStatus())
                .orderByDesc(Product::getCreateTime);

        Page<Product> page = productService.page(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                toVOList(page.getRecords()));
    }

    /* ==================== 3. 商品详情 ==================== */

    @Override
    public ProductDetailVO detail(Long productId) {
        // 浏览量统计（无论是否命中缓存都要计）——v0.12 改为 Redis 去重 + 计数，见 recordViewStat
        recordViewStat(productId);

        String cacheKey = cacheKeys.productDetail(productId);
        // ① 缓存命中（Cache-Aside 的第一步）
        if (cacheService.exists(cacheKey)) {
            ProductDetailVO cached = cacheService.get(cacheKey, ProductDetailVO.class);
            if (cached != null) {
                log.debug("[缓存命中] 商品详情 id={}", productId);
                // v0.12 缺陷修复：行为埋点必须放在缓存命中路径上，否则重复浏览学不到（v0.11 用例 R17）
                recordBehaviorQuietly(cached.getSellerId(), productId);
                return cached;
            }
            // 命中的是"空值缓存"：说明该商品确实不存在（防穿透），直接按不存在处理
            log.debug("[缓存命中-空值] 商品详情 id={}", productId);
            throw ProductException.notFound();
        }

        // ② 缓存未命中：用分布式锁保证同一热点商品只有一个线程回源（防击穿）
        String lockKey = cacheKey + ":lock";
        String lockValue = java.util.UUID.randomUUID().toString();
        if (!cacheService.tryLock(lockKey, lockValue, cacheProperties.getLockTtl())) {
            // 没抢到锁：短暂等待后重试读缓存（其他线程正在回源）
            try {
                Thread.sleep(60);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ProductDetailVO waited = cacheService.get(cacheKey, ProductDetailVO.class);
            if (waited != null) {
                log.debug("[缓存击穿保护] 等待后命中商品详情 id={}", productId);
                recordBehaviorQuietly(waited.getSellerId(), productId);
                return waited;
            }
            // 仍未命中：退化为直接查库，不阻塞用户
            return queryDetailAndCache(productId, null);
        }
        try {
            return queryDetailAndCache(productId, cacheKey);
        } finally {
            cacheService.releaseLock(lockKey, lockValue);
        }
    }

    /**
     * v0.11 行为埋点（推荐算法数据源）。
     *
     * <p>三条纪律：① 未登录不记录；② 卖家浏览自己的商品不计入兴趣，避免自我强化；
     * ③ 埋点失败绝不能影响商品详情正常返回，因此统一吞掉异常。
     * 取用户一律用可空的 {@code UserContext.get()}，{@code getUserId()} 在匿名时会抛 401。</p>
     */
    private void recordBehaviorQuietly(Long sellerId, Long productId) {
        LoginUser viewer = UserContext.get();
        if (viewer == null || Objects.equals(viewer.userId(), sellerId)) {
            return;
        }
        try {
            userBehaviorService.record(viewer.userId(), productId, UserBehaviorService.TYPE_VIEW);
        } catch (Exception e) {
            log.warn("[行为埋点失败] 浏览 userId={} productId={} 原因={}",
                    viewer.userId(), productId, e.getMessage());
        }
    }

    /**
     * 回源查询商品详情并写缓存（v0.12）。
     *
     * @param cacheKey 需要写入缓存的 Key；为 null 表示只查库不写缓存（等待锁超时的降级路径）
     */
    private ProductDetailVO queryDetailAndCache(Long productId, String cacheKey) {
        Product product = productService.getById(productId);
        if (product == null) {
            if (cacheKey != null) {
                cacheService.setNull(cacheKey, cacheProperties.getNullValueTtl());   // 防穿透
            }
            throw ProductException.notFound();
        }

        // v0.10 缺陷修复（BUG-02）：待审核(0) 商品只有卖家本人和管理员可见，
        // 其他人按"商品不存在"处理，避免未审核内容提前外泄。
        // 公开接口的当前登录用户由 LoginInterceptor 的可选鉴权写入（未登录则为 null）。
        LoginUser current = UserContext.get();
        if (Integer.valueOf(STATUS_PENDING).equals(product.getStatus())) {
            boolean isOwner = current != null && Objects.equals(current.userId(), product.getSellerId());
            boolean isAdmin = current != null && current.isAdmin();
            if (!isOwner && !isAdmin) {
                log.info("[商品详情] 待审核商品对外隐藏 productId={} 访问者={}", productId,
                        current == null ? "匿名" : current.userId());
                // v0.12 缺陷修复：这里**不能**写空值缓存！
                // "待审核商品是否可见"取决于访问者身份（卖家本人/管理员可见），
                // 若把对该访问者的"不可见"缓存起来，卖家和管理员随后也会被误判为 3001。
                // 空值缓存只用于"商品确实不存在"的情况（见上面的 product == null 分支）。
                throw ProductException.notFound();
            }
        }

        Category category = product.getCategoryId() == null ? null : categoryService.getById(product.getCategoryId());
        ProductDetailVO vo = toDetailVO(product, category, true);

        // 缓存时把浏览量口径统一为"数据库值 + Redis 待回写值"，避免缓存里的浏览量长期不动。
        //
        // v0.12 缺陷修复（关键）：**待审核(status=0) 商品不写缓存**。
        // 原因：待审核商品的"可见性"取决于访问者身份（卖家本人/管理员可见，其他人 3001），
        // 而缓存 Key 只按商品 ID 维度划分、不含身份信息 ——
        // 一旦把卖家视角的详情写入公共缓存，匿名用户随后也会命中并看到未审核内容。
        // 结论：只缓存"与访问者身份无关"的数据，这是缓存设计的一条重要纪律。
        if (cacheKey != null && !Integer.valueOf(STATUS_PENDING).equals(product.getStatus())) {
            long pending = cacheService.getCounter(cacheKeys.viewCounter(productId));
            if (pending > 0 && vo.getViewCount() != null) {
                vo.setViewCount(vo.getViewCount() + (int) pending);
            }
            cacheService.set(cacheKey, vo, cacheProperties.getProductDetailTtl());
        }

        // v0.11 行为埋点（缓存命中路径也会调用，见 detail 方法）
        recordBehaviorQuietly(product.getSellerId(), productId);
        return vo;
    }

    /**
     * 浏览量统计（v0.12）：Redis 去重 + 计数，达到阈值再批量回写数据库。
     *
     * <p>改造前：每次访问详情都执行 {@code UPDATE product SET view_count = view_count + 1}，
     * 热点商品会把数据库写压力放大；改造后：</p>
     * <ol>
     *   <li><b>去重</b>：同一访问者（登录用户或 IP）在 N 秒内重复访问只计一次，
     *       抑制"刷新刷浏览量"；</li>
     *   <li><b>计数</b>：去重通过后在 Redis 累加，达到阈值（默认 20）再一次性写库，
     *       把 N 次写库压成 1 次；</li>
     *   <li><b>兜底</b>：Redis 不可用时直接写库，保证浏览量不丢。</li>
     * </ol>
     */
    private void recordViewStat(Long productId) {
        if (!cacheService.enabled()) {
            productService.update(new LambdaUpdateWrapper<Product>()
                    .setSql("view_count = view_count + 1")
                    .eq(Product::getId, productId));
            return;
        }
        LoginUser viewer = UserContext.get();
        String visitor = viewer != null ? "u" + viewer.userId() : "ip" + clientIp();
        String dedupKey = cacheKeys.viewDedup(productId, visitor);
        boolean first = cacheService.tryLock(dedupKey, "1", cacheProperties.getViewDedupSeconds());
        if (!first) {
            return;      // 窗口内重复访问，不计数
        }
        String counterKey = cacheKeys.viewCounter(productId);
        long pending = cacheService.increment(counterKey, 3600);
        if (pending >= cacheProperties.getViewFlushThreshold()) {
            // 达到阈值：一次性回写数据库并清零计数
            productService.update(new LambdaUpdateWrapper<Product>()
                    .setSql("view_count = view_count + " + pending)
                    .eq(Product::getId, productId));
            cacheService.resetCounter(counterKey);
            log.debug("[浏览量回写] productId={} 回写 {} 次", productId, pending);
        }
    }

    /** 取客户端 IP（用于浏览量去重维度） */
    private String clientIp() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attributes =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "unknown";
            }
            jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /* ==================== 4. 上下架 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long productId, Integer status, Long userId) {
        // ① 参数非法：只允许 1(上架) / 3(下架)
        if (status == null || (status != STATUS_ON_SALE && status != STATUS_OFF_SHELF)) {
            throw ProductException.paramIllegal("status 只能为 1（上架）或 3（下架）");
        }

        // ② 商品必须存在
        Product product = productService.getById(productId);
        if (product == null) {
            throw ProductException.notFound();
        }

        // ③ 只能操作自己发布的商品
        if (!Objects.equals(product.getSellerId(), userId)) {
            log.warn("[上下架失败] 越权操作 productId={} ownerId={} currentUserId={}",
                    productId, product.getSellerId(), userId);
            throw ProductException.noPermission();
        }

        // ④ 交易中 / 已售出的商品不允许再上下架
        Integer current = product.getStatus();
        if (current != null && (current == STATUS_TRADING || current == STATUS_SOLD)) {
            throw ProductException.statusIllegal();
        }
        if (Objects.equals(current, status)) {
            log.info("[上下架] 状态未变化，忽略 productId={} status={}", productId, status);
            return;
        }

        // ⑤ 更新状态（上架时刷新上架时间）
        Product update = new Product();
        update.setId(productId);
        update.setStatus(status);
        if (status == STATUS_ON_SALE) {
            update.setShelfTime(LocalDateTime.now());
        }
        productService.updateById(update);
        log.info("[上下架成功] productId={} {} -> {} 操作人={}", productId, current, status, userId);
        // v0.12 缓存失效：上下架会改变列表可见性与详情状态
        cacheEvictor.productChanged(productId);
    }

    /* ==================== 5. 图片入库 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addImages(Long productId, List<String> urls, Long userId) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        Product product = productService.getById(productId);
        if (product == null) {
            throw ProductException.notFound();
        }
        if (!Objects.equals(product.getSellerId(), userId)) {
            throw ProductException.noPermission();
        }

        long exists = productImageService.count(new LambdaQueryWrapper<ProductImage>()
                .eq(ProductImage::getProductId, productId));
        if (exists + urls.size() > MAX_IMAGE_COUNT) {
            throw ProductException.imageCountExceeded();
        }

        saveImages(productId, urls, (int) exists);

        // 没有封面图时用第一张补上
        if (!StringUtils.hasText(product.getCoverImage())) {
            Product update = new Product();
            update.setId(productId);
            update.setCoverImage(urls.get(0));
            productService.updateById(update);
        }
        log.info("[商品图片入库] productId={} 新增 {} 张", productId, urls.size());
        // v0.12 缓存失效：详情里的图片列表变了
        cacheEvictor.productChanged(productId);
    }

    /* ==================== 内部方法 ==================== */

    /** 保存图片记录（sortOrder 依次递增） */
    private void saveImages(Long productId, List<String> urls, int startOrder) {
        List<ProductImage> images = new ArrayList<>();
        int order = startOrder;
        for (String url : urls) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            ProductImage image = new ProductImage();
            image.setProductId(productId);
            image.setUrl(url);
            image.setSortOrder(order++);
            image.setDeleted(0);
            images.add(image);
        }
        if (!images.isEmpty()) {
            productImageService.saveBatch(images);
        }
    }

    /**
     * 组装列表查询条件。
     *
     * <p>categoryId 若是一级分类，会自动把它的子分类一起纳入查询。</p>
     */
    private LambdaQueryWrapper<Product> buildBaseWrapper(ProductQueryDTO query) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        // 分类（含子分类）
        if (query.getCategoryId() != null) {
            Set<Long> categoryIds = new LinkedHashSet<>();
            categoryIds.add(query.getCategoryId());
            List<Category> children = categoryService.list(new LambdaQueryWrapper<Category>()
                    .eq(Category::getParentId, query.getCategoryId()));
            children.forEach(c -> categoryIds.add(c.getId()));
            wrapper.in(Product::getCategoryId, categoryIds);
        }

        // 关键词：匹配名称或描述
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(Product::getTitle, keyword)
                    .or().like(Product::getDescription, keyword));
        }

        // 价格区间
        wrapper.ge(query.getMinPrice() != null, Product::getPrice, query.getMinPrice());
        wrapper.le(query.getMaxPrice() != null, Product::getPrice, query.getMaxPrice());

        // 排序
        String sort = query.getSort() == null ? "new" : query.getSort();
        switch (sort) {
            case "priceAsc" -> wrapper.orderByAsc(Product::getPrice);
            case "priceDesc" -> wrapper.orderByDesc(Product::getPrice);
            case "hot" -> wrapper.orderByDesc(Product::getViewCount);
            default -> wrapper.orderByDesc(Product::getCreateTime);
        }
        wrapper.orderByDesc(Product::getId);
        return wrapper;
    }

    /** 商品实体 -> 列表 VO（批量补齐分类名与卖家昵称，避免 N+1 查询） */
    private List<ProductVO> toVOList(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> categoryIds = products.stream().map(Product::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNameMap = categoryIds.isEmpty() ? Collections.emptyMap()
                : categoryService.listByIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));

        Set<Long> sellerIds = products.stream().map(Product::getSellerId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> sellerMap = sellerIds.isEmpty() ? Collections.emptyMap()
                : userService.listByIds(sellerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        return products.stream().map(product -> {
            ProductVO vo = new ProductVO();
            BeanUtils.copyProperties(product, vo);
            vo.setCategoryName(categoryNameMap.get(product.getCategoryId()));
            User seller = sellerMap.get(product.getSellerId());
            if (seller != null) {
                vo.setSellerNickname(seller.getNickname());
            }
            return vo;
        }).collect(Collectors.toList());
    }

    /** 商品实体 -> 详情 VO */
    private ProductDetailVO toDetailVO(Product product, Category category, boolean withImages) {
        ProductDetailVO vo = new ProductDetailVO();
        BeanUtils.copyProperties(product, vo);
        vo.setCategoryName(category == null ? null : category.getName());

        if (withImages) {
            List<ProductImage> images = productImageService.list(new LambdaQueryWrapper<ProductImage>()
                    .eq(ProductImage::getProductId, product.getId())
                    .orderByAsc(ProductImage::getSortOrder)
                    .orderByAsc(ProductImage::getId));
            vo.setImages(images.stream().map(ProductImage::getUrl).collect(Collectors.toList()));
        } else {
            vo.setImages(Collections.emptyList());
        }

        if (product.getSellerId() != null) {
            User seller = userService.getById(product.getSellerId());
            if (seller != null) {
                vo.setSellerNickname(seller.getNickname());
                vo.setSellerAvatar(seller.getAvatar());
                vo.setSellerCreditScore(seller.getCreditScore());
                vo.setSellerCampus(seller.getCampus());
            }
        }
        return vo;
    }
}
