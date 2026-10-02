package com.campus.trade.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 服务健康信息（脚手架连通性自检用）。
 */
@Data
@Builder
public class HealthVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 应用名称 */
    private String application;

    /** 版本号 */
    private String version;

    /** 运行环境 */
    private String profile;

    /** 服务状态 */
    private String status;

    /** 服务端时间 */
    private String serverTime;

    /** 运行服务的 JDK 版本 */
    private String javaVersion;
}
