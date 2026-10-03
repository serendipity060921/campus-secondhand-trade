-- =============================================================================
-- 校园二手交易平台 常用查询（可直接在 HeidiSQL 的"查询"标签页里粘贴执行）
-- 数据库：campus_trade    账号：root / 123456
-- =============================================================================

USE campus_trade;

-- ---------- 1. 用户 ----------
-- 看所有账号（密码是 BCrypt 密文，不可逆，这是正常的）
SELECT id, username, nickname, real_name, student_no, phone, email,
       CASE role WHEN 1 THEN '管理员' ELSE '学生' END AS 角色,
       CASE status WHEN 1 THEN '正常' ELSE '禁用' END AS 状态,
       gender, school, campus, credit_score, avatar, last_login_time, create_time
FROM `user` WHERE deleted = 0 ORDER BY id;

-- 找我自己刚注册的账号（按时间倒序）
SELECT id, username, nickname, create_time FROM `user` ORDER BY create_time DESC;

-- ---------- 2. 商品 ----------
SELECT p.id, p.title AS 商品名称, c.name AS 分类, u.username AS 卖家,
       p.price AS 售价, p.condition_level AS 成色,
       CASE p.status WHEN 0 THEN '待审核' WHEN 1 THEN '在售' WHEN 2 THEN '审核不通过'
                     WHEN 3 THEN '已下架' WHEN 4 THEN '交易中' WHEN 5 THEN '已售出' END AS 状态,
       p.view_count AS 浏览量, p.favorite_count AS 收藏数, p.create_time AS 发布时间
FROM product p
LEFT JOIN category c ON c.id = p.category_id
LEFT JOIN `user` u ON u.id = p.seller_id
WHERE p.deleted = 0 ORDER BY p.id;

-- 商品图片
SELECT i.id, i.product_id, p.title AS 商品, i.url AS 图片地址, i.sort_order AS 排序
FROM product_image i LEFT JOIN product p ON p.id = i.product_id
WHERE i.deleted = 0 ORDER BY i.product_id, i.sort_order;

-- ---------- 3. 分类 ----------
SELECT id, parent_id, name, sort_order, status FROM category WHERE deleted = 0 ORDER BY parent_id, sort_order;

-- ---------- 4. 收藏 ----------
SELECT f.id, u1.username AS 收藏人, p.title AS 商品, u2.username AS 卖家, f.create_time AS 收藏时间
FROM favorite f
JOIN `user` u1 ON u1.id = f.user_id
JOIN product p ON p.id = f.product_id
JOIN `user` u2 ON u2.id = p.seller_id
WHERE f.deleted = 0 ORDER BY f.id;

-- ---------- 5. 留言 / 私信 ----------
SELECT m.id, CASE m.type WHEN 1 THEN '商品留言' ELSE '私信' END AS 类型,
       fu.username AS 发送人, tu.username AS 接收人, m.content AS 内容,
       p.title AS 关联商品, m.is_read AS 已读, m.create_time AS 时间
FROM message m
JOIN `user` fu ON fu.id = m.from_user_id
LEFT JOIN `user` tu ON tu.id = m.to_user_id
LEFT JOIN product p ON p.id = m.product_id
WHERE m.deleted = 0 ORDER BY m.id;

-- ---------- 6. 订单 ----------
SELECT o.id, o.order_no AS 订单号, o.product_title AS 商品, o.amount AS 金额,
       bu.username AS 买家, su.username AS 卖家,
       CASE o.status WHEN 0 THEN '待交易' WHEN 1 THEN '待交付' WHEN 2 THEN '待收货'
                     WHEN 3 THEN '已完成' WHEN 4 THEN '已取消' WHEN 5 THEN '已退款' END AS 状态,
       o.trade_place AS 交易地点, o.buyer_remark AS 买家备注, o.cancel_reason AS 取消原因,
       o.create_time AS 下单时间, o.finish_time AS 完成时间
FROM orders o
JOIN `user` bu ON bu.id = o.buyer_id
JOIN `user` su ON su.id = o.seller_id
WHERE o.deleted = 0 ORDER BY o.id;

-- ---------- 7. 数据概览 ----------
SELECT
  (SELECT COUNT(*) FROM `user` WHERE deleted = 0) AS 用户数,
  (SELECT COUNT(*) FROM product WHERE deleted = 0) AS 商品数,
  (SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 1) AS 在售商品,
  (SELECT COUNT(*) FROM orders WHERE deleted = 0) AS 订单数,
  (SELECT COUNT(*) FROM orders WHERE deleted = 0 AND status = 3) AS 已完成订单,
  (SELECT COUNT(*) FROM favorite WHERE deleted = 0) AS 收藏数,
  (SELECT COUNT(*) FROM message WHERE deleted = 0) AS 消息数,
  (SELECT COUNT(*) FROM category WHERE deleted = 0) AS 分类数;

-- ---------- 8. 常用管理操作（按需取消注释） ----------
-- 把某个账号的密码重置为 123456（BCrypt 密文，下面的密文对应明文 123456）
-- UPDATE `user` SET password = '$2a$10$m9CT3hBn7eugreIZxoFL6ORFB3uxN9sNJYcrGOOXB5POBdzZJ6K26'
-- WHERE username = 'serendipity';

-- 禁用 / 解禁账号
-- UPDATE `user` SET status = 0 WHERE username = 'xxx';
-- UPDATE `user` SET status = 1 WHERE username = 'xxx';

-- 把某人的昵称改掉
-- UPDATE `user` SET nickname = '新昵称' WHERE username = 'xxx';
