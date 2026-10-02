-- =============================================================================
-- 校园二手交易平台 v0.09 辅助功能模块 —— 数据库测试脚本
-- 覆盖：个人资料维护、商品搜索（名称模糊 + 分类筛选）、分类新增/修改
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_v009_test.sql
-- 说明：脚本可重复执行；第 5、6 节的插入会故意报错，属预期结果
-- =============================================================================

USE campus_trade;

SELECT '================ 1. 个人资料相关字段与唯一索引 ================' AS step;
SELECT COLUMN_NAME AS `字段`, COLUMN_TYPE AS `类型`, IS_NULLABLE AS `可空`, COLUMN_COMMENT AS `注释`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'user'
  AND COLUMN_NAME IN ('id', 'username', 'nickname', 'avatar', 'real_name', 'student_no',
                      'gender', 'school', 'campus', 'phone', 'email', 'credit_score')
ORDER BY ORDINAL_POSITION;

SELECT INDEX_NAME AS `索引`, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS `列`,
       IF(NON_UNIQUE = 0, '唯一', '普通') AS `类型`
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'user'
GROUP BY INDEX_NAME, NON_UNIQUE;

SELECT '================ 2. 当前用户资料（含 v0.09 可编辑字段） ================' AS step;
SELECT id AS `用户ID`, username AS `用户名`, nickname AS `昵称`,
       IFNULL(real_name, '未填写') AS `真实姓名`,
       IFNULL(student_no, '未填写') AS `学号`,
       CASE gender WHEN 1 THEN '男' WHEN 2 THEN '女' ELSE '保密' END AS `性别`,
       IFNULL(school, '未填写') AS `学校`,
       IFNULL(campus, '未填写') AS `校区`,
       IFNULL(phone, '未填写') AS `手机号`,
       IFNULL(email, '未填写') AS `邮箱`,
       IF(avatar IS NULL, '（未上传头像）', avatar) AS `头像`,
       credit_score AS `信用分`
FROM `user` WHERE deleted = 0 ORDER BY id;

SELECT '================ 3. 商品搜索的等价 SQL（v0.09 /api/product/search） ================' AS step;
SELECT '--- 3.1 关键词「教材」：只匹配名称，且只返回上架商品 ---' AS `说明`;
SELECT p.id AS `商品ID`, p.title AS `商品名称`, p.price AS `售价`,
       c.name AS `分类`, u.nickname AS `卖家`, p.status AS `状态`
FROM product p
LEFT JOIN category c ON c.id = p.category_id
LEFT JOIN `user` u ON u.id = p.seller_id
WHERE p.deleted = 0 AND p.status = 1
  AND p.title LIKE CONCAT('%', '教材', '%')
ORDER BY p.create_time DESC, p.id DESC;

SELECT '--- 3.2 分类筛选：一级分类「教材书籍」(id=1) 自动包含其子分类 ---' AS `说明`;
SELECT p.id AS `商品ID`, p.title AS `商品名称`, c.name AS `分类`
FROM product p
LEFT JOIN category c ON c.id = p.category_id
WHERE p.deleted = 0 AND p.status = 1
  AND (p.category_id = 1 OR p.category_id IN (SELECT id FROM category WHERE parent_id = 1))
ORDER BY p.create_time DESC, p.id DESC;

SELECT '--- 3.3 分页 + 价格升序（等价 LIMIT (page-1)*size, size） ---' AS `说明`;
SELECT p.id AS `商品ID`, p.title AS `商品名称`, p.price AS `售价`
FROM product p
WHERE p.deleted = 0 AND p.status = 1
ORDER BY p.price ASC, p.id DESC
LIMIT 0, 5;

SELECT '--- 3.4 搜索命中统计（各关键词） ---' AS `说明`;
SELECT '教材' AS `关键词`, COUNT(*) AS `命中条数` FROM product
  WHERE deleted = 0 AND status = 1 AND title LIKE '%教材%'
UNION ALL
SELECT '台灯', COUNT(*) FROM product WHERE deleted = 0 AND status = 1 AND title LIKE '%台灯%'
UNION ALL
SELECT '键盘', COUNT(*) FROM product WHERE deleted = 0 AND status = 1 AND title LIKE '%键盘%'
UNION ALL
SELECT '（含已售出）键盘', COUNT(*) FROM product WHERE deleted = 0 AND title LIKE '%键盘%';

SELECT '================ 4. 分类表结构与层级统计 ================' AS step;
SELECT COUNT(*) AS `分类总数`, SUM(parent_id = 0) AS `一级分类`, SUM(parent_id > 0) AS `二级分类`,
       SUM(status = 1) AS `启用中`
FROM category WHERE deleted = 0;

