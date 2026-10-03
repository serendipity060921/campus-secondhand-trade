package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.trade.common.exception.CategoryException;
import com.campus.trade.dto.CategoryDTO;
import com.campus.trade.entity.Category;
import com.campus.trade.service.CategoryModuleService;
import com.campus.trade.service.CategoryService;
import com.campus.trade.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 分类管理业务实现（v0.09）。
 *
 * <p>校验规则：</p>
 * <ol>
 *   <li>新增/修改时名称不能在同一上级下重复（数据库 uk_parent_name 唯一索引兜底）；</li>
 *   <li>上级分类必须存在（parentId != 0 时）；</li>
 *   <li>修改时分类必须存在，且上级不能设置为自己。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryModuleServiceImpl implements CategoryModuleService {

    /** 一级分类的 parentId */
    private static final long ROOT_PARENT_ID = 0L;

    private final CategoryService categoryService;
    /** v0.12：分类列表缓存（读多写少） */
    private final com.campus.trade.service.CacheService cacheService;
    private final com.campus.trade.common.cache.CacheKeys cacheKeys;
    private final com.campus.trade.common.cache.CacheEvictor cacheEvictor;
    private final com.campus.trade.config.CacheProperties cacheProperties;

    @Override
    public List<CategoryVO> list(Boolean onlyTop) {
        String key = cacheKeys.categoryList() + (Boolean.TRUE.equals(onlyTop) ? ":top" : ":all");
        // Cache-Aside：先查缓存，命中直接返回
        if (cacheService.exists(key)) {
            List<CategoryVO> cached = cacheService.get(key, List.class);
            if (cached != null) {
                log.debug("[缓存命中] 分类列表 onlyTop={}", onlyTop);
                return cached;
            }
        }
        List<CategoryVO> list = queryFromDb(onlyTop);
        cacheService.set(key, list, cacheProperties.getCategoryTtl());
        return list;
    }

    /** 分类列表查库（缓存未命中时执行） */
    private List<CategoryVO> queryFromDb(Boolean onlyTop) {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, 1)
                .eq(Boolean.TRUE.equals(onlyTop), Category::getParentId, ROOT_PARENT_ID)
                .orderByAsc(Category::getParentId)
                .orderByAsc(Category::getSortOrder)
                .orderByAsc(Category::getId);
        return categoryService.list(wrapper).stream().map(this::toVO).collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryVO add(CategoryDTO dto) {
        // 新增时名称必填（@Size 只校验长度，这里补非空判断，让"修改时名称可选"成立）
        if (!StringUtils.hasText(dto.getName())) {
            throw CategoryException.paramIllegal("新增分类时名称不能为空");
        }
        Long parentId = normalizeParentId(dto.getParentId());
        String name = dto.getName().trim();

        validateParentExists(parentId);
        validateNameUnique(parentId, name, null);

        Category category = new Category();
        category.setParentId(parentId);
        category.setName(name);
        category.setIcon(StringUtils.hasText(dto.getIcon()) ? dto.getIcon().trim() : null);
        category.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        category.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        category.setDeleted(0);
        categoryService.save(category);

        log.info("[分类新增] id={} parentId={} name={}", category.getId(), parentId, name);
        // v0.12 缓存失效：分类变更会影响分类列表、商品列表（列表里带分类名）与推荐结果
        cacheEvictor.categoryChanged();
        return toVO(categoryService.getById(category.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryVO update(CategoryDTO dto) {
        if (dto.getId() == null) {
            throw CategoryException.paramIllegal("修改分类时必须传 id");
        }
        Category exist = categoryService.getById(dto.getId());
        if (exist == null) {
            throw CategoryException.notFound();
        }

        Long parentId = dto.getParentId() == null ? exist.getParentId() : normalizeParentId(dto.getParentId());
        if (parentId.equals(dto.getId())) {
            throw CategoryException.selfParent();
        }
        validateParentExists(parentId);

        String name = dto.getName() == null ? exist.getName() : dto.getName().trim();
        validateNameUnique(parentId, name, dto.getId());

        Category update = new Category();
        update.setId(dto.getId());
        update.setParentId(parentId);
        update.setName(name);
        if (dto.getIcon() != null) {
            update.setIcon(StringUtils.hasText(dto.getIcon()) ? dto.getIcon().trim() : null);
        }
        update.setSortOrder(dto.getSortOrder());
        update.setStatus(dto.getStatus());
        // 注意：name/icon 之外的 null 字段会被 MyBatis-Plus 忽略，等于"不修改"
        categoryService.updateById(update);

        log.info("[分类修改] id={} parentId={} name={} status={}",
                dto.getId(), parentId, name, dto.getStatus());
        // v0.12 缓存失效
        cacheEvictor.categoryChanged();
        return toVO(categoryService.getById(dto.getId()));
    }

    /* ==================== 校验与转换 ==================== */

    private Long normalizeParentId(Long parentId) {
        return parentId == null ? ROOT_PARENT_ID : parentId;
    }

    /** 上级分类必须存在（0 表示一级分类，无需校验） */
    private void validateParentExists(Long parentId) {
        if (parentId == null || parentId == ROOT_PARENT_ID) {
            return;
        }
        if (categoryService.getById(parentId) == null) {
            throw CategoryException.parentNotFound();
        }
    }

    /** 同一上级下分类名不能重复 */
    private void validateNameUnique(Long parentId, String name, Long excludeId) {
        long count = categoryService.count(new LambdaQueryWrapper<Category>()
                .eq(Category::getParentId, parentId)
                .eq(Category::getName, name)
                .ne(excludeId != null, Category::getId, excludeId));
        if (count > 0) {
            throw CategoryException.nameExists();
        }
    }

    private CategoryVO toVO(Category category) {
        CategoryVO vo = new CategoryVO();
        BeanUtils.copyProperties(category, vo);
        return vo;
    }
}
