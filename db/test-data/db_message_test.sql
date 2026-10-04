-- =============================================================================
-- 校园二手交易平台 v0.07 私信聊天模块 —— 数据库测试脚本
-- 用途：验证 message 表的私信（type = 2）数据、会话列表/聊天记录/未读统计 SQL
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_message_test.sql
-- 说明：脚本可重复执行；第 9 节的外键插入会故意报 1452，属预期结果
-- =============================================================================

USE campus_trade;

SELECT '================ 1. message 表结构（v0.07 关注字段） ================' AS step;
SELECT COLUMN_NAME AS `字段`, COLUMN_TYPE AS `类型`, IS_NULLABLE AS `可空`,
       COLUMN_DEFAULT AS `默认值`, COLUMN_COMMENT AS `注释`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'message'
ORDER BY ORDINAL_POSITION;

SELECT '================ 2. 消息类型分布（type=1 商品留言 / type=2 私信） ================' AS step;
SELECT type AS `类型`,
       CASE type WHEN 1 THEN '商品留言' WHEN 2 THEN '私信' ELSE '未知' END AS `说明`,
       COUNT(*) AS `条数`
FROM message WHERE deleted = 0 GROUP BY type;

SELECT '================ 3. 当前私信明细（含双方昵称与关联商品） ================' AS step;
SELECT m.id             AS `消息ID`,
       fu.nickname      AS `发送人`,
       tu.nickname      AS `接收人`,
       m.content        AS `内容`,
       IFNULL(p.title, '（无）') AS `关联商品`,
       CASE m.is_read WHEN 0 THEN '未读' ELSE '已读' END AS `状态`,
       m.create_time    AS `发送时间`
FROM message m
JOIN `user` fu ON fu.id = m.from_user_id
JOIN `user` tu ON tu.id = m.to_user_id
LEFT JOIN product p ON p.id = m.product_id
WHERE m.deleted = 0 AND m.type = 2
ORDER BY m.id;

SELECT '================ 4. 会话列表（后端 /api/message/conversationList 的等价 SQL） ================' AS step;
-- 以 stu_test01（用户ID 用子查询取）为例
SELECT t.peer_id        AS `聊天对象ID`,
       u.nickname       AS `对象昵称`,
       m.content        AS `最后一条消息`,
       m.create_time    AS `最后时间`,
       IF(m.from_user_id = u2.id, '我发的', '对方发的') AS `方向`,
       IFNULL(p.title, '（无）') AS `关联商品`,
       (SELECT COUNT(*) FROM message x
         WHERE x.deleted = 0 AND x.type = 2 AND x.to_user_id = u2.id
           AND x.from_user_id = t.peer_id AND x.is_read = 0) AS `未读数`
FROM (
    SELECT IF(m.from_user_id = (SELECT id FROM `user` WHERE username = 'stu_test01'), m.to_user_id, m.from_user_id) AS peer_id,
           MAX(m.id) AS max_id
    FROM message m
    WHERE m.deleted = 0 AND m.type = 2
      AND (m.from_user_id = (SELECT id FROM `user` WHERE username = 'stu_test01')
        OR m.to_user_id = (SELECT id FROM `user` WHERE username = 'stu_test01'))
    GROUP BY peer_id
) t
JOIN message m ON m.id = t.max_id
JOIN `user` u ON u.id = t.peer_id
JOIN `user` u2 ON u2.id = (SELECT id FROM `user` WHERE username = 'stu_test01')
LEFT JOIN product p ON p.id = m.product_id
ORDER BY m.create_time DESC, m.id DESC;

SELECT '================ 5. 两个用户之间的聊天记录（后端 /api/message/history 的等价 SQL） ================' AS step;
SELECT m.id          AS `消息ID`,
       fu.username   AS `发送人`,
       m.content     AS `内容`,
       m.is_read     AS `已读`,
       m.create_time AS `时间`
FROM message m
JOIN `user` fu ON fu.id = m.from_user_id
WHERE m.deleted = 0 AND m.type = 2
  AND ((m.from_user_id = 5 AND m.to_user_id = 1) OR (m.from_user_id = 1 AND m.to_user_id = 5))
ORDER BY m.create_time DESC, m.id DESC
LIMIT 3;   -- 后端分页：最新在前

SELECT '================ 6. 未读数统计 ================' AS step;
SELECT u.id AS `用户ID`, u.username AS `用户名`,
       (SELECT COUNT(*) FROM message m WHERE m.deleted = 0 AND m.type = 2
          AND m.to_user_id = u.id AND m.is_read = 0) AS `未读私信数`
FROM `user` u WHERE u.deleted = 0 ORDER BY u.id;

SELECT '================ 7. 标记已读（后端 PUT /api/message/read 的等价 SQL） ================' AS step;
-- 注意：务必带 to_user_id 条件，只允许把自己收到的消息标记为已读（防越权）
UPDATE message SET is_read = 1, read_time = NOW()
WHERE deleted = 0 AND type = 2 AND to_user_id = 1 AND from_user_id = 5 AND is_read = 0;

