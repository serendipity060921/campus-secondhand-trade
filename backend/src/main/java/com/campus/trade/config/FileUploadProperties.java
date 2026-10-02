package com.campus.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件上传配置（v0.05）。
 *
 * <p>字段自带默认值，不修改 application.yml 也能直接运行；如需覆盖：</p>
 * <pre>
 * app:
 *   upload:
 *     path: ./uploads            # 本地保存目录（相对启动目录，也可写绝对路径）
 *     url-prefix: /upload        # 对外访问前缀，前端通过 {url-prefix}/2026/10/xxx.png 访问
 *     max-size: 5242880          # 单张图片大小上限（字节），默认 5MB
 *     max-count: 9               # 单次最多上传张数
 *     allowed-extensions: [jpg, jpeg, png, gif, webp, bmp]
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.upload")
public class FileUploadProperties {

    /** 本地保存目录（默认项目根目录下的 uploads） */
    private String path = "./uploads";

    /** 对外访问 URL 前缀 */
    private String urlPrefix = "/upload";

    /** 单张图片大小上限（字节），默认 5MB */
    private long maxSize = 5 * 1024 * 1024L;

    /** 单次上传最大张数 */
    private int maxCount = 9;

    /** 允许的图片扩展名（小写，不带点） */
    private List<String> allowedExtensions = List.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
}
