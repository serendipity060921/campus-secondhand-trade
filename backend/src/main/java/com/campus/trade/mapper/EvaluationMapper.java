package com.campus.trade.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.entity.Evaluation;

/**
 * 评价表 Mapper（v0.18 / 第6批-㉑）。
 *
 * <p>刻意只保留 {@link BaseMapper} 的基础能力，不写自定义 SQL / XML：
 * 统计与富化（昵称、商品标题、星级分布）都在服务层用 Wrapper 查询后内存计算完成。</p>
 *
 * <p>理由：本项目 CI 会用 <code>01-schema.sql</code> + 补丁脚本在 MySQL 容器里从零建库，
 * 自定义 SQL 一旦引用了某个脚本才有的列/函数，就会"本机能跑、CI 建库后报错"。
 * 评价数据量级很小（一单一两条），内存聚合的代价可以忽略。</p>
 */
@Mapper
public interface EvaluationMapper extends BaseMapper<Evaluation> {
}
