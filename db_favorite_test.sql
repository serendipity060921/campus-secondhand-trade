-- =============================================================================
-- 校园二手交易平台 v0.06 收藏模块 —— 数据库测试脚本
-- 用途：验证 favorite 表结构/唯一索引/收藏数据，以及"我的收藏"关联查询与计数一致性
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_favorite_test.sql
-- 说明：脚本可重复执行；第 6 节的重复插入会故意报 1062，属预期结果
-- =============================================================================

USE campus_trade;

SELECT '================ 1. favorite 表结构 ================' AS step;
SELECT COLUMN_NAME AS `字段`, COLUMN_TYPE AS `类型`, IS_NULLABLE AS `可空`,
       COLUMN_DEFAULT AS `默认值`, COLUMN_COMMENT AS `注释`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'favorite'
ORDER BY ORDINAL_POSITION;

SELECT '================ 2. 唯一索引（禁止重复收藏的数据库兜底） ================' AS step;
SELECT INDEX_NAME AS `索引`, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS `列`,
       NON_UNIQUE AS `非唯一`
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'favorite'
GROUP BY INDEX_NAME, NON_UNIQUE;

SELECT '================ 3. 当前收藏数据（含商品状态与卖家） ================' AS step;
SELECT f.id            AS `收藏ID`,
       u.username      AS `收藏人`,
       p.id            AS `商品ID`,
       p.title         AS `商品名称`,
       p.price         AS `售价`,
       CASE p.status WHEN 1 THEN '在售' WHEN 3 THEN '已下架' WHEN 4 THEN '交易中'
                     WHEN 5 THEN '已售出' ELSE CONCAT('状态', p.status) END AS `商品状态`,
       seller.username AS `卖家`,
       f.create_time   AS `收藏时间`
FROM favorite f
JOIN product p ON p.id = f.product_id
JOIN `user` u ON u.id = f.user_id
JOIN `user` seller ON seller.id = p.seller_id
WHERE f.deleted = 0
ORDER BY f.id;

SELECT '================ 4. 我的收藏列表（后端 /api/favorite/list 的等价 SQL） ================' AS step;
-- 关键点：INNER JOIN product 并在 ON 里限定 deleted = 0 AND status = 1
--         这样已下架/已删除的商品不会出现在收藏列表里，且分页总数正确
SELECT f.id          AS `收藏ID`,
       f.create_time AS `收藏时间`,
       p.id          AS `商品ID`,
       p.title       AS `商品名称`,
       p.price       AS `售价`,
       c.name        AS `分类`,
       u.nickname    AS `卖家昵称`,
       p.status      AS `商品状态`
FROM favorite f
INNER JOIN product p ON p.id = f.product_id AND p.deleted = 0 AND p.status = 1
LEFT JOIN category c ON c.id = p.category_id
LEFT JOIN `user` u ON u.id = p.seller_id
WHERE f.deleted = 0 AND f.user_id = (SELECT id FROM `user` WHERE username = 'stu_test01')
ORDER BY f.create_time DESC, f.id DESC;

SELECT '================ 5. 收藏数一致性校验（product.favorite_count 应等于收藏表真实条数） ================' AS step;
SELECT p.id AS `商品ID`, p.title AS `商品名称`,
       p.favorite_count AS `商品表计数`,
       (SELECT COUNT(*) FROM favorite f WHERE f.product_id = p.id AND f.deleted = 0) AS `收藏表真实条数`,
       CASE WHEN p.favorite_count = (SELECT COUNT(*) FROM favorite f WHERE f.product_id = p.id AND f.deleted = 0)
            THEN '一致' ELSE '不一致（见第 8 节修复）' END AS `校验结果`
FROM product p
WHERE p.deleted = 0
ORDER BY p.id
LIMIT 15;

SELECT '================ 6. 重复收藏验证（预期报 1062，数据库层兜底） ================' AS step;
-- 后端在插入前会先查重并返回 4001；这里直接插库，验证唯一索引 uk_user_product 同样能拦住
INSERT INTO favorite (user_id, product_id)
SELECT f.user_id, f.product_id FROM favorite f WHERE f.deleted = 0 LIMIT 1;

