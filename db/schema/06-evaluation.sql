-- =============================================================================
-- 06-evaluation.sql —— 订单双向评价表（v0.18 / 第6批-㉑）
--
-- 背景：平台原本只有"举报"这种负向机制，缺少**正向反馈**：
--   · 买家无法判断卖家是否靠谱（信用分是注册时给的固定值，之后从不变化）；
--   · 卖家也无法筛选"爱放鸽子/压价"的买家；
--   · 商品详情页没有口碑信息，"二手交易"最需要的信任成本没有被降低。
--   因此引入**双向评价**：一笔已完成订单，买卖双方各可评价对方一次。
--
-- 设计要点（每条都是为了防"评价污染"）：
--   ① `uk_order_from (order_id, from_user_id)`：同一订单每人只能评一次，
--      由数据库唯一索引兜底 —— 并发重复提交也只会成功一次（与收藏、注册同一思路）；
--   ② `role` 显式记录评价者身份（1 买家评卖家 / 2 卖家评买家），
--      避免靠 fromUserId 和买卖双方比对去反推（那样一旦订单字段变动就全错）；
--   ③ `to_user_id` 冗余落库：查"某人收到的评价"不必先查订单再比对，
--      也让评价在订单归档后依然可查；
--   ④ `product_id` 冗余落库：商品详情页要展示"这件商品的评价"，避免三表关联；
--   ⑤ 不建外键：本表是**行为留痕**，订单/商品被逻辑删除后评价仍应可查；
--      同时避免灌库顺序带来的外键顺序问题（CI 建库因此在任何顺序下都安全）。
--
-- 说明：`deleted` 保留逻辑删除位（与其它业务表一致），但业务层不提供删除入口 ——
--       评价一旦发出就应可追溯，删除只用于合规清理。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `evaluation` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评价ID，主键',
  `order_id`     BIGINT UNSIGNED NOT NULL                COMMENT '关联订单ID（orders.id）',
  `product_id`   BIGINT UNSIGNED NOT NULL                COMMENT '关联商品ID（product.id），冗余便于按商品查询',
  `from_user_id` BIGINT UNSIGNED NOT NULL                COMMENT '评价人ID（user.id）',
  `to_user_id`   BIGINT UNSIGNED NOT NULL                COMMENT '被评价人ID（user.id），冗余便于按人查询',
  `role`         TINYINT         NOT NULL                COMMENT '评价者身份：1买家评卖家 2卖家评买家',
  `rating`       TINYINT         NOT NULL                COMMENT '评分 1~5 星',
  `content`      VARCHAR(500)    DEFAULT NULL            COMMENT '评价内容（可空）',
  `tags`         VARCHAR(200)    DEFAULT NULL            COMMENT '标签，逗号分隔（如 描述相符,态度很好）',
  `anonymous`    TINYINT         NOT NULL DEFAULT 0      COMMENT '是否匿名：0否 1是（匿名只隐藏昵称，不影响统计）',
  `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
  `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0正常 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_from` (`order_id`, `from_user_id`) COMMENT '同一订单每人只能评价一次（并发兜底）',
  KEY `idx_to_user` (`to_user_id`, `id`) COMMENT '查"我收到的评价"',
  KEY `idx_product` (`product_id`, `id`) COMMENT '查"某商品的评价"',
  KEY `idx_from_user` (`from_user_id`, `id`) COMMENT '查"我发出的评价"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '订单双向评价表（v0.18）';
