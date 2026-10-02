package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.entity.ProductImage;
import com.campus.trade.mapper.ProductImageMapper;
import com.campus.trade.service.ProductImageService;
import org.springframework.stereotype.Service;

/**
 * 商品图片 业务实现。
 */
@Service
public class ProductImageServiceImpl extends ServiceImpl<ProductImageMapper, ProductImage> implements ProductImageService {
}
