package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.trade.entity.Message;
import com.campus.trade.vo.ConversationVO;
import com.campus.trade.vo.MessageVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 私信模块 Mapper（v0.07）。
 *
 * <p>只用于两处必须联表的查询；其余增删改查走脚手架既有的 {@code MessageService}
 * （IService），不修改既有 Mapper。</p>
 *
 * <p>约定：私信固定 <code>type = 2</code>（type = 1 是商品留言，留给后续里程碑），
 * 因此这里所有查询都带 <code>type = 2</code> 条件，两个功能互不干扰。</p>
 */
@Mapper
public interface MessageModuleMapper extends BaseMapper<Message> {

    /**
     * 会话列表：当前用户的所有聊天对象 + 最后一条消息 + 未读数。
     *
     * <p>实现思路：</p>
     * <ol>
     *   <li>子查询把"我发出的 / 发给我的"私信统一成 (peer_id, max_id)，
     *       peer_id 用 IF() 取对话的另一方；</li>
     *   <li>再用 max_id 关联回 message 取最后一条内容；</li>
     *   <li>unreadCount 用相关子查询统计"对方发给我且未读"的条数。</li>
     * </ol>
     */
    @Select("""
            SELECT t.peer_id            AS peer_id,
                   m.id                 AS last_message_id,
                   m.content            AS last_message,
                   m.create_time        AS last_message_time,
                   (m.from_user_id = #{userId}) AS last_from_me,
                   m.product_id         AS product_id,
                   p.title              AS product_title,
                   u.nickname           AS peer_nickname,
                   u.avatar             AS peer_avatar,
                   u.campus             AS peer_campus,
                   (SELECT COUNT(*) FROM message x
                     WHERE x.deleted = 0 AND x.type = 2
                       AND x.to_user_id = #{userId} AND x.from_user_id = t.peer_id AND x.is_read = 0) AS unread_count
            FROM (
                SELECT IF(m.from_user_id = #{userId}, m.to_user_id, m.from_user_id) AS peer_id,
                       MAX(m.id) AS max_id
                FROM message m
                WHERE m.deleted = 0 AND m.type = 2
                  AND (m.from_user_id = #{userId} OR m.to_user_id = #{userId})
                GROUP BY peer_id
            ) t
            INNER JOIN message m ON m.id = t.max_id
            LEFT JOIN `user` u ON u.id = t.peer_id
            LEFT JOIN product p ON p.id = m.product_id
            ORDER BY m.create_time DESC, m.id DESC
            """)
    List<ConversationVO> selectConversationList(@Param("userId") Long userId);

    /**
     * 两个用户之间的聊天记录（分页，按时间倒序：最新的在前）。
     *
     * <p>前端展示时把当前页反转成"旧 → 新"，向上滚动再加载下一页（更早的消息）。</p>
     */
    @Select("""
            SELECT m.id            AS id,
                   m.from_user_id  AS from_user_id,
                   m.to_user_id    AS to_user_id,
                   m.content       AS content,
                   m.product_id    AS product_id,
                   p.title         AS product_title,
                   m.is_read       AS is_read,
                   m.read_time     AS read_time,
                   m.create_time   AS create_time
            FROM message m
            LEFT JOIN product p ON p.id = m.product_id
            WHERE m.deleted = 0 AND m.type = 2
              AND ((m.from_user_id = #{userId} AND m.to_user_id = #{peerId})
                OR (m.from_user_id = #{peerId} AND m.to_user_id = #{userId}))
            ORDER BY m.create_time DESC, m.id DESC
            """)
    IPage<MessageVO> selectHistory(IPage<MessageVO> page,
                                   @Param("userId") Long userId,
                                   @Param("peerId") Long peerId);
}
