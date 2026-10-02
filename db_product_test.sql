-- =============================================================================
-- 校园二手交易平台 v0.05 商品模块 —— 数据库测试脚本
-- 用途：验证商品/商品图片/分类相关表结构与数据；并提供一批使用占位图的演示商品
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_product_test.sql
-- 说明：脚本可重复执行（演示数据会先按标题删除再插入）
-- =============================================================================

USE campus_trade;

SELECT '================ 1. product 表结构（v0.05 关注字段） ================' AS step;
SELECT COLUMN_NAME AS `字段`, COLUMN_TYPE AS `类型`, IS_NULLABLE AS `可空`,
       COLUMN_DEFAULT AS `默认值`, COLUMN_COMMENT AS `注释`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'product'
  AND COLUMN_NAME IN ('id', 'title', 'category_id', 'seller_id', 'price', 'original_price',
                      'cover_image', 'condition_level', 'status', 'view_count',
                      'shelf_time', 'create_time', 'deleted')
ORDER BY ORDINAL_POSITION;

SELECT '================ 2. product_image 表结构 ================' AS step;
SELECT COLUMN_NAME AS `字段`, COLUMN_TYPE AS `类型`, IS_NULLABLE AS `可空`, COLUMN_COMMENT AS `注释`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'product_image'
ORDER BY ORDINAL_POSITION;

SELECT '================ 3. 商品与图片的外键约束 ================' AS step;
SELECT TABLE_NAME AS `表`, COLUMN_NAME AS `列`, CONSTRAINT_NAME AS `约束`,
       REFERENCED_TABLE_NAME AS `引用表`, REFERENCED_COLUMN_NAME AS `引用列`
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = 'campus_trade' AND REFERENCED_TABLE_NAME IS NOT NULL
  AND TABLE_NAME IN ('product', 'product_image')
ORDER BY TABLE_NAME, CONSTRAINT_NAME;

SELECT '================ 4. 分类数据统计 ================' AS step;
SELECT COUNT(*) AS `分类总数`, SUM(parent_id = 0) AS `一级分类`, SUM(parent_id > 0) AS `二级分类`
FROM category WHERE deleted = 0;

SELECT '================ 5. 当前商品总览（含状态与图片数） ================' AS step;
SELECT p.id                                    AS `商品ID`,
       p.title                                 AS `商品名称`,
       c.name                                  AS `分类`,
       u.username                              AS `卖家`,
       p.price                                 AS `售价`,
       CASE p.status WHEN 0 THEN '待审核' WHEN 1 THEN '在售' WHEN 2 THEN '审核不通过'
                     WHEN 3 THEN '已下架' WHEN 4 THEN '交易中' WHEN 5 THEN '已售出' END AS `状态`,
       IFNULL(p.cover_image, '（空-前端显示占位图）') AS `封面图`,
       (SELECT COUNT(*) FROM product_image i WHERE i.product_id = p.id AND i.deleted = 0) AS `图片数`,
       p.view_count                            AS `浏览量`,
       p.create_time                           AS `发布时间`
FROM product p
LEFT JOIN category c ON c.id = p.category_id
LEFT JOIN user u ON u.id = p.seller_id
WHERE p.deleted = 0
ORDER BY p.id;

SELECT '================ 6. 上架商品列表（后端 /api/product/list 的等价 SQL） ================' AS step;
-- 后端实际执行的是 MyBatis-Plus 分页 + 分类/卖家信息批量补齐，这里用等价 SQL 直观展示
SELECT p.id AS `商品ID`, p.title AS `商品名称`, p.price AS `售价`,
       c.name AS `分类名称`, u.nickname AS `卖家昵称`, p.create_time AS `发布时间`
FROM product p
LEFT JOIN category c ON c.id = p.category_id
LEFT JOIN user u ON u.id = p.seller_id
WHERE p.deleted = 0 AND p.status = 1
ORDER BY p.create_time DESC, p.id DESC
LIMIT 12;

SELECT '================ 7. 商品图片明细（product_image） ================' AS step;
SELECT i.id AS `图片ID`, i.product_id AS `商品ID`, p.title AS `商品名称`,
       i.url AS `图片地址`, i.sort_order AS `排序`
FROM product_image i
LEFT JOIN product p ON p.id = i.product_id
WHERE i.deleted = 0
ORDER BY i.product_id, i.sort_order;

SELECT '================ 8. 插入演示商品（使用 frontend/public/demo-images 占位图） ================' AS step;
-- 先清理同名演示数据，保证可重复执行
DELETE FROM product_image WHERE product_id IN (
    SELECT id FROM product WHERE title IN (
        '《计算机网络（第7版）》教材 九成新', '漫步者蓝牙耳机 半入耳式', '罗技 K380 蓝牙键盘',
        '宿舍陶瓷马克杯（全新未用）', '优衣库摇粒绒外套 M码', '迪卡侬哑铃一对 5kg',
        '宿舍护眼台灯（三档调光）', '珀莱雅精华水 余量九成', '全新文具套装（笔记本+中性笔）'));
