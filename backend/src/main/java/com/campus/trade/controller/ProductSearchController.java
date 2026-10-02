package com.campus.trade.controller;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.ProductSearchDTO;
import com.campus.trade.service.ProductSearchService;
import com.campus.trade.vo.ProductVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品搜索接口（v0.09）。
 *
 * <p><code>GET /api/product/search?keyword=教材&amp;categoryId=1&amp;sort=new&amp;page=1&amp;size=12</code></p>
 *
 * <p>公开接口（无需登录）：按商品名称模糊查询，支持分类筛选与分页。</p>
 * <p>路径为字面量 /search，优先级高于 v0.05 的 /api/product/{id}，不会冲突。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductSearchService productSearchService;

    @GetMapping("/search")
    public Result<PageResult<ProductVO>> search(@Valid ProductSearchDTO dto) {
        return Result.success(productSearchService.search(dto));
    }
}
