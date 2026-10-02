package com.campus.trade.service;

import com.campus.trade.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件服务（v0.05）：把上传的图片保存到本地磁盘。
 */
public interface FileService {

    /**
     * 保存单个图片文件。
     *
     * <p>校验：非空 → 扩展名白名单 → Content-Type 必须是 image/* → 大小上限；
     * 文件名使用 UUID 重新生成，避免原始文件名带来的路径穿越与重名问题。</p>
     *
     * @param file 上传的文件
     * @return 原始文件名 + 访问地址 + 大小
     * @throws com.campus.trade.common.exception.ProductException 文件为空 / 格式不支持 / 超过大小 / 保存失败
     */
    FileUploadVO store(MultipartFile file);

    /** 上传目录的绝对路径（日志/排查用） */
    String getBasePath();
}