DELETE FROM product WHERE title IN (
    '《计算机网络（第7版）》教材 九成新', '漫步者蓝牙耳机 半入耳式', '罗技 K380 蓝牙键盘',
    '宿舍陶瓷马克杯（全新未用）', '优衣库摇粒绒外套 M码', '迪卡侬哑铃一对 5kg',
    '宿舍护眼台灯（三档调光）', '珀莱雅精华水 余量九成', '全新文具套装（笔记本+中性笔）');

INSERT INTO product (title, description, category_id, seller_id, price, original_price, cover_image,
                     condition_level, campus, trade_place, status, view_count, favorite_count,
                     shelf_time, create_time)
VALUES
('《计算机网络（第7版）》教材 九成新',
 '谢希仁版教材，考研复试复习用，书页干净无笔记，仅封面有轻微折痕。',
 (SELECT id FROM category WHERE name = '公共课教材'), (SELECT id FROM user WHERE username = 'stu_test01'),
 18.00, 49.00, '/demo-images/textbook.png', 2, '东校区', '东校区图书馆门口', 1, 36, 4,
 NOW(), NOW() - INTERVAL 6 DAY),

('漫步者蓝牙耳机 半入耳式',
 '自用三个月，音质正常，续航约 5 小时，附充电盒与说明。',
 (SELECT id FROM category WHERE name = '耳机音响'), (SELECT id FROM user WHERE username = 'stu_test01'),
 65.00, 149.00, '/demo-images/digital.png', 3, '东校区', '东校区一食堂门口', 1, 58, 7,
 NOW(), NOW() - INTERVAL 5 DAY),

('罗技 K380 蓝牙键盘',
 '三设备切换，按键手感好，适合连平板记笔记，成色很新。',
 (SELECT id FROM category WHERE name = '键盘鼠标'), (SELECT id FROM user WHERE username = 'admin'),
 129.00, 259.00, '/demo-images/computer.png', 2, '主校区', '主校区南门', 1, 41, 5,
 NOW(), NOW() - INTERVAL 4 DAY),

('宿舍陶瓷马克杯（全新未用）',
 '室友送的重复了，一次都没用过，360ml 白色杯身。',
 (SELECT id FROM category WHERE name = '生活用品'), (SELECT id FROM user WHERE username = 'stu_test01'),
 12.00, 29.00, '/demo-images/daily.png', 1, '东校区', '3号宿舍楼下', 1, 22, 2,
 NOW(), NOW() - INTERVAL 4 DAY),

('优衣库摇粒绒外套 M码',
 '去年冬天买的，穿过几次，无起球无破损，M 码偏宽松。',
 (SELECT id FROM category WHERE name = '男装'), (SELECT id FROM user WHERE username = 'admin'),
 55.00, 199.00, '/demo-images/clothes.png', 3, '主校区', '主校区体育馆门口', 1, 33, 3,
 NOW(), NOW() - INTERVAL 3 DAY),

('迪卡侬哑铃一对 5kg',
 '宿舍健身用，包胶无味，握把完好，两只一起出。',
 (SELECT id FROM category WHERE name = '健身器材'), (SELECT id FROM user WHERE username = 'stu_test01'),
 45.00, 99.00, '/demo-images/sport.png', 3, '东校区', '东校区操场西门', 1, 47, 6,
 NOW(), NOW() - INTERVAL 3 DAY),

('宿舍护眼台灯（三档调光）',
 'USB 供电，三档亮度可调，可夹床架，毕业搬家出。',
 (SELECT id FROM category WHERE name = '宿舍家具'), (SELECT id FROM user WHERE username = 'stu_test01'),
 25.50, 69.00, '/demo-images/dorm.png', 2, '东校区', '3号宿舍楼下', 1, 61, 9,
 NOW(), NOW() - INTERVAL 2 DAY),

('珀莱雅精华水 余量九成',
 '专柜购入，用了两三次，余量约九成，介意勿拍。',
 (SELECT id FROM category WHERE name = '护肤'), (SELECT id FROM user WHERE username = 'admin'),
 78.00, 169.00, '/demo-images/beauty.png', 3, '主校区', '主校区快递驿站', 1, 29, 3,
 NOW(), NOW() - INTERVAL 2 DAY),

('全新文具套装（笔记本+中性笔）',
 '开学多买的，A5 笔记本 2 本 + 黑色中性笔 5 支，未拆封。',
 (SELECT id FROM category WHERE name = '文具用品'), (SELECT id FROM user WHERE username = 'admin'),
 9.90, 25.00, '/demo-images/default.png', 1, '主校区', '主校区教学楼下', 1, 18, 1,
 NOW(), NOW() - INTERVAL 1 DAY);

