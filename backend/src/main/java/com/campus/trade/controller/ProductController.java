package com.campus.trade.controller;

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
 * 商品接口（脚手架示例：演示 MyBatis-Plus 分页插件 + 统一分页响应）。
 *
 * <p>说明：本里程碑只提供只读的分页/详情查询；发布、审核、搜索等属于后续业务里程碑。</p>
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 商品分页列表（演示分页插件）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    @GetMapping
    public Result<PageResult<Product>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        Page<Product> result = productService.page(new Page<>(page, size));
        return Result.success(PageResult.of(result));
    }

    /** 商品详情（演示路径参数 + 数据不存在时的统一错误响应） */
    @GetMapping("/{id}")
    public Result<Product> detail(@PathVariable("id") Long id) {
        Product product = productService.getById(id);
        if (product == null) {
            return Result.error(404, "商品不存在：id=" + id);
        }
        return Result.success(product);
    }
}