SELECT '================ 7. 取消收藏 + 再次收藏（验证物理删除的必要性） ================' AS step;
-- 说明：favorite 表带逻辑删除字段 deleted，但唯一索引不含 deleted，
--       若用逻辑删除，取消后再次收藏会撞唯一索引；所以后端取消收藏采用物理删除。
-- 下面用一条临时数据完整演示这个过程，最后清理。
DELETE FROM favorite WHERE user_id = (SELECT id FROM `user` WHERE username = 'stu_test01')
                        AND product_id = 14;   -- 先清理历史
INSERT INTO favorite (user_id, product_id)
SELECT (SELECT id FROM `user` WHERE username = 'stu_test01'), 14;

SELECT '① 收藏后：' AS `步骤`, COUNT(*) AS `记录数`
FROM favorite WHERE user_id = (SELECT id FROM `user` WHERE username = 'stu_test01') AND product_id = 14;

-- 取消收藏（物理删除）
DELETE FROM favorite
WHERE user_id = (SELECT id FROM `user` WHERE username = 'stu_test01') AND product_id = 14;

SELECT '② 取消后：' AS `步骤`, COUNT(*) AS `记录数`
FROM favorite WHERE user_id = (SELECT id FROM `user` WHERE username = 'stu_test01') AND product_id = 14;

-- 再次收藏（若能成功插入，说明物理删除方案成立）
INSERT INTO favorite (user_id, product_id)
SELECT (SELECT id FROM `user` WHERE username = 'stu_test01'), 14;

SELECT '③ 再次收藏后：' AS `步骤`, COUNT(*) AS `记录数`
FROM favorite WHERE user_id = (SELECT id FROM `user` WHERE username = 'stu_test01') AND product_id = 14;

-- 清理演示数据并同步计数
DELETE FROM favorite WHERE user_id = (SELECT id FROM `user` WHERE username = 'stu_test01') AND product_id = 14;

SELECT '================ 8. 计数修复语句（如果出现不一致可执行） ================' AS step;
UPDATE product p
SET p.favorite_count = (SELECT COUNT(*) FROM favorite f WHERE f.product_id = p.id AND f.deleted = 0);

SELECT p.id AS `商品ID`, p.title AS `商品名称`, p.favorite_count AS `收藏数`
FROM product p WHERE p.deleted = 0 ORDER BY p.id LIMIT 15;

SELECT '================ 9. 业务规则检查 SQL（排查用） ================' AS step;
-- 9.1 是否有人收藏了自己发布的商品（正常应为 0 条，后端会拦 4002）
SELECT COUNT(*) AS `自收藏异常条数`
FROM favorite f JOIN product p ON p.id = f.product_id
WHERE f.user_id = p.seller_id AND f.deleted = 0;

-- 9.2 是否存在重复收藏（正常应为 0 条，唯一索引保证）
SELECT COUNT(*) AS `重复收藏组数` FROM (
    SELECT user_id, product_id FROM favorite WHERE deleted = 0
    GROUP BY user_id, product_id HAVING COUNT(*) > 1
) t;

-- 9.3 每个商品的收藏人数排行
SELECT p.id AS `商品ID`, p.title AS `商品名称`, COUNT(f.id) AS `收藏人数`
FROM product p LEFT JOIN favorite f ON f.product_id = p.id AND f.deleted = 0
WHERE p.deleted = 0 AND p.status = 1
GROUP BY p.id, p.title
ORDER BY `收藏人数` DESC, p.id
LIMIT 10;

SELECT '================ 10. 统计总览 ================' AS step;
SELECT (SELECT COUNT(*) FROM favorite WHERE deleted = 0) AS `收藏记录总数`,
       (SELECT COUNT(DISTINCT user_id) FROM favorite WHERE deleted = 0) AS `参与收藏的人数`,
       (SELECT COUNT(DISTINCT product_id) FROM favorite WHERE deleted = 0) AS `被收藏的商品数`,
       (SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 1) AS `在售商品数`;

-- =============================================================================
-- 附：清理 SQL（按需执行）
--   清空某个用户的收藏： DELETE FROM favorite WHERE user_id = 5;
--   清空全部收藏并重置计数：
--     DELETE FROM favorite; UPDATE product SET favorite_count = 0;
-- =============================================================================
