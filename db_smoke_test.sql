-- =============================================================================
-- 结构 + 业务冒烟测试脚本（在 db_schema.sql 执行成功后运行）
-- 说明：使用 mysql --force 运行，故意触发唯一键/外键冲突以验证约束生效。
-- =============================================================================
USE campus_trade;

SELECT '=== 1. 表清单 ===' AS step;
SELECT TABLE_NAME AS `表名`, ENGINE AS `引擎`, TABLE_COLLATION AS `排序规则`, TABLE_COMMENT AS `注释`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME;

SELECT '=== 2. 数量统计 ===' AS step;
SELECT (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'campus_trade') AS `表数量`,
       (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'campus_trade') AS `列数量`,
       (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
         WHERE TABLE_SCHEMA = 'campus_trade' AND REFERENCED_TABLE_NAME IS NOT NULL) AS `外键数量`,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = 'campus_trade') AS `索引条目数`;

SELECT '=== 3. 外键关系 ===' AS step;
SELECT TABLE_NAME AS `子表`, COLUMN_NAME AS `列`, CONSTRAINT_NAME AS `约束名`,
       REFERENCED_TABLE_NAME AS `父表`, REFERENCED_COLUMN_NAME AS `父列`
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = 'campus_trade' AND REFERENCED_TABLE_NAME IS NOT NULL
ORDER BY TABLE_NAME, CONSTRAINT_NAME;

SELECT '=== 4. 初始化数据 ===' AS step;
SELECT COUNT(*) AS `分类总数`, SUM(parent_id = 0) AS `一级分类`, SUM(parent_id > 0) AS `二级分类` FROM category;
SELECT id, username, nickname, role, status, LEFT(password, 7) AS `密码前缀` FROM `user`;

SELECT '=== 5. DML 冒烟测试：注册学生 -> 发布商品 -> 审核 -> 留言 -> 下单 -> 确认收货 ===' AS step;

-- 5.1 学生注册（卖家 / 买家）
INSERT INTO `user` (username, password, nickname, real_name, student_no, phone, campus, role)
VALUES ('stu_seller', '$2a$10$m9CT3hBn7eugreIZxoFL6ORFB3uxN9sNJYcrGOOXB5POBdzZJ6K26',
        '卖家小张', '张同学', '2021001', '13800000001', '东校区', 0),
       ('stu_buyer', '$2a$10$m9CT3hBn7eugreIZxoFL6ORFB3uxN9sNJYcrGOOXB5POBdzZJ6K26',
        '买家小李', '李同学', '2021002', '13800000002', '东校区', 0);

-- 5.2 发布商品（status = 0 待审核）
INSERT INTO `product` (title, description, category_id, seller_id, price, original_price,
                       cover_image, condition_level, campus, trade_place, status)
VALUES ('《数据结构（C语言版）》教材九成新', '考研复习用书，无笔记无划线，可小刀',
        (SELECT id FROM category WHERE name = '专业课教材' AND parent_id = 1),
        (SELECT id FROM `user` WHERE username = 'stu_seller'),
        15.00, 45.00, '/upload/ds-book.jpg', 2, '东校区', '东校区图书馆门口', 0);

INSERT INTO product_image (product_id, url, sort_order)
SELECT id, '/upload/ds-book.jpg', 0 FROM `product` WHERE title LIKE '《数据结构%';

-- 5.3 管理员审核通过（status -> 1 在售）
UPDATE `product` SET status = 1, auditor_id = (SELECT id FROM `user` WHERE username = 'admin'),
       audit_time = NOW(), shelf_time = NOW()
WHERE title LIKE '《数据结构%';

-- 5.4 买家在商品下留言，卖家回复（parent_id 关联，验证盖楼）
INSERT INTO `message` (type, product_id, from_user_id, to_user_id, parent_id, content)
SELECT 1, p.id, b.id, s.id, 0, '请问书里有笔记吗？'
FROM `product` p, `user` b, `user` s
WHERE p.title LIKE '《数据结构%' AND b.username = 'stu_buyer' AND s.username = 'stu_seller';

INSERT INTO `message` (type, product_id, from_user_id, to_user_id, parent_id, content)
SELECT 1, p.id, s.id, b.id, (SELECT MAX(id) FROM `message` WHERE type = 1), '没有笔记，只有封面轻微磨损，可以放心'
FROM `product` p, `user` s, `user` b
WHERE p.title LIKE '《数据结构%' AND s.username = 'stu_seller' AND b.username = 'stu_buyer';

-- 5.5 私信（type = 2）
INSERT INTO `message` (type, product_id, from_user_id, to_user_id, parent_id, content)
SELECT 2, p.id, b.id, s.id, 0, '私信：明天下午三点图书馆门口交易可以吗？'
FROM `product` p, `user` b, `user` s
WHERE p.title LIKE '《数据结构%' AND b.username = 'stu_buyer' AND s.username = 'stu_seller';

-- 5.6 下单（商品状态 -> 4 交易中，订单快照标题与价格）
START TRANSACTION;
INSERT INTO `orders` (order_no, product_id, buyer_id, seller_id, product_title, product_image,
                      amount, status, delivery_type, trade_place, buyer_remark)
