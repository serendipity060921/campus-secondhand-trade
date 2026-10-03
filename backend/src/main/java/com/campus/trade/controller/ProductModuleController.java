package com.campus.trade.controller;

import com.campus.trade.common.annotation.LoginRequired;
import com.campus.trade.common.annotation.RateLimit;
import com.campus.trade.common.context.UserContext;
import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.result.PageResult;
import com.campus.trade.common.result.Result;
import com.campus.trade.config.FileUploadProperties;
import com.campus.trade.dto.ProductPublishDTO;
import com.campus.trade.dto.ProductQueryDTO;
import com.campus.trade.dto.ProductStatusDTO;
import com.campus.trade.service.FileService;
import com.campus.trade.service.ProductModuleService;
import com.campus.trade.vo.FileUploadVO;
import com.campus.trade.vo.ProductDetailVO;
import com.campus.trade.vo.ProductVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 商品核心模块接口（v0.05）。
 *
 * <table border="1">
 *   <caption>接口清单</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th><th>登录</th></tr>
 *   <tr><td>POST</td><td>/api/product/publish</td><td>发布商品（卖家=当前登录用户）</td><td>是</td></tr>
 *   <tr><td>POST</td><td>/api/product/upload</td><td>图片上传（单张/多张），可带 productId 直接写入 product_image</td><td>是</td></tr>
 *   <tr><td>GET</td><td>/api/product/list</td><td>商品分页列表（仅上架商品）</td><td>否</td></tr>
 *   <tr><td>GET</td><td>/api/product/{id}</td><td>商品详情（浏览量 +1）</td><td>否</td></tr>
 *   <tr><td>GET</td><td>/api/product/mine</td><td>我的商品（含已下架）</td><td>是</td></tr>
 *   <tr><td>PUT</td><td>/api/product/status</td><td>上架 / 下架（仅限自己的商品）</td><td>是</td></tr>
 * </table>
 *
 * <p>命名说明：脚手架 v0.03 已存在演示用的 {@code ProductController}（/api/products），
 * 为避免破坏原有代码，本模块使用独立类名 ProductModuleController。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductModuleController {

    private final ProductModuleService productModuleService;
    private final FileService fileService;
    private final FileUploadProperties fileUploadProperties;

    /**
     * 发布商品（必须登录）。
     *
     * <p>卖家由 Token 解析出的当前登录用户决定，前端无需（也不允许）传 sellerId。</p>
     */
    @LoginRequired
    @PostMapping("/publish")
    @RateLimit(key = "publish", limit = 10, window = 300, dimension = RateLimit.Dimension.USER,
            message = "发布商品过于频繁")
    public Result<ProductDetailVO> publish(@Valid @RequestBody ProductPublishDTO dto) {
        ProductDetailVO vo = productModuleService.publish(dto, UserContext.getUserId());
        return Result.success("发布成功", vo);
    }

    /**
     * 图片上传（必须登录）。
     *
     * <p>支持两种入参写法：</p>
     * <ul>
     *   <li>单张：<code>file=@a.png</code></li>
     *   <li>多张：<code>files=@a.png&amp;files=@b.png</code></li>
     * </ul>
     * <p>若带上 <code>productId</code>，会在校验归属后把图片信息写入 product_image 表；
     * 若不带，则只返回图片地址，供发布接口在 imageUrls 中提交。</p>
     */
    @LoginRequired
    @PostMapping("/upload")
    public Result<List<FileUploadVO>> upload(
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "productId", required = false) Long productId) {

        // 合并单张与多张两种写法
        List<MultipartFile> uploadFiles = new ArrayList<>();
        if (files != null) {
            uploadFiles.addAll(Arrays.asList(files));
        }
        if (file != null && !file.isEmpty()) {
            uploadFiles.add(file);
        }
        uploadFiles.removeIf(f -> f == null || f.isEmpty());

        if (uploadFiles.isEmpty()) {
            throw ProductException.fileEmpty();
        }
        if (uploadFiles.size() > fileUploadProperties.getMaxCount()) {
            throw ProductException.imageCountExceeded();
        }

        List<FileUploadVO> result = new ArrayList<>();
        for (MultipartFile multipartFile : uploadFiles) {
            result.add(fileService.store(multipartFile));
        }

        // 带商品ID：直接写入 product_image（会校验商品归属）
        if (productId != null) {
            List<String> urls = result.stream().map(FileUploadVO::getUrl).toList();
            productModuleService.addImages(productId, urls, UserContext.getUserId());
        }
        return Result.success("上传成功", result);
    }

    /** 商品分页列表：只返回上架中的商品，支持分类/关键词/价格区间/排序 */
    @GetMapping("/list")
    public Result<PageResult<ProductVO>> list(@Valid ProductQueryDTO query) {
        return Result.success(productModuleService.pageList(query));
    }

    /** 我的商品（含已下架）：需要登录 */
    @LoginRequired
    @GetMapping("/mine")
    public Result<PageResult<ProductVO>> mine(@Valid ProductQueryDTO query) {
        return Result.success(productModuleService.pageMine(query, UserContext.getUserId()));
    }

    /** 商品详情：公开接口，每次访问浏览量 +1 */
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable("id") Long id) {
        return Result.success(productModuleService.detail(id));
    }

    /** 上架 / 下架：必须登录，且只能操作自己发布的商品 */
    @LoginRequired
    @PutMapping("/status")
    public Result<Void> changeStatus(@Valid @RequestBody ProductStatusDTO dto) {
        productModuleService.changeStatus(dto.getProductId(), dto.getStatus(), UserContext.getUserId());
        return Result.success(dto.getStatus() == 1 ? "上架成功" : "下架成功", null);
    }
}
