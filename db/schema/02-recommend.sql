-- =============================================================================
-- 校园二手交易平台 v0.11 推荐模块 —— 用户行为表（推荐算法数据源）
-- 说明：新增表，不影响既有 7 张表；历史行为由 favorite / orders / message 回填
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_v011_recommend.sql
-- =============================================================================

USE campus_trade;

-- -----------------------------------------------------------------------------
-- 1. 用户行为表
--    设计要点：
--      ① 一个用户对一件商品的同一类行为只保留一行（唯一索引），重复发生则累加次数、
--         刷新时间、按当前权重更新 —— 避免浏览行为把表撑爆，同时保留"频次"信号；
--      ② weight 由行为类型决定（浏览 1 / 收藏 3 / 私信 2 / 下单 5），把"兴趣强度"显式化；
--      ③ category_id 冗余商品分类，便于按分类聚合用户偏好，省一次 JOIN。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `user_behavior`;
CREATE TABLE `user_behavior` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '行为ID，主键',
  `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '用户ID（外键 -> user.id）',
  `product_id`    BIGINT UNSIGNED NOT NULL                COMMENT '商品ID（外键 -> product.id）',
  `behavior_type` TINYINT         NOT NULL                COMMENT '行为类型：1浏览 2收藏 3私信 4下单',
  `category_id`   BIGINT UNSIGNED DEFAULT NULL            COMMENT '冗余的商品分类ID，便于聚合用户偏好',
  `weight`        DECIMAL(4,2)    NOT NULL DEFAULT 1.00   COMMENT '行为权重（兴趣强度，1~5）',
  `behavior_count` INT            NOT NULL DEFAULT 1      COMMENT '同类行为累计发生次数',
  `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次发生时间',
  `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近发生时间',
  `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product_type` (`user_id`, `product_id`, `behavior_type`),
  KEY `idx_user_time` (`user_id`, `update_time`),
  KEY `idx_product` (`product_id`),
  KEY `idx_type_time` (`behavior_type`, `update_time`),
  CONSTRAINT `fk_behavior_user`    FOREIGN KEY (`user_id`)    REFERENCES `user` (`id`)    ON DELETE CASCADE,
  CONSTRAINT `fk_behavior_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户行为表（推荐算法数据源）';

-- -----------------------------------------------------------------------------
-- 2. 历史行为回填（离线特征初始化）
--    把已经产生的收藏、下单、私信（带商品）行为补进行为表，让推荐立刻有数据可用
-- -----------------------------------------------------------------------------
DELETE FROM user_behavior;

-- 2.1 收藏行为（权重 3）
INSERT INTO user_behavior (user_id, product_id, behavior_type, category_id, weight, behavior_count, create_time, update_time)
SELECT f.user_id, f.product_id, 2, p.category_id, 3.00, 1, f.create_time, f.create_time
FROM favorite f JOIN product p ON p.id = f.product_id
WHERE f.deleted = 0 AND p.deleted = 0;

-- 2.2 下单行为（权重 5）
INSERT INTO user_behavior (user_id, product_id, behavior_type, category_id, weight, behavior_count, create_time, update_time)
SELECT o.buyer_id, o.product_id, 4, p.category_id, 5.00, 1, o.create_time, o.create_time
FROM orders o JOIN product p ON p.id = o.product_id
WHERE o.deleted = 0 AND p.deleted = 0 AND o.status IN (0, 3);

-- 2.3 私信/留言行为（权重 2）
INSERT INTO user_behavior (user_id, product_id, behavior_type, category_id, weight, behavior_count, create_time, update_time)
SELECT m.from_user_id, m.product_id, 3, p.category_id, 2.00, 1, MIN(m.create_time), MAX(m.create_time)
FROM message m JOIN product p ON p.id = m.product_id
WHERE m.deleted = 0 AND p.deleted = 0 AND m.product_id IS NOT NULL
GROUP BY m.from_user_id, m.product_id, p.category_id;

-- 2.4 浏览行为（权重 1）：用现有商品的浏览量做一个温和的冷启动模拟
--     说明：真实浏览行为由后端在访问商品详情时写入（v0.11 起），这里只做演示数据初始化
INSERT INTO user_behavior (user_id, product_id, behavior_type, category_id, weight, behavior_count, create_time, update_time)
SELECT u.id, p.id, 1, p.category_id, 1.00, 1, NOW(), NOW()
FROM `user` u JOIN product p ON p.deleted = 0 AND p.status = 1
WHERE u.deleted = 0 AND u.id <> p.seller_id
  AND MOD(p.id + u.id, 7) = 0;      -- 只取一部分组合，模拟"部分用户看过部分商品"

-- -----------------------------------------------------------------------------
-- 3. 校验
-- -----------------------------------------------------------------------------
SELECT '用户行为表统计' AS 项;
SELECT behavior_type AS 行为类型,
       CASE behavior_type WHEN 1 THEN '浏览' WHEN 2 THEN '收藏' WHEN 3 THEN '私信' WHEN 4 THEN '下单' END AS 名称,
       COUNT(*) AS 记录数, COUNT(DISTINCT user_id) AS 涉及用户, COUNT(DISTINCT product_id) AS 涉及商品,
       SUM(behavior_count) AS 行为次数
FROM user_behavior WHERE deleted = 0 GROUP BY behavior_type ORDER BY behavior_type;

SELECT '用户行为明细（前 20 条）' AS 项;
SELECT b.id, u.username AS 用户, p.title AS 商品, b.behavior_type AS 类型, b.weight AS 权重, b.update_time AS 时间
FROM user_behavior b JOIN `user` u ON u.id = b.user_id JOIN product p ON p.id = b.product_id
WHERE b.deleted = 0 ORDER BY b.id LIMIT 20;

-- 推荐可用性自检：有多少用户产生了行为、商品被行为覆盖的比例
SELECT '推荐数据覆盖率' AS 项;
SELECT (SELECT COUNT(DISTINCT user_id) FROM user_behavior WHERE deleted = 0) AS 有行为用户数,
       (SELECT COUNT(*) FROM `user` WHERE deleted = 0) AS 用户总数,
       (SELECT COUNT(DISTINCT product_id) FROM user_behavior WHERE deleted = 0) AS 被行为覆盖商品数,
       (SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 1) AS 在售商品数;
