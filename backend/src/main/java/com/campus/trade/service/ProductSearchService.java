package com.campus.trade.service;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.ProductSearchDTO;
import com.campus.trade.vo.ProductVO;

/**
 * 商品搜索业务（v0.09）。
 *
 * <p>与 v0.05 的 {@code /api/product/list} 的区别：</p>
 * <ul>
 *   <li>搜索<b>只按商品名称</b>模糊匹配（list 接口是名称 + 描述）；</li>
 *   <li>默认排序为"最新发布"，可选价格与热度排序；</li>
 *   <li>只返回上架商品，分类支持一级分类自动包含子分类。</li>
 * </ul>
 */
public interface ProductSearchService {

    /**
     * 搜索商品。
     *
     * @throws com.campus.trade.common.exception.ProductException 参数非法（如关键词超长时由校验器拦截）
     */
    PageResult<ProductVO> search(ProductSearchDTO dto);
}