SELECT '--- 分类树（一级 + 其子分类数量） ---' AS `说明`;
SELECT c.id AS `分类ID`, c.name AS `一级分类`, c.sort_order AS `排序`,
       (SELECT COUNT(*) FROM category x WHERE x.parent_id = c.id AND x.deleted = 0) AS `子分类数`,
       (SELECT COUNT(*) FROM product p WHERE p.category_id = c.id AND p.deleted = 0) AS `直属商品数`
FROM category c
WHERE c.deleted = 0 AND c.parent_id = 0
ORDER BY c.sort_order, c.id;

SELECT '================ 5. 分类新增/修改的等价 SQL 与约束验证 ================' AS step;
-- 5.1 新增分类（后端 POST /api/category/add 的等价 SQL）
DELETE FROM category WHERE name IN ('SQL演示分类', 'SQL演示子分类');
INSERT INTO category (parent_id, name, icon, sort_order, status)
VALUES (0, 'SQL演示分类', '/demo-images/default.png', 50, 1);

SELECT id AS `新增分类ID`, parent_id, name, sort_order, status
FROM category WHERE name = 'SQL演示分类';

-- 5.2 新增二级分类
INSERT INTO category (parent_id, name, sort_order, status)
SELECT id, 'SQL演示子分类', 1, 1 FROM category WHERE name = 'SQL演示分类';

SELECT id AS `二级分类ID`, parent_id, name FROM category WHERE name = 'SQL演示子分类';

-- 5.3 修改分类（后端 PUT /api/category/update 的等价 SQL）
UPDATE category SET name = 'SQL演示分类（已改名）', sort_order = 48, status = 1, update_time = NOW()
WHERE name = 'SQL演示分类';

SELECT id, name AS `改名后`, sort_order AS `排序`, status AS `状态`
FROM category WHERE name LIKE 'SQL演示分类%';

-- 5.4 同级重名 → 期望 1062（数据库唯一索引 uk_parent_name 兜底）
INSERT INTO category (parent_id, name) VALUES (0, 'SQL演示分类（已改名）');

SELECT '--- 5.5 上级分类不存在 → 期望 1452 ---' AS `说明`;
INSERT INTO category (parent_id, name) VALUES (999999, '孤儿分类');

SELECT '--- 5.6 清理演示分类 ---' AS `说明`;
DELETE FROM category WHERE name IN ('SQL演示子分类', 'SQL演示分类（已改名）');
SELECT COUNT(*) AS `剩余分类数` FROM category WHERE deleted = 0;

SELECT '================ 6. 手机号唯一索引验证（个人资料改手机号） ================' AS step;
-- 期望 1062：把 stu_demo 的手机号直接写到 admin 上（后端会先查重返回 2006）
UPDATE `user` SET phone = (SELECT phone FROM (SELECT phone FROM `user` WHERE username = 'stu_demo') t)
WHERE username = 'admin';

SELECT '================ 7. 资料完整度统计（可用于论文"数据概览"章节） ================' AS step;
SELECT COUNT(*) AS `用户总数`,
       SUM(avatar IS NOT NULL) AS `已上传头像数`,
       SUM(phone IS NOT NULL) AS `已填手机号数`,
       SUM(email IS NOT NULL) AS `已填邮箱数`,
       SUM(campus IS NOT NULL) AS `已填校区数`,
       SUM(real_name IS NOT NULL) AS `已填真实姓名数`
FROM `user` WHERE deleted = 0;

SELECT '================ 8. 上传文件与头像地址统计 ================' AS step;
SELECT COUNT(*) AS `有头像的用户数`,
       SUM(avatar LIKE '/upload/%') AS `真实上传的头像数`,
       SUM(avatar LIKE '/demo-images/%') AS `使用占位图的头像数`
FROM `user` WHERE deleted = 0 AND avatar IS NOT NULL;

SELECT '头像地址示例' AS `说明`, id, username, avatar FROM `user`
WHERE deleted = 0 AND avatar IS NOT NULL ORDER BY id;

-- =============================================================================
-- 附：常用排查 SQL
--   搜索某关键词（含描述）：SELECT id,title FROM product
--       WHERE deleted=0 AND status=1 AND (title LIKE '%关键字%' OR description LIKE '%关键字%');
--   查看某分类下所有商品（含子分类）：
--       SELECT p.id,p.title FROM product p WHERE p.category_id = 1
--          OR p.category_id IN (SELECT id FROM category WHERE parent_id = 1);
--   还原测试数据：UPDATE `user` SET nickname='测试同学01', campus='东校区', school=NULL,
--       email=NULL, gender=0 WHERE username = 'stu_test01';
-- =============================================================================
