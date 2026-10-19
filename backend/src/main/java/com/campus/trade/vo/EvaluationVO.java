package com.campus.trade.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import lombok.Data;

/**
 * 评价展示对象（v0.18 / 第6批-㉑）。
 *
 * <p>两个刻意的处理：</p>
 * <ul>
 *   <li><b>匿名不泄漏身份</b>：{@code anonymous=1} 时把昵称/头像清空并改成"匿名同学"，
 *       但 {@code fromUserId} 仍然保留（管理端与本人仍需可追溯）——
 *       只在前端展示层隐藏，不改变数据 ✓；</li>
 *   <li>{@code tags} 同时给原始串与切好的列表，避免前端各自 split 出不一致的结果。</li>
 * </ul>
 */
@Data
public class EvaluationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long orderId;

    private Long productId;

    private String productTitle;

    private Long fromUserId;

    private String fromNickname;

    private String fromAvatar;

    private Long toUserId;

    private String toNickname;

    /** 1 买家评卖家 / 2 卖家评买家 */
    private Integer role;

    private String roleLabel;

    private Integer rating;

    private String content;

    private String tags;

    private List<String> tagList;

    private Integer anonymous;

    private LocalDateTime createTime;

    /** 便于前端直接判断"这条是不是我写的" */
    private Boolean mine;

    /** 把逗号分隔的标签切成列表（去空、去重保序） */
    public void fillTagList() {
        if (tags == null || tags.isBlank()) {
            tagList = List.of();
            return;
        }
        tagList = Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
