package com.campus.trade.controller;

import com.campus.trade.common.context.LoginUser;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.result.Result;
import com.campus.trade.config.RecommendProperties;
import com.campus.trade.service.RecommendService;
import com.campus.trade.vo.RecommendItemVO;
import com.campus.trade.vo.RecommendResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐接口（v0.11）。
 *
 * <table border="1">
 *   <caption>接口清单（映射在 /api/product 下，与 v0.05/v0.09 的商品接口互补）</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/product/recommend</td><td>猜你喜欢：登录后个性化推荐，未登录热门冷启动</td></tr>
 *   <tr><td>GET</td><td>/api/product/similar/{id}</td><td>相似商品：详情页"相关推荐"</td></tr>
 * </table>
 *
 * <p>鉴权说明：两个接口都是<b>公开接口</b>，不强制登录。若请求携带有效 Token，
 * 由 v0.10 起启用可选鉴权的 {@code LoginInterceptor} 把当前用户写入上下文，
 * 推荐服务据此读取该用户的行为做个性化；未登录则退化为热门推荐。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;
    private final RecommendProperties properties;

    /** 猜你喜欢（strategy 可选：auto/hot/content/cf/hybrid，主要用于离线对比实验与 A/B） */
    @GetMapping("/recommend")
    public Result<RecommendResultVO> recommend(@RequestParam(value = "size", required = false) Integer size,
                                               @RequestParam(value = "strategy", required = false) String strategy) {
        checkSize(size);
        // 公开接口：未登录时 UserContext 为空，此时退化为热门推荐。
        // 注意不能用 UserContext.getUserId()——那是给强制登录接口用的，匿名会抛 401。
        LoginUser current = UserContext.get();
        Long userId = current == null ? null : current.userId();
        return Result.success(recommendService.recommend(userId, size, strategy));
    }

    /** 相似商品（详情页相关推荐） */
    @GetMapping("/similar/{id}")
    public Result<RecommendResultVO> similar(@PathVariable("id") Long id,
                                             @RequestParam(value = "size", required = false) Integer size) {
        checkSize(size);
        return Result.success(recommendService.similar(id, size));
    }

    /**
     * 热门榜（v0.12）。
     *
     * <p>数据来自 Redis ZSet（{@code campus:hot:yyyyMMdd}），由用户行为实时加分，
     * 读取时只需一次 ZREVRANGE，不需要查数据库 —— 典型的"用缓存承接高频读"场景。</p>
     *
     * @param size 返回条数（1~30，默认 10）
     */
    @GetMapping("/hot")
    public Result<java.util.List<RecommendItemVO>> hot(
            @RequestParam(value = "size", required = false) Integer size) {
        checkSize(size);
        return Result.success(recommendService.hotList(size));
    }

    /** size 参数校验：1 ~ max-size，非法直接 400（与 v0.09 搜索接口一致的口径） */
    private void checkSize(Integer size) {
        if (size == null) {
            return;
        }
        if (size < 1 || size > properties.getMaxSize()) {
            throw ProductException.paramIllegal("size 必须为 1~" + properties.getMaxSize() + " 之间的整数");
        }
    }
}
