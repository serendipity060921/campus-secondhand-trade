package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.entity.Favorite;
import com.campus.trade.mapper.FavoriteMapper;
import com.campus.trade.service.FavoriteService;
import org.springframework.stereotype.Service;

/**
 * 商品收藏 业务实现。
 */
@Service
public class FavoriteServiceImpl extends ServiceImpl<FavoriteMapper, Favorite> implements FavoriteService {
}
