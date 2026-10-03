package com.campus.trade.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 推荐结果集（v0.11 推荐模块）。
 *
 * <p>除了商品列表，还返回本次推荐的"策略与依据"，方便前端展示、
 * 也方便测试用例直接断言算法行为（例如断言冷启动走了热门策略）。</p>
 */
@Data
public class RecommendResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 策略：personal（个性化）/ hot（热门·冷启动）/ similar（相似商品） */
    private String strategy;

    /** 策略中文名 */
    private String strategyLabel;

    /** 是否为个性化推荐（false = 冷启动热门） */
    private Boolean personalized;

    /** 用户画像描述，例如「常看：教材书籍、数码电子；价位 ￥10~80」 */
    private String profileDesc;

    /** 召回依据说明（算法内部过程，便于测试与演示） */
    private List<String> basis = new ArrayList<>();

    /** 返回条数 */
    private Integer total;

    /** 推荐商品列表 */
    private List<RecommendItemVO> items = new ArrayList<>();
}
