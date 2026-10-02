package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.CategoryDTO;
import com.campus.trade.service.CategoryModuleService;
import com.campus.trade.vo.CategoryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分类管理接口（v0.09，仅管理员）。
 *
 * <table border="1">
 *   <caption>接口清单</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th><th>权限</th></tr>
 *   <tr><td>POST</td><td>/api/category/add</td><td>新增分类</td><td>管理员</td></tr>
 *   <tr><td>PUT</td><td>/api/category/update</td><td>修改分类</td><td>管理员</td></tr>
 * </table>
 *
 * <p>权限说明：使用 v0.04 就预留好的 {@code @LoginRequired(admin = true)}，
 * 拦截器会校验 Token 中的 role；普通学生访问返回 403「没有操作权限」。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryManageController {

    private final CategoryModuleService categoryModuleService;

    /** 新增分类（管理员） */
    @LoginRequired(admin = true)
    @PostMapping("/add")
    public Result<CategoryVO> add(@Valid @RequestBody CategoryDTO dto) {
        return Result.success("分类新增成功", categoryModuleService.add(dto));
    }

    /** 修改分类（管理员） */
    @LoginRequired(admin = true)
    @PutMapping("/update")
    public Result<CategoryVO> update(@Valid @RequestBody CategoryDTO dto) {
        return Result.success("分类修改成功", categoryModuleService.update(dto));
    }
}
