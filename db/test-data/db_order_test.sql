-- =============================================================================
-- 校园二手交易平台 v0.08 订单交易模块 —— 数据库测试脚本
-- 用途：验证 orders 表结构、订单三态流转、商品状态联动、信用分变化与约束
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_order_test.sql
-- 说明：脚本可重复执行；第 8 节的外键/唯一键插入会故意报错，属预期结果
-- =============================================================================

USE campus_trade;

SELECT '================ 1. orders 表结构（v0.08 关注字段） ================' AS step;
SELECT COLUMN_NAME AS `字段`, COLUMN_TYPE AS `类型`, COLUMN_COMMENT AS `注释`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'orders'
  AND COLUMN_NAME IN ('id', 'order_no', 'product_id', 'buyer_id', 'seller_id',
                      'product_title', 'product_image', 'amount', 'status',
                      'delivery_type', 'trade_place', 'buyer_remark',
                      'finish_time', 'cancel_time', 'cancel_reason', 'create_time', 'deleted')
ORDER BY ORDINAL_POSITION;

SELECT '================ 2. 当前订单总览（含商品与买卖双方） ================' AS step;
SELECT o.id            AS `订单ID`,
       o.order_no      AS `订单号`,
       o.product_title AS `商品快照`,
       o.amount        AS `金额`,
       bu.username     AS `买家`,
       su.username     AS `卖家`,
       CASE o.status WHEN 0 THEN '待交易' WHEN 3 THEN '已完成' WHEN 4 THEN '已取消'
                     ELSE CONCAT('状态', o.status) END AS `订单状态`,
       CASE p.status WHEN 1 THEN '在售' WHEN 3 THEN '已下架' WHEN 4 THEN '交易中'
                     WHEN 5 THEN '已售出' ELSE CONCAT('状态', p.status) END AS `商品当前状态`,
       o.create_time   AS `下单时间`,
       o.finish_time   AS `完成时间`,
       o.cancel_time   AS `取消时间`
FROM orders o
JOIN `user` bu ON bu.id = o.buyer_id
JOIN `user` su ON su.id = o.seller_id
LEFT JOIN product p ON p.id = o.product_id
WHERE o.deleted = 0
ORDER BY o.id;

SELECT '================ 3. 买家订单列表（后端 /api/order/buyList 的等价 SQL） ================' AS step;
SELECT o.id AS `订单ID`, o.order_no AS `订单号`, o.product_title AS `商品`,
       o.amount AS `金额`, su.nickname AS `卖家昵称`,
       CASE o.status WHEN 0 THEN '待交易' WHEN 3 THEN '已完成' WHEN 4 THEN '已取消' END AS `状态`,
       o.create_time AS `下单时间`
FROM orders o
JOIN `user` su ON su.id = o.seller_id
WHERE o.deleted = 0 AND o.buyer_id = (SELECT id FROM `user` WHERE username = 'stu_test01')
ORDER BY o.create_time DESC, o.id DESC;

SELECT '================ 4. 卖家订单列表（后端 /api/order/sellList 的等价 SQL） ================' AS step;
SELECT o.id AS `订单ID`, o.order_no AS `订单号`, o.product_title AS `商品`,
       o.amount AS `金额`, bu.nickname AS `买家昵称`,
       CASE o.status WHEN 0 THEN '待交易' WHEN 3 THEN '已完成' WHEN 4 THEN '已取消' END AS `状态`,
       o.create_time AS `下单时间`
FROM orders o
JOIN `user` bu ON bu.id = o.buyer_id
WHERE o.deleted = 0 AND o.seller_id = (SELECT id FROM `user` WHERE username = 'admin')
ORDER BY o.create_time DESC, o.id DESC;

SELECT '================ 5. 订单详情（含交易地点、备注、取消原因） ================' AS step;
SELECT o.id AS `订单ID`, o.order_no AS `订单号`, o.product_title AS `商品`,
       o.amount AS `金额`, o.delivery_type AS `交付方式`, o.trade_place AS `交易地点`,
       o.buyer_remark AS `买家备注`, o.cancel_reason AS `取消原因`,
       bu.nickname AS `买家`, su.nickname AS `卖家`
FROM orders o
JOIN `user` bu ON bu.id = o.buyer_id
JOIN `user` su ON su.id = o.seller_id
WHERE o.deleted = 0
ORDER BY o.id;

SELECT '================ 6. 订单状态与商品状态联动校验 ================' AS step;
-- 规则：订单待交易 → 商品交易中(4)；订单已完成 → 商品已售出(5)；订单已取消 → 商品回到在售(1)
SELECT o.id AS `订单ID`,
       o.status AS `订单状态码`,
       p.status AS `商品状态码`,
       CASE
           WHEN o.status = 0 AND p.status = 4 THEN '✓ 一致（待交易 / 交易中）'
           WHEN o.status = 3 AND p.status = 5 THEN '✓ 一致（已完成 / 已售出）'
           WHEN o.status = 4 AND p.status = 1 THEN '✓ 一致（已取消 / 回到在售）'
           WHEN o.status = 4 AND p.status <> 1 THEN '注意：已取消但商品不在售（可能已下架）'
           ELSE '✗ 不一致，需要检查'
       END AS `联动校验`
FROM orders o JOIN product p ON p.id = o.product_id
WHERE o.deleted = 0 ORDER BY o.id;

SELECT '================ 7. 三态流转 SQL 演示（新建一笔待交易订单） ================' AS step;
-- 先清理同名演示订单，并把商品 14 恢复到在售
DELETE FROM orders WHERE buyer_id = (SELECT id FROM `user` WHERE username = 'stu_demo')
                     AND product_id = 14;
UPDATE product SET status = 1 WHERE id = 14;