SELECT ROW_COUNT() AS `本次标记条数`;

SELECT '================ 8. 补充演示数据：新增一个聊天对象 ================' AS step;
-- 造一个演示用户 stu_demo（密码 123456 的 BCrypt 密文）并与他聊两句，让会话列表不止一个对象
DELETE FROM message WHERE from_user_id = (SELECT id FROM (SELECT id FROM `user` WHERE username = 'stu_demo') t)
                       OR to_user_id = (SELECT id FROM (SELECT id FROM `user` WHERE username = 'stu_demo') t);
DELETE FROM `user` WHERE username = 'stu_demo';

INSERT INTO `user` (username, password, nickname, real_name, student_no, phone, campus, role, status, credit_score)
VALUES ('stu_demo', '$2a$10$m9CT3hBn7eugreIZxoFL6ORFB3uxN9sNJYcrGOOXB5POBdzZJ6K26',
        '演示同学', '演示', '2021099', '13800000099', '东校区', 0, 1, 100);

INSERT INTO message (type, from_user_id, to_user_id, product_id, parent_id, content, is_read)
SELECT 2, d.id, s.id, 6, 0, '同学你好，你那本《计算机网络》还在吗？', 0
FROM `user` d, `user` s WHERE d.username = 'stu_demo' AND s.username = 'stu_test01';

INSERT INTO message (type, from_user_id, to_user_id, product_id, parent_id, content, is_read)
SELECT 2, s.id, d.id, 6, 0, '在的，18 元，图书馆门口可以随时面交', 0
FROM `user` d, `user` s WHERE d.username = 'stu_demo' AND s.username = 'stu_test01';

INSERT INTO message (type, from_user_id, to_user_id, product_id, parent_id, content, is_read)
SELECT 2, d.id, s.id, 6, 0, '好的，我明天下午过去找你', 0
FROM `user` d, `user` s WHERE d.username = 'stu_demo' AND s.username = 'stu_test01';

SELECT m.id AS `消息ID`, fu.nickname AS `发送人`, tu.nickname AS `接收人`, m.content AS `内容`
FROM message m
JOIN `user` fu ON fu.id = m.from_user_id
JOIN `user` tu ON tu.id = m.to_user_id
WHERE m.type = 2 AND (fu.username = 'stu_demo' OR tu.username = 'stu_demo')
ORDER BY m.id;

SELECT '================ 9. 外键与规则校验（预期报错，属正常） ================' AS step;
-- 9.1 发送人不存在 → 期望 1452
INSERT INTO message (type, from_user_id, to_user_id, content, is_read)
VALUES (2, 999999, 1, '非法发送人', 0);
-- 9.2 接收人不存在 → 期望 1452（后端在插入前会先校验并返回 5002）
INSERT INTO message (type, from_user_id, to_user_id, content, is_read)
VALUES (2, 5, 999999, '非法接收人', 0);
-- 9.3 关联商品不存在 → 期望 1452（后端会先校验并返回 3001）
INSERT INTO message (type, from_user_id, to_user_id, product_id, content, is_read)
VALUES (2, 5, 1, 999999, '非法商品', 0);

SELECT '================ 10. 业务规则排查 SQL ================' AS step;
-- 10.1 自聊异常（正常应为 0 条，后端会拦 5001）
SELECT COUNT(*) AS `自己发给自己的异常条数` FROM message WHERE from_user_id = to_user_id AND deleted = 0;

-- 10.2 消息总数与类型
SELECT (SELECT COUNT(*) FROM message WHERE deleted = 0) AS `消息总数`,
       (SELECT COUNT(*) FROM message WHERE deleted = 0 AND type = 2) AS `私信数`,
       (SELECT COUNT(*) FROM message WHERE deleted = 0 AND is_read = 0) AS `未读总数`,
       (SELECT COUNT(DISTINCT LEAST(from_user_id, to_user_id) * 1000 + GREATEST(from_user_id, to_user_id))
          FROM message WHERE deleted = 0 AND type = 2) AS `会话对数`;

-- 10.3 每个会话的消息条数排行
SELECT LEAST(from_user_id, to_user_id) AS `用户A`, GREATEST(from_user_id, to_user_id) AS `用户B`,
       COUNT(*) AS `消息条数`
FROM message WHERE deleted = 0 AND type = 2
GROUP BY `用户A`, `用户B` ORDER BY `消息条数` DESC;

-- =============================================================================
-- 附：清理 SQL（按需执行）
--   清空全部私信：   DELETE FROM message WHERE type = 2;
--   删除演示用户：   DELETE FROM message WHERE from_user_id = <stu_demo id> OR to_user_id = <stu_demo id>;
--                    DELETE FROM `user` WHERE username = 'stu_demo';
-- =============================================================================
