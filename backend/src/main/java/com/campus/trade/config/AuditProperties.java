package com.campus.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务开关配置（v0.13），对应 {@code campus.audit.*}。
 *
 * <p><b>商品审核开关</b>：</p>
 * <ul>
 *   <li>{@code enabled = false}（默认）：发布商品直接上架（沿用 v0.05 以来的行为，
 *       保证既有接口与测试兼容）；</li>
 *   <li>{@code enabled = true}：发布商品进入「待审核」状态，管理员在后台审核通过后才上架 ——
 *       这是更贴近真实校园平台的流程，答辩演示时可打开。</li>
 * </ul>
 *
 * <p>开关只影响"新发布商品"，历史商品不受影响；审核接口本身两种配置下都可用。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "campus.audit")
public class AuditProperties {

    /** 是否开启发布审核（false = 发布即上架） */
    private boolean enabled = false;

    /** 驳回后卖家可重新编辑并再次提交（预留，当前版本未实现重新提交接口） */
    private boolean allowResubmit = true;
}
