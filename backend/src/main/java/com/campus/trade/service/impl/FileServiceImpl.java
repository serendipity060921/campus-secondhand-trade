package com.campus.trade.service.impl;

import com.campus.trade.common.exception.ProductException;
import com.campus.trade.common.util.ImageTypeValidator;
import com.campus.trade.config.FileUploadProperties;
import com.campus.trade.service.FileService;
import com.campus.trade.vo.FileUploadVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 文件服务实现（v0.05）。
 *
 * <p>存储结构：{上传目录}/yyyy/MM/{uuid}.{ext}，例如 uploads/2026/10/6f1c....png</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    private final FileUploadProperties properties;

    /** 上传根目录（绝对路径） */
    private Path basePath;

    @PostConstruct
    public void init() throws IOException {
        this.basePath = Paths.get(properties.getPath()).toAbsolutePath().normalize();
        Files.createDirectories(basePath);
        log.info("图片上传目录：{}", basePath);
    }

    @Override
    public FileUploadVO store(MultipartFile file) {
        // ① 非空校验
        if (file == null || file.isEmpty()) {
            throw ProductException.fileEmpty();
        }

        // ② 扩展名白名单
        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename());
        String ext = StringUtils.getFilenameExtension(originalName);
        String lowerExt = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (!properties.getAllowedExtensions().contains(lowerExt)) {
            throw ProductException.fileTypeNotAllowed(lowerExt.isEmpty() ? "无扩展名" : lowerExt);
        }

        // ③ Content-Type 也必须属于图片，双重保险
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw ProductException.fileTypeNotAllowed(contentType == null ? "未知类型" : contentType);
        }

        // ④ ★ 内容校验（magic number）：扩展名与 Content-Type 都是客户端自述、可以伪造，
        //    只有文件头几个字节骗不了人。一个名为 a.png、标着 image/png 的文件，
        //    内容完全可以是 HTML / PHP / 可执行文件 —— 这一层就是拦它的。
        try (InputStream headStream = file.getInputStream()) {
            byte[] magic = headStream.readNBytes(ImageTypeValidator.HEAD_BYTES);
            String detected = ImageTypeValidator.detect(magic);
            if (detected == null) {
                // ★ 拦截必须留痕：这类请求往往就是探测行为，日志是事后追溯的唯一线索
                log.warn("[图片上传-已拦截] 文件内容不是有效图片: originalName={}, contentType={}, size={}",
                        originalName, contentType, file.getSize());
                throw ProductException.fileTypeNotAllowed("文件内容不是有效图片");
            }
            if (!ImageTypeValidator.matchesExtension(detected, lowerExt)) {
                log.warn("[图片上传-已拦截] 扩展名与内容不一致: originalName={}, 扩展名={}, 真实内容={}",
                        originalName, lowerExt, detected);
                throw ProductException.fileTypeNotAllowed(
                        "扩展名 " + lowerExt + " 与文件真实内容 " + detected + " 不一致");
            }
        } catch (IOException e) {
            log.error("[图片上传] 读取文件头失败: {}", originalName, e);
            throw ProductException.uploadError(e.getMessage());
        }

        // ⑤ 大小校验
        if (file.getSize() > properties.getMaxSize()) {
            throw ProductException.fileTooLarge();
        }

        // ⑥ 生成安全的文件名与目录
        String datePath = LocalDate.now().format(DATE_DIR);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + lowerExt;
        try {
            Path dir = basePath.resolve(datePath);
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String url = properties.getUrlPrefix() + "/" + datePath + "/" + fileName;
            log.info("[图片上传] {} -> {} ({} KB)", originalName, url, file.getSize() / 1024);
            return FileUploadVO.builder()
                    .originalName(originalName)
                    .url(url)
                    .size(file.getSize())
                    .build();
        } catch (IOException e) {
            log.error("[图片上传失败] {}", originalName, e);
            throw ProductException.uploadError(e.getMessage());
        }
    }

    @Override
    public String getBasePath() {
        return basePath.toString();
    }
}
