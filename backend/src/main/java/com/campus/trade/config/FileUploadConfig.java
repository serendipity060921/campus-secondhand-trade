package com.campus.trade.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 上传文件的静态资源映射（v0.05）。
 *
 * <p>脚手架为了纯前后端分离，关闭了 Spring Boot 默认静态资源映射
 * （<code>spring.web.resources.add-mappings: false</code>）。这里只针对
 * <b>上传目录</b>单独开放映射：把磁盘目录映射为 URL 前缀，使前端可以直接用
 * <code>&lt;img src="/upload/2026/10/xxx.png"&gt;</code> 显示图片。</p>
 *
 * <p>例：上传目录 D:/campus-secondhand-trade/backend/uploads，URL 前缀 /upload
 * → 访问 /upload/2026/10/abc.png 实际读取 D:/campus-secondhand-trade/backend/uploads/2026/10/abc.png</p>
 *
 * <p>注意：Java 源码中即使注释里也不能出现反斜杠 + u 的组合（会被当成 Unicode 转义），
 * 所以这里统一用正斜杠书写路径。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FileUploadConfig implements WebMvcConfigurer {

    private final FileUploadProperties fileUploadProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path basePath = Paths.get(fileUploadProperties.getPath()).toAbsolutePath().normalize();
        // 统一使用 "file:" + 正斜杠 + 结尾斜杠 的写法，兼容 Windows 盘符
        String location = "file:" + basePath.toString().replace("\\", "/") + "/";
        String pattern = fileUploadProperties.getUrlPrefix() + "/**";

        registry.addResourceHandler(pattern)
                .addResourceLocations(location)
                .setCachePeriod(3600);

        log.info("上传文件映射完成：{} -> {}", pattern, location);
    }
}
