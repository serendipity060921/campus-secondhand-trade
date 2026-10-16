package com.campus.trade.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果封装，配合 MyBatis-Plus 的 {@link IPage} 使用。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private Long total;

    /** 总页数 */
    private Long pages;

    /** 当前页码 */
    private Long current;

    /** 每页条数 */
    private Long size;

    /** 当前页数据 */
    private List<T> records;

    public PageResult() {
    }

    public PageResult(Long total, Long pages, Long current, Long size, List<T> records) {
        this.total = total;
        this.pages = pages;
        this.current = current;
        this.size = size;
        this.records = records;
    }

    /** 由 MyBatis-Plus 的分页对象转换 */
    public static <T> PageResult<T> of(IPage<T> page) {
        // ★ v0.18 修复（BUG-019）：records 必须兜底为空集合。
        //   原先直接透传 page.getRecords()，一旦底层返回 null，
        //   响应里就会出现 "records": null —— 前端对列表做 .map / .length 会直接崩。
        //   这个问题是被"统一响应结构"的单测发现的（ResultAndPageResultTest#ofNullRecords）。
        java.util.List<T> records = page.getRecords() == null
                ? Collections.emptyList() : page.getRecords();
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    /** 空分页 */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(0L, 0L, 1L, 10L, Collections.emptyList());
    }
}
