package com.campus.trade.service;

import com.campus.trade.common.result.PageResult;
import com.campus.trade.dto.ProductPublishDTO;
import com.campus.trade.dto.ProductQueryDTO;
import com.campus.trade.vo.ProductDetailVO;
import com.campus.trade.vo.ProductVO;

import java.util.List;

/**
 * 商品核心业务（v0.05）。
 *
 * <p>独立于脚手架原有的 {@code ProductService}（那只是 IService 通用 CRUD），
 * 本接口承载商品模块的业务规则：发布、分页列表、详情、上下架、图片入库。</p>
 */
public interface ProductModuleService {

    /**
     * 发布商品（卖家 = 当前登录用户）。
     *
     * @throws com.campus.trade.common.exception.ProductException 分类不存在
     */
    ProductDetailVO publish(ProductPublishDTO dto, Long sellerId);

    /** 商品分页列表（只返回上架中的商品） */
    PageResult<ProductVO> pageList(ProductQueryDTO query);

    /** 我的商品分页列表（含上架/下架/已售出） */
    PageResult<ProductVO> pageMine(ProductQueryDTO query, Long sellerId);

    /**
     * 商品详情（每次访问浏览量 +1）。
     *
     * @throws com.campus.trade.common.exception.ProductException 商品不存在
     */
    ProductDetailVO detail(Long productId);

    /**
     * 商品上下架（只能操作自己发布的商品）。
     *
     * @param status 1 = 上架，3 = 下架
     * @throws com.campus.trade.common.exception.ProductException 商品不存在 / 无权限 / 状态不允许 / 参数非法
     */
    void changeStatus(Long productId, Integer status, Long userId);

    /**
     * 把已上传的图片地址写入 product_image 表（并把第一张设为封面）。
     *
     * @throws com.campus.trade.common.exception.ProductException 商品不存在 / 无权限 / 数量超限
     */
    void addImages(Long productId, List<String> urls, Long userId);
}
