-- =============================================================================
-- 校园二手交易平台 v0.13 管理后台 —— 举报表 + 管理操作日志表 + 演示数据
-- 说明：仅新增 2 张表，不改动既有 8 张表；商品审核复用 product 表已有的
--       audit_remark / auditor_id / audit_time 字段（v0.02 设计时已预留）
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_v013_admin.sql
-- =============================================================================

USE campus_trade;

-- -----------------------------------------------------------------------------
-- 1. 举报表
--    业务：用户举报违规商品或不良用户，管理员在后台处理（整改/下架/忽略）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `report`;
CREATE TABLE `report` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '举报ID，主键',
  `reporter_id`      BIGINT UNSIGNED NOT NULL                COMMENT '举报人ID（外键 -> user.id）',
  `target_type`      TINYINT         NOT NULL DEFAULT 1      COMMENT '举报对象类型：1商品 2用户',
  `target_id`        BIGINT UNSIGNED NOT NULL                COMMENT '举报对象ID（商品ID或用户ID）',
  `reason_type`      TINYINT         NOT NULL DEFAULT 4      COMMENT '举报原因：1虚假信息 2违禁物品 3辱骂骚扰 4其他',
  `content`          VARCHAR(500)    DEFAULT NULL            COMMENT '补充说明',
  `image_url`        VARCHAR(255)    DEFAULT NULL            COMMENT '证据图片地址',
  `status`           TINYINT         NOT NULL DEFAULT 0      COMMENT '处理状态：0待处理 1已处理 2已忽略',
  `handle_admin_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '处理人（管理员ID）',
  `handle_result`    VARCHAR(255)    DEFAULT NULL            COMMENT '处理结果说明',
  `handle_time`      DATETIME        DEFAULT NULL            COMMENT '处理时间',
  `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
  `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_reporter` (`reporter_id`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_status_time` (`status`, `create_time`),
  CONSTRAINT `fk_report_reporter` FOREIGN KEY (`reporter_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '举报表';

-- -----------------------------------------------------------------------------
-- 2. 管理操作日志表
--    业务：记录管理员的关键操作（审核商品、禁用用户、处理举报），便于追责与审计
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `admin_log`;
CREATE TABLE `admin_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID，主键',
  `admin_id`    BIGINT UNSIGNED NOT NULL                COMMENT '操作管理员ID（外键 -> user.id）',
  `admin_name`  VARCHAR(50)     DEFAULT NULL            COMMENT '管理员用户名（冗余，便于查日志）',
  `action`      VARCHAR(50)     NOT NULL                COMMENT '操作类型：AUDIT_PRODUCT / DISABLE_USER / HANDLE_REPORT ...',
  `target_type` VARCHAR(20)     DEFAULT NULL            COMMENT '对象类型：PRODUCT / USER / REPORT',
  `target_id`   BIGINT UNSIGNED DEFAULT NULL            COMMENT '对象ID',
  `detail`      VARCHAR(500)    DEFAULT NULL            COMMENT '操作详情（如"驳回：图片含违禁内容"）',
  `ip`          VARCHAR(64)     DEFAULT NULL            COMMENT '操作人IP',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_admin_time` (`admin_id`, `create_time`),
  KEY `idx_action` (`action`),
  CONSTRAINT `fk_admin_log_admin` FOREIGN KEY (`admin_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '管理员操作日志表';

-- -----------------------------------------------------------------------------
-- 3. 演示数据
--    3.1 三件「待审核」商品（用于演示后台审核流程；不影响原有 14 件在售商品）
--    3.2 两条待处理举报
--    3.3 一条已处理举报（用于展示状态流转）
-- -----------------------------------------------------------------------------
DELETE FROM report WHERE content LIKE '【演示】%';
DELETE FROM product WHERE title LIKE '【待审核演示】%';

-- 3.1 待审核商品（卖家分别是 admin(1) 与 stu_demo(7)）
INSERT INTO product (title, description, category_id, seller_id, price, original_price, condition_level,
                     campus, trade_place, cover_image, status, view_count, favorite_count, create_time, update_time, deleted)
VALUES
 ('【待审核演示】某品牌电子烟（替烟）', '朋友送的，用不上了，低价出。', 8, 7, 120.00, 399.00, 2, '东校区', '东校区食堂门口', '/demo-images/default.png', 0, 0, 0, NOW(), NOW(), 0),
 ('【待审核演示】英语四级真题册（含答案）', '大三下考完了，转让给学弟学妹。', 10, 7, 15.00, 45.00, 2, '东校区', '图书馆一楼', '/demo-images/textbook.png', 0, 0, 0, NOW(), NOW(), 0),
 ('【待审核演示】某网课账号（包月）', '共享账号，价格可谈。', 8, 1, 30.00, 99.00, 1, '主校区', '线上交易', '/demo-images/default.png', 0, 0, 0, NOW(), NOW(), 0);

-- 3.2 举报数据：一条针对"疑似违规商品"、一条针对"用户骚扰"
INSERT INTO report (reporter_id, target_type, target_id, reason_type, content, status, create_time, update_time, deleted)
SELECT u.id, 1, p.id, 2, '【演示】疑似违禁物品，请管理员核实', 0, NOW(), NOW(), 0
FROM `user` u, product p
WHERE u.username = 'stu_test01' AND p.title = '【待审核演示】某品牌电子烟（替烟）';

INSERT INTO report (reporter_id, target_type, target_id, reason_type, content, status, create_time, update_time, deleted)
SELECT u.id, 2, t.id, 3, '【演示】该用户多次发送骚扰私信', 0, NOW(), NOW(), 0
FROM `user` u, `user` t
WHERE u.username = 'serendipity' AND t.username = 'stu_demo';

INSERT INTO report (reporter_id, target_type, target_id, reason_type, content, status,
                    handle_admin_id, handle_result, handle_time, create_time, update_time, deleted)
SELECT u.id, 1, p.id, 1, '【演示】商品描述与实物不符', 1, a.id, '已联系卖家补充描述并重新上传实拍图',
       NOW(), DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0
FROM `user` u, product p, `user` a
WHERE u.username = 'stu_demo' AND p.title = '宿舍小台灯 护眼款' AND a.username = 'admin';

-- -----------------------------------------------------------------------------
-- 4. 校验
-- -----------------------------------------------------------------------------
SELECT '待审核商品' AS 项, id, title, seller_id, status FROM product WHERE status = 0 AND deleted = 0;
SELECT '举报数据' AS 项;
SELECT r.id, ur.username AS 举报人, r.target_type AS 对象类型,
       CASE r.target_type WHEN 1 THEN (SELECT title FROM product WHERE id = r.target_id)
                          ELSE (SELECT username FROM `user` WHERE id = r.target_id) END AS 举报对象,
       CASE r.reason_type WHEN 1 THEN '虚假信息' WHEN 2 THEN '违禁物品' WHEN 3 THEN '辱骂骚扰' ELSE '其他' END AS 原因,
       CASE r.status WHEN 0 THEN '待处理' WHEN 1 THEN '已处理' ELSE '已忽略' END AS 状态,
       r.handle_result AS 处理结果
FROM report r JOIN `user` ur ON ur.id = r.reporter_id WHERE r.deleted = 0 ORDER BY r.id;
SELECT '表数量' AS 项, COUNT(*) AS 表数 FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_TYPE = 'BASE TABLE';