-- ① 下单：商品 → 交易中，订单 → 待交易
UPDATE product SET status = 4 WHERE id = 14 AND status = 1;
INSERT INTO orders (order_no, product_id, buyer_id, seller_id, product_title, product_image,
                    amount, status, delivery_type, trade_place, buyer_remark)
SELECT CONCAT(DATE_FORMAT(NOW(), '%Y%m%d%H%i%s'), '0001'),
       p.id, d.id, s.id, p.title, p.cover_image, p.price, 0, 1, p.trade_place, '（演示数据）我明天下午来取'
FROM product p, `user` d, `user` s
WHERE p.id = 14 AND d.username = 'stu_demo' AND s.username = 'admin';

SELECT o.id AS `新订单ID`, o.order_no AS `订单号`, o.status AS `订单状态码`,
       p.status AS `商品状态码（应为4交易中）`, o.buyer_remark AS `买家备注`
FROM orders o JOIN product p ON p.id = o.product_id
WHERE o.product_id = 14 AND o.deleted = 0;

-- ② 取消订单：订单 → 已取消(4)，商品 → 回到在售(1)
UPDATE orders SET status = 4, cancel_time = NOW(), cancel_reason = '演示：临时取消'
WHERE product_id = 14 AND status = 0 AND buyer_id = (SELECT id FROM `user` WHERE username = 'stu_demo');
UPDATE product SET status = 1 WHERE id = 14 AND status = 4;

SELECT o.status AS `订单状态码（应为4）`, o.cancel_reason AS `取消原因`,
       p.status AS `商品状态码（应为1在售）`
FROM orders o JOIN product p ON p.id = o.product_id
WHERE o.product_id = 14 AND o.deleted = 0;

-- ③ 重新下一笔待交易订单（留给前端演示"完成/取消"按钮）
UPDATE product SET status = 4 WHERE id = 14 AND status = 1;
INSERT INTO orders (order_no, product_id, buyer_id, seller_id, product_title, product_image,
                    amount, status, delivery_type, trade_place, buyer_remark)
SELECT CONCAT(DATE_FORMAT(NOW(), '%Y%m%d%H%i%s'), '0002'),
       p.id, d.id, s.id, p.title, p.cover_image, p.price, 0, 1, p.trade_place, '（演示数据）可以约今晚吗'
FROM product p, `user` d, `user` s
WHERE p.id = 14 AND d.username = 'stu_demo' AND s.username = 'admin';

SELECT '最终演示订单（待交易）' AS `说明`, o.id, o.order_no, o.product_title, o.status
FROM orders o WHERE o.product_id = 14 AND o.deleted = 0;

SELECT '================ 8. 约束与规则校验（预期报错，属正常） ================' AS step;
-- 8.1 商品不存在 → 期望 1452
INSERT INTO orders (order_no, product_id, buyer_id, seller_id, product_title, amount, status)
VALUES ('TEST-0001', 999999, 5, 1, '非法商品', 1.00, 0);
-- 8.2 买家不存在 → 期望 1452
INSERT INTO orders (order_no, product_id, buyer_id, seller_id, product_title, amount, status)
VALUES ('TEST-0002', 1, 999999, 1, '非法买家', 1.00, 0);
-- 8.3 订单号重复 → 期望 1062
INSERT INTO orders (order_no, product_id, buyer_id, seller_id, product_title, amount, status)
SELECT order_no, product_id, buyer_id, seller_id, product_title, amount, status
FROM orders WHERE deleted = 0 LIMIT 1;

SELECT '================ 9. 业务规则排查 SQL ================' AS step;
-- 9.1 自己买自己的商品（正常应为 0 条，后端会拦 6002）
SELECT COUNT(*) AS `自买自卖异常条数` FROM orders WHERE buyer_id = seller_id AND deleted = 0;

-- 9.2 同一个商品存在多笔"待交易"订单（正常应为 0 条，条件更新会防止一物多卖）
SELECT COUNT(*) AS `一物多卖异常组数` FROM (
    SELECT product_id FROM orders WHERE deleted = 0 AND status = 0
    GROUP BY product_id HAVING COUNT(*) > 1
) t;

-- 9.3 商品处于"交易中"却没有待交易订单（正常应为 0 条）
SELECT COUNT(*) AS `状态悬挂条数`
FROM product p
WHERE p.deleted = 0 AND p.status = 4
  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.product_id = p.id AND o.status = 0 AND o.deleted = 0);

SELECT '================ 10. 统计总览 ================' AS step;
SELECT (SELECT COUNT(*) FROM orders WHERE deleted = 0) AS `订单总数`,
       (SELECT COUNT(*) FROM orders WHERE deleted = 0 AND status = 0) AS `待交易`,
       (SELECT COUNT(*) FROM orders WHERE deleted = 0 AND status = 3) AS `已完成`,
       (SELECT COUNT(*) FROM orders WHERE deleted = 0 AND status = 4) AS `已取消`,
       (SELECT IFNULL(SUM(amount), 0) FROM orders WHERE deleted = 0 AND status = 3) AS `已完成成交额`,
       (SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 5) AS `已售出商品数`,
       (SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 4) AS `交易中商品数`;

SELECT '用户信用分（完成订单后 +1）' AS `说明`;
SELECT id AS `用户ID`, username AS `用户名`, credit_score AS `信用分`
FROM `user` WHERE deleted = 0 ORDER BY id;

-- =============================================================================
-- 附：清理 SQL（按需执行）
--   清空演示订单并释放商品：
--     UPDATE product SET status = 1 WHERE id IN
--       (SELECT product_id FROM orders WHERE status = 0 AND deleted = 0);
--     DELETE FROM orders WHERE deleted = 0;
-- =============================================================================
