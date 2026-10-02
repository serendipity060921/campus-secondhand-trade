package com.campus.trade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.entity.Product;
import com.campus.trade.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口（v0.03 脚手架演示接口，演示 MyBatis-Plus 分页插件 + 统一分页响应）。
 *
 * <p><b>v0.10 缺陷修复记录（BUG-01）</b>：</p>
 * <p>原实现直接 {@code productService.page(new Page<>(page, size))}，会把<b>所有状态</b>的商品
 * （待审核 0 / 已下架 3 / 交易中 4 / 已售出 5）都返回给匿名访问者，属于数据越权泄漏。
 * 现已限定 {@code status = 1（在售）}，与 v0.05 的 /api/product/list 口径保持一致；
 * 详情接口同样只允许查看在售商品。</p>
 *
 * <p>正式的商品接口请使用 v0.05 的 <code>/api/product/**</code>；
 * 本接口保留是为了兼容脚手架自带的连通性自检看板（/dev/health）。</p>
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    /** 商品状态：在售 */
    private static final int STATUS_ON_SALE = 1;

    private final ProductService productService;

    /**
     * 商品分页列表（只返回在售商品）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    @GetMapping
    public Result<PageResult<Product>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        Page<Product> result = productService.page(new Page<>(page, size),
                new LambdaQueryWrapper<Product>().eq(Product::getStatus, STATUS_ON_SALE));
        return Result.success(PageResult.of(result));
    }

    /** 商品详情（只返回在售商品，其余状态统一按"不存在"处理，避免泄漏） */
    @GetMapping("/{id}")
    public Result<Product> detail(@PathVariable("id") Long id) {
        Product product = productService.getOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getId, id)
                .eq(Product::getStatus, STATUS_ON_SALE));
        if (product == null) {
            return Result.error(404, "商品不存在或已下架：id=" + id);
        }
        return Result.success(product);
    }
}
