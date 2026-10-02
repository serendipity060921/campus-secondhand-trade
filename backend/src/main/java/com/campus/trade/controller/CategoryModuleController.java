package com.campus.trade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.trade.common.result.Result;
import com.campus.trade.entity.Category;
import com.campus.trade.service.CategoryService;
import com.campus.trade.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类接口（v0.05）。
 *
 * <p>GET /api/category/list —— 商品发布页与首页筛选使用；
 * 返回启用状态的分类（一级 + 二级），前端按下拉分组展示。</p>
 *
 * <p>命名说明：脚手架 v0.03 已有 {@code CategoryController}（/api/categories），
 * 本模块使用独立类名 CategoryModuleController，互不影响。</p>
 */
@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryModuleController {

    private final CategoryService categoryService;

    /**
     * 分类列表。
     *
     * @param onlyTop true 时只返回一级分类（可选，默认返回全部）
     */
    @GetMapping("/list")
    public Result<List<CategoryVO>> list(@RequestParam(value = "onlyTop", required = false) Boolean onlyTop) {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, 1)
                .eq(Boolean.TRUE.equals(onlyTop), Category::getParentId, 0L)
                .orderByAsc(Category::getParentId)
                .orderByAsc(Category::getSortOrder)
                .orderByAsc(Category::getId);
        List<CategoryVO> list = categoryService.list(wrapper).stream().map(this::toVO).toList();
        return Result.success(list);
    }

    private CategoryVO toVO(Category category) {
        CategoryVO vo = new CategoryVO();
        BeanUtils.copyProperties(category, vo);
        return vo;
    }
}