-- 给其中两个演示商品补充 product_image（演示详情页大图 + 缩略图切换）
INSERT INTO product_image (product_id, url, sort_order)
SELECT p.id, '/demo-images/textbook.png', 0 FROM product p WHERE p.title = '《计算机网络（第7版）》教材 九成新';
INSERT INTO product_image (product_id, url, sort_order)
SELECT p.id, '/demo-images/default.png', 1 FROM product p WHERE p.title = '《计算机网络（第7版）》教材 九成新';
INSERT INTO product_image (product_id, url, sort_order)
SELECT p.id, '/demo-images/dorm.png', 0 FROM product p WHERE p.title = '宿舍护眼台灯（三档调光）';
INSERT INTO product_image (product_id, url, sort_order)
SELECT p.id, '/demo-images/daily.png', 1 FROM product p WHERE p.title = '宿舍护眼台灯（三档调光）';

SELECT p.id AS `演示商品ID`, p.title AS `名称`, p.price AS `售价`, p.cover_image AS `封面`,
       c.name AS `分类`, u.username AS `卖家`,
       (SELECT COUNT(*) FROM product_image i WHERE i.product_id = p.id) AS `图片数`
FROM product p
LEFT JOIN category c ON c.id = p.category_id
LEFT JOIN user u ON u.id = p.seller_id
WHERE p.title IN ('《计算机网络（第7版）》教材 九成新', '漫步者蓝牙耳机 半入耳式', '罗技 K380 蓝牙键盘',
                  '宿舍陶瓷马克杯（全新未用）', '优衣库摇粒绒外套 M码', '迪卡侬哑铃一对 5kg',
                  '宿舍护眼台灯（三档调光）', '珀莱雅精华水 余量九成', '全新文具套装（笔记本+中性笔）')
ORDER BY p.id;

SELECT '================ 9. 上下架（后端 PUT /api/product/status 等价的 SQL） ================' AS step;
-- 下架：仅限自己的商品（应用层校验 seller_id = 当前登录用户）
UPDATE product SET status = 3, update_time = NOW()
WHERE title = '宿舍陶瓷马克杯（全新未用）' AND status = 1;

SELECT id, title, status AS `下架后状态` FROM product WHERE title = '宿舍陶瓷马克杯（全新未用）';

-- 上架：刷新上架时间
UPDATE product SET status = 1, shelf_time = NOW(), update_time = NOW()
WHERE title = '宿舍陶瓷马克杯（全新未用）' AND status = 3;

SELECT id, title, status AS `重新上架后状态`, shelf_time AS `上架时间`
FROM product WHERE title = '宿舍陶瓷马克杯（全新未用）';

SELECT '================ 10. 约束验证（预期报错，属正常） ================' AS step;
-- 10.1 分类不存在 → 期望 1452 外键错误
INSERT INTO product (title, category_id, seller_id, price, condition_level, status)
VALUES ('非法分类测试商品', 999999, (SELECT id FROM user WHERE username = 'admin'), 10.00, 1, 1);
-- 10.2 卖家不存在 → 期望 1452 外键错误
INSERT INTO product (title, category_id, seller_id, price, condition_level, status)
VALUES ('非法卖家测试商品', (SELECT id FROM category WHERE name = '其他'), 999999, 10.00, 1, 1);
-- 10.3 商品图片指向不存在的商品 → 期望 1452 外键错误
INSERT INTO product_image (product_id, url, sort_order) VALUES (999999, '/demo-images/default.png', 0);

SELECT '================ 11. 统计校验 ================' AS step;
SELECT (SELECT COUNT(*) FROM product WHERE deleted = 0) AS `商品总数`,
       (SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 1) AS `在售商品数`,
       (SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 3) AS `已下架数`,
       (SELECT COUNT(*) FROM product_image WHERE deleted = 0) AS `图片记录数`,
       (SELECT COUNT(DISTINCT category_id) FROM product WHERE deleted = 0) AS `覆盖分类数`;

-- =============================================================================
-- 附：清理演示数据（默认注释掉；执行后首页将只剩接口发布的商品）
-- =============================================================================
-- DELETE FROM product_image WHERE product_id IN (
--     SELECT id FROM product WHERE cover_image LIKE '/demo-images/%');
-- DELETE FROM product WHERE cover_image LIKE '/demo-images/%';

-- =============================================================================
-- 附：常用排查 SQL
--   某商品的图片：   SELECT * FROM product_image WHERE product_id = 1 ORDER BY sort_order;
--   某卖家的商品：   SELECT id,title,status FROM product WHERE seller_id = 5 AND deleted = 0;
--   按分类统计：     SELECT c.name, COUNT(*) FROM product p JOIN category c ON c.id=p.category_id
--                    WHERE p.status=1 GROUP BY c.name ORDER BY COUNT(*) DESC;
--   浏览量最高的：   SELECT id,title,view_count FROM product WHERE status=1 ORDER BY view_count DESC LIMIT 10;
--   逻辑删除商品：   UPDATE product SET deleted = 1 WHERE id = 1;
-- =============================================================================
