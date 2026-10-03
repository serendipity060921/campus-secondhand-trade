package com.campus.trade.service;

import com.campus.trade.dto.CategoryDTO;
import com.campus.trade.vo.CategoryVO;

import java.util.List;

/**
 * 分类管理业务（v0.09，仅管理员可用）。
 *
 * <p>只做新增与修改（本里程碑范围）；删除涉及"分类下是否还有商品/子分类"的判断，
 * 留给后台管理里程碑。</p>
 *
 * <p>v0.12：分类列表查询迁入本服务并加 Redis 缓存（分类是典型的"读多写极少"数据，
 * 首页与发布页都会拉取），新增/修改时主动失效缓存。</p>
 */
public interface CategoryModuleService {

    /**
     * 分类列表（只返回启用状态），带 Redis 缓存。
     *
     * @param onlyTop true 时只返回一级分类
     */
    List<CategoryVO> list(Boolean onlyTop);

    /**
     * 新增分类。
     *
     * @throws com.campus.trade.common.exception.CategoryException 同级重名(7001) / 上级分类不存在(7003)
     */
    CategoryVO add(CategoryDTO dto);

    /**
     * 修改分类。
     *
     * @throws com.campus.trade.common.exception.CategoryException 分类不存在(7002) / 同级重名(7001) / 上级不存在(7003) / 上级是自己(7004)
     */
    CategoryVO update(CategoryDTO dto);
}
