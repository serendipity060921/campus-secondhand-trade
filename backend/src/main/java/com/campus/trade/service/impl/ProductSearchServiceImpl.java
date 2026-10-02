package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.ProductSearchDTO;
import com.campus.trade.entity.Category;
import com.campus.trade.entity.Product;
import com.campus.trade.entity.User;
import com.campus.trade.service.CategoryService;
import com.campus.trade.service.ProductSearchService;
import com.campus.trade.service.ProductService;
import com.campus.trade.service.UserService;
import com.campus.trade.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品搜索业务实现（v0.09）。
 *
 * <p>只查询上架商品（status = 1），关键词用 LIKE 匹配商品名称；
 * 分类筛选支持一级分类自动带上子分类；结果批量补齐分类名与卖家昵称，避免 N+1 查询。</p>
 *
 * <p>说明：本服务与 v0.05 的列表服务查询语义不同（搜索仅匹配名称、默认按最新排序），
 * 因此独立实现；两者共用既有的 ProductService / CategoryService / UserService，
 * 没有修改 v0.05 的任何代码。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchServiceImpl implements ProductSearchService {

    /** 商品状态：在售 */
    private static final int STATUS_ON_SALE = 1;

    private final ProductService productService;
    private final CategoryService categoryService;
    private final UserService userService;

    @Override
    public PageResult<ProductVO> search(ProductSearchDTO dto) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        // ① 只搜上架商品
        wrapper.eq(Product::getStatus, STATUS_ON_SALE);

        // ② 关键词：按商品名称模糊匹配
        if (StringUtils.hasText(dto.getKeyword())) {
            wrapper.like(Product::getTitle, dto.getKeyword().trim());
        }

        // ③ 分类筛选：一级分类自动包含子分类
        if (dto.getCategoryId() != null) {
            Set<Long> categoryIds = new LinkedHashSet<>();
            categoryIds.add(dto.getCategoryId());
            categoryService.list(new LambdaQueryWrapper<Category>()
                            .eq(Category::getParentId, dto.getCategoryId()))
                    .forEach(child -> categoryIds.add(child.getId()));
            wrapper.in(Product::getCategoryId, categoryIds);
        }

        // ④ 价格区间（选填）
        wrapper.ge(dto.getMinPrice() != null, Product::getPrice, dto.getMinPrice());
        wrapper.le(dto.getMaxPrice() != null, Product::getPrice, dto.getMaxPrice());

        // ⑤ 排序
        String sort = dto.getSort() == null ? "new" : dto.getSort();
        switch (sort) {
            case "priceAsc" -> wrapper.orderByAsc(Product::getPrice);
            case "priceDesc" -> wrapper.orderByDesc(Product::getPrice);
            case "hot" -> wrapper.orderByDesc(Product::getViewCount);
            default -> wrapper.orderByDesc(Product::getCreateTime);
        }
        wrapper.orderByDesc(Product::getId);

        // ⑥ 分页 + 组装
        Page<Product> page = productService.page(new Page<>(dto.getPage(), dto.getSize()), wrapper);
        log.info("[商品搜索] keyword={} categoryId={} sort={} 命中={} 条",
                dto.getKeyword(), dto.getCategoryId(), sort, page.getTotal());
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                toVOList(page.getRecords()));
    }

    /** 批量补齐分类名与卖家昵称（避免 N+1 查询） */
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
}
