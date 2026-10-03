package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.annotation.RateLimit;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.dto.FavoriteDTO;
import com.campus.trade.service.FavoriteModuleService;
import com.campus.trade.vo.FavoriteStatusVO;
import com.campus.trade.vo.FavoriteVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品收藏接口（v0.06）。
 *
 * <table border="1">
 *   <caption>接口清单（三个接口都需要登录）</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/favorite/operate</td><td>收藏 / 取消收藏（type: 1 收藏，2 取消）</td></tr>
 *   <tr><td>GET</td><td>/api/favorite/list</td><td>我的收藏分页（只返回上架商品）</td></tr>
 *   <tr><td>GET</td><td>/api/favorite/hasFavorite</td><td>是否已收藏该商品</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    /** 每页条数上限 */
    private static final long MAX_PAGE_SIZE = 100L;

    private final FavoriteModuleService favoriteModuleService;

    /**
     * 收藏 / 取消收藏。
     * <p>用户ID 来自 Token，前端不能指定收藏人。</p>
     */
    @LoginRequired
    @PostMapping("/operate")
    @RateLimit(key = "favorite", limit = 60, window = 60, dimension = RateLimit.Dimension.USER,
            message = "操作过于频繁")
    public Result<FavoriteStatusVO> operate(@Valid @RequestBody FavoriteDTO dto) {
        FavoriteStatusVO vo = favoriteModuleService.operate(dto.getProductId(), dto.getType(), UserContext.getUserId());
        boolean favorited = Boolean.TRUE.equals(vo.getFavorited());
        return Result.success(favorited ? "收藏成功" : "已取消收藏", vo);
    }

    /**
     * 我的收藏分页列表（只返回上架中的商品）。
     *
     * <p>注意：这里不用 @Min/@Max 注解校验，而是手动校验并抛 400，
     * 以免依赖 ConstraintViolationException 的处理链（保持全局异常处理器不变）。</p>
     */
    @LoginRequired
    @GetMapping("/list")
    public Result<PageResult<FavoriteVO>> list(@RequestParam(value = "page", defaultValue = "1") long page,
                                               @RequestParam(value = "size", defaultValue = "12") long size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ProductException.paramIllegal("page 必须大于 0，size 必须为 1~" + MAX_PAGE_SIZE);
        }
        return Result.success(favoriteModuleService.pageMine(page, size, UserContext.getUserId()));
    }

    /** 当前用户是否已收藏该商品 */
    @LoginRequired
    @GetMapping("/hasFavorite")
    public Result<FavoriteStatusVO> hasFavorite(@RequestParam("productId") Long productId) {
        return Result.success(favoriteModuleService.hasFavorite(productId, UserContext.getUserId()));
    }
}
