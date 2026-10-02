package com.campus.trade.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 图片上传结果（v0.05）。
 */
@Data
@Builder
public class FileUploadVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 原始文件名（仅用于展示） */
    private String originalName;

    /** 可访问的相对地址，例如 /upload/2026/10/6f1c...png */
    private String url;

    /** 文件大小（字节） */
    private Long size;
}
