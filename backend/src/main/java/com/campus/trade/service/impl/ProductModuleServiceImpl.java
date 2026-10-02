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
        return toDetailVO(product, category, false);
    }

    /* ==================== 2. 商品分页列表 ==================== */

    @Override
    public PageResult<ProductVO> pageList(ProductQueryDTO query) {
        LambdaQueryWrapper<Product> wrapper = buildBaseWrapper(query);
        // 列表只返回上架中的商品
        wrapper.eq(Product::getStatus, STATUS_ON_SALE);

        Page<Product> page = productService.page(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                toVOList(page.getRecords()));
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
        Product product = productService.getById(productId);
        if (product == null) {
            throw ProductException.notFound();
        }

        // v0.10 缺陷修复（BUG-02）：待审核(0) 商品只有卖家本人和管理员可见，
        // 其他人按"商品不存在"处理，避免未审核内容提前外泄。
        // 公开接口的当前登录用户由 LoginInterceptor 的可选鉴权写入（未登录则为 null）。
        if (Integer.valueOf(STATUS_PENDING).equals(product.getStatus())) {
            LoginUser current = UserContext.get();
            boolean isOwner = current != null && Objects.equals(current.userId(), product.getSellerId());
            boolean isAdmin = current != null && current.isAdmin();
            if (!isOwner && !isAdmin) {
                log.info("[商品详情] 待审核商品对外隐藏 productId={} 访问者={}", productId,
                        current == null ? "匿名" : current.userId());
                throw ProductException.notFound();
            }
        }

        // 浏览量 +1（一条 SQL 原子自增，避免并发覆盖）
        productService.update(new LambdaUpdateWrapper<Product>()
                .setSql("view_count = view_count + 1")
                .eq(Product::getId, productId));
        product.setViewCount(product.getViewCount() == null ? 1 : product.getViewCount() + 1);

        Category category = product.getCategoryId() == null ? null : categoryService.getById(product.getCategoryId());
        return toDetailVO(product, category, true);
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