SELECT '20250601120000000001', p.id, b.id, s.id, p.title, p.cover_image,
       p.price, 0, 1, '东校区图书馆门口', '请带好书本'
FROM `product` p, `user` b, `user` s
WHERE p.title LIKE '《数据结构%' AND b.username = 'stu_buyer' AND s.username = 'stu_seller';

UPDATE `product` SET status = 4
WHERE title LIKE '《数据结构%' AND status = 1;
COMMIT;

-- 5.7 模拟支付 -> 卖家交付 -> 买家确认收货（订单状态机 0 -> 1 -> 2 -> 3）
UPDATE `orders` SET status = 1, pay_type = 1, pay_time = NOW() WHERE order_no = '20250601120000000001';
UPDATE `orders` SET status = 2, deliver_time = NOW()             WHERE order_no = '20250601120000000001';
UPDATE `orders` SET status = 3, finish_time = NOW()              WHERE order_no = '20250601120000000001';

START TRANSACTION;
UPDATE `product` SET status = 5, sold_time = NOW()
WHERE id = (SELECT product_id FROM `orders` WHERE order_no = '20250601120000000001');
UPDATE `user` SET credit_score = credit_score + 1
WHERE id IN (SELECT buyer_id FROM `orders` WHERE order_no = '20250601120000000001'
             UNION SELECT seller_id FROM `orders` WHERE order_no = '20250601120000000001');
COMMIT;

SELECT '=== 6. 业务数据核对 ===' AS step;
SELECT o.order_no AS `订单号`, o.product_title AS `商品快照`, o.amount AS `金额`,
       b.nickname AS `买家`, s.nickname AS `卖家`,
       CASE o.status WHEN 0 THEN '待付款' WHEN 1 THEN '待交付' WHEN 2 THEN '待收货'
                     WHEN 3 THEN '已完成' WHEN 4 THEN '已取消' ELSE '已退款' END AS `订单状态`,
       CASE p.status WHEN 0 THEN '待审核' WHEN 1 THEN '在售' WHEN 2 THEN '审核不通过'
                     WHEN 3 THEN '已下架' WHEN 4 THEN '交易中' WHEN 5 THEN '已售出' END AS `商品状态`,
       o.delivery_type AS `交付方式`, o.finish_time IS NOT NULL AS `已完结`
FROM `orders` o
JOIN `user` b ON b.id = o.buyer_id
JOIN `user` s ON s.id = o.seller_id
JOIN `product` p ON p.id = o.product_id;

SELECT '--- 留言/私信（含盖楼回复） ---' AS msg;
SELECT m.type AS `类型`, m.content AS `内容`, f.nickname AS `发送人`, t.nickname AS `接收人`,
       m.parent_id AS `父留言`, m.is_read AS `已读`
FROM `message` m
JOIN `user` f ON f.id = m.from_user_id
LEFT JOIN `user` t ON t.id = m.to_user_id
ORDER BY m.id;

SELECT '--- 收藏（唯一索引防重复） ---' AS fav;
INSERT INTO favorite (user_id, product_id)
SELECT b.id, p.id FROM `user` b, `product` p
WHERE b.username = 'stu_buyer' AND p.title LIKE '《数据结构%';
INSERT INTO favorite (user_id, product_id)
SELECT b.id, p.id FROM `user` b, `product` p
WHERE b.username = 'stu_buyer' AND p.title LIKE '《数据结构%';
SELECT COUNT(*) AS `收藏条数（应为1）` FROM favorite;

SELECT '=== 7. 约束反向验证（以下 3 条语句应报错，属预期失败） ===' AS step;
-- 7.1 用户名唯一：应报 ERROR 1062
INSERT INTO `user` (username, password, nickname) VALUES ('stu_buyer', 'x', '重复用户名');
-- 7.2 外键约束：应报 ERROR 1452（卖家用户不存在）
INSERT INTO `product` (title, category_id, seller_id, price) VALUES ('非法商品', 1, 999999, 1.00);
-- 7.3 非空约束：应报 ERROR 1364（订单缺少买家）
INSERT INTO `orders` (order_no, product_id, seller_id, product_title, amount)
VALUES ('20250601120000000002', 1, 1, '缺买家', 1.00);

SELECT '=== 8. 清理测试数据 ===' AS step;
DELETE FROM `message` WHERE from_user_id IN (SELECT id FROM `user` WHERE username IN ('stu_buyer', 'stu_seller'));
DELETE FROM favorite WHERE user_id IN (SELECT id FROM `user` WHERE username IN ('stu_buyer', 'stu_seller'));
DELETE FROM `orders` WHERE buyer_id IN (SELECT id FROM `user` WHERE username = 'stu_buyer');
DELETE FROM product_image WHERE product_id IN (SELECT id FROM `product` WHERE title LIKE '《数据结构%');
DELETE FROM `product` WHERE title LIKE '《数据结构%';
DELETE FROM `user` WHERE username IN ('stu_buyer', 'stu_seller');
SELECT (SELECT COUNT(*) FROM `user`) AS `剩余用户`, (SELECT COUNT(*) FROM `product`) AS `剩余商品`,
       (SELECT COUNT(*) FROM `orders`) AS `剩余订单`, (SELECT COUNT(*) FROM `message`) AS `剩余消息`;
