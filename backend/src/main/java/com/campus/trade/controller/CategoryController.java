package com.campus.trade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.trade.common.result.Result;
import com.campus.trade.entity.Category;
import com.campus.trade.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品分类接口（脚手架示例：演示 Controller → Service → Mapper → MySQL 完整分层调用）。
 *
 * <p>说明：本里程碑只提供只读查询，用于验证分层与数据库连通，
 * 分类的增删改与校验属于后续里程碑的业务范围。</p>
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 查询分类列表。
     *
     * @param parentId 可选，传入时只查询该父分类下的子分类（0 表示一级分类）
     */
    @GetMapping
    public Result<List<Category>> list(@RequestParam(value = "parentId", required = false) Long parentId) {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<Category>()
                .eq(parentId != null, Category::getParentId, parentId)
                .eq(Category::getStatus, 1)
                .orderByAsc(Category::getSortOrder)
                .orderByAsc(Category::getId);
        return Result.success(categoryService.list(wrapper));
    }
}
