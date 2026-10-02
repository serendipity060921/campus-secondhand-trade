package com.campus.trade.service;

import com.campus.trade.dto.CategoryDTO;
import com.campus.trade.vo.CategoryVO;

/**
 * 分类管理业务（v0.09，仅管理员可用）。
 *
 * <p>只做新增与修改（本里程碑范围）；删除涉及"分类下是否还有商品/子分类"的判断，
 * 留给后台管理里程碑。</p>
 */
public interface CategoryModuleService {

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
