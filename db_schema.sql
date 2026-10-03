/* =============================================================================
 * 项目名称：二手校园交易系统（Campus Secondhand Trade）
 * 文件名称：db_schema.sql
 * 数据库：MySQL 8.0+   引擎：InnoDB   字符集：utf8mb4 / utf8mb4_general_ci
 * 说明：
 *   1) 本脚本包含 5 张核心表（user / category / product / message / orders）
 *      与 2 张扩展表（product_image / favorite），可直接整体执行。
 *   2) 所有表使用逻辑删除字段 deleted，避免物理删除破坏订单、留言的历史关联。
 *   3) 所有字段均带 COMMENT，便于生成数据字典与论文表格。
 *   4) 表名 orders 使用复数，规避 MySQL 保留字 ORDER；成色字段取名
 *      condition_level，规避保留字 CONDITION。
 * 执行方式：
 *   mysql -u root -p < db_schema.sql
 * ========================================================================== */

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------------
-- 0. 创建数据库
-- ---------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS `campus_trade`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `campus_trade`;

-- ---------------------------------------------------------------------------
-- 1. 用户表 user（普通学生 + 管理员共用，role 区分）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID，主键',
    `username`        VARCHAR(50)     NOT NULL                COMMENT '登录用户名，唯一',
    `password`        VARCHAR(100)    NOT NULL                COMMENT '登录密码，BCrypt 密文',
    `nickname`        VARCHAR(50)              DEFAULT NULL   COMMENT '昵称，展示用',
    `real_name`       VARCHAR(50)              DEFAULT NULL   COMMENT '真实姓名，校内认证用',
    `student_no`      VARCHAR(30)              DEFAULT NULL   COMMENT '学号，唯一，注册后不可修改',
    `phone`           VARCHAR(20)              DEFAULT NULL   COMMENT '手机号，唯一',
    `email`           VARCHAR(100)             DEFAULT NULL   COMMENT '邮箱',
    `avatar`          VARCHAR(255)             DEFAULT NULL   COMMENT '头像地址',
    `gender`          TINYINT         NOT NULL DEFAULT 0      COMMENT '性别：0未知 1男 2女',
    `school`          VARCHAR(100)             DEFAULT NULL   COMMENT '学校名称',
    `campus`          VARCHAR(100)             DEFAULT NULL   COMMENT '所在校区',
    `role`            TINYINT         NOT NULL DEFAULT 0      COMMENT '角色：0普通学生 1管理员',
    `status`          TINYINT         NOT NULL DEFAULT 1      COMMENT '账号状态：1正常 0禁用',
    `credit_score`    INT             NOT NULL DEFAULT 100    COMMENT '信用分，初始100，交易完成累加',
    `last_login_time` DATETIME                 DEFAULT NULL   COMMENT '最后登录时间',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_student_no` (`student_no`),
    UNIQUE KEY `uk_phone` (`phone`),
    KEY `idx_role_status` (`role`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '用户表（学生/管理员）';

-- ---------------------------------------------------------------------------
-- 2. 商品分类表 category（parent_id 自关联，支持二级分类）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类ID，主键',
    `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父分类ID，0表示一级分类',
    `name`        VARCHAR(50)     NOT NULL                COMMENT '分类名称',
    `icon`        VARCHAR(255)             DEFAULT NULL   COMMENT '分类图标地址',
    `sort_order`  INT             NOT NULL DEFAULT 0      COMMENT '排序值，越小越靠前',
    `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1启用 0停用',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_parent_name` (`parent_id`, `name`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_sort_order` (`sort_order`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '商品分类表';

-- ---------------------------------------------------------------------------
-- 3. 商品表 product
--    status 流转：0待审核 -> 1在售 -> 4交易中 -> 5已售出
--                 0待审核 -> 2审核不通过（修改后重新提交回 0）
--                 1在售   -> 3已下架（可重新上架回 1）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品ID，主键',
    `title`          VARCHAR(100)    NOT NULL                COMMENT '商品标题',
    `description`    TEXT                     DEFAULT NULL   COMMENT '商品描述（成色、入手渠道、瑕疵说明等）',
    `category_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属分类ID',
    `seller_id`      BIGINT UNSIGNED NOT NULL                COMMENT '卖家用户ID',
    `price`          DECIMAL(10, 2)  NOT NULL                COMMENT '售价（元）',
    `original_price` DECIMAL(10, 2)           DEFAULT NULL   COMMENT '原价（元），用于展示折扣',
    `cover_image`    VARCHAR(255)             DEFAULT NULL   COMMENT '封面图地址',
    `condition_level` TINYINT        NOT NULL DEFAULT 1      COMMENT '成色：1全新 2几乎全新 3轻微使用痕迹 4明显使用痕迹',
    `campus`         VARCHAR(100)             DEFAULT NULL   COMMENT '交易校区',
    `trade_place`    VARCHAR(100)             DEFAULT NULL   COMMENT '期望交易地点',
    `status`         TINYINT         NOT NULL DEFAULT 0      COMMENT '状态：0待审核 1在售 2审核不通过 3已下架 4交易中 5已售出',
    `audit_remark`   VARCHAR(255)             DEFAULT NULL   COMMENT '审核意见（驳回理由）',
    `auditor_id`     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '审核管理员ID',
    `audit_time`     DATETIME                 DEFAULT NULL   COMMENT '审核时间',
    `view_count`     INT             NOT NULL DEFAULT 0      COMMENT '浏览量',
    `favorite_count` INT             NOT NULL DEFAULT 0      COMMENT '收藏量',
    `shelf_time`     DATETIME                 DEFAULT NULL   COMMENT '上架时间',
    `sold_time`      DATETIME                 DEFAULT NULL   COMMENT '售出时间',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (`id`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_seller_id` (`seller_id`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_status_category` (`status`, `category_id`),
    KEY `idx_title` (`title`),
    CONSTRAINT `fk_product_seller` FOREIGN KEY (`seller_id`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`),
    CONSTRAINT `fk_product_auditor` FOREIGN KEY (`auditor_id`) REFERENCES `user` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '商品表';

-- ---------------------------------------------------------------------------
-- 4. 商品图片表 product_image（一商品多图，扩展表）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `product_image`;
CREATE TABLE `product_image` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '图片ID，主键',
    `product_id`  BIGINT UNSIGNED NOT NULL                COMMENT '商品ID',
    `url`         VARCHAR(255)    NOT NULL                COMMENT '图片地址',
    `sort_order`  INT             NOT NULL DEFAULT 0      COMMENT '排序值，0为首图',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (`id`),
    KEY `idx_product_id` (`product_id`),
    CONSTRAINT `fk_image_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '商品图片表';

-- ---------------------------------------------------------------------------
-- 5. 商品收藏表 favorite（扩展表）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `favorite`;
CREATE TABLE `favorite` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '收藏ID，主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '收藏人用户ID',
    `product_id`  BIGINT UNSIGNED NOT NULL                COMMENT '被收藏商品ID',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
    KEY `idx_product_id` (`product_id`),
    CONSTRAINT `fk_favorite_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_favorite_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '商品收藏表';

-- ---------------------------------------------------------------------------
-- 6. 留言/私信表 message
--    type = 1 商品留言：product_id 必填，parent_id 支持盖楼回复，to_user_id 可为空
--    type = 2 私信：    product_id 可为空（也可关联来源商品），to_user_id 必填
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `message`;
CREATE TABLE `message` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息ID，主键',
    `type`         TINYINT         NOT NULL DEFAULT 1      COMMENT '消息类型：1商品留言 2私信',
    `product_id`   BIGINT UNSIGNED          DEFAULT NULL   COMMENT '关联商品ID，纯私信可为空',
    `from_user_id` BIGINT UNSIGNED NOT NULL                COMMENT '发送人用户ID',
    `to_user_id`   BIGINT UNSIGNED          DEFAULT NULL   COMMENT '接收人用户ID，商品留言可为空',
    `parent_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父留言ID，0为顶层留言',
    `content`      VARCHAR(1000)   NOT NULL                COMMENT '消息内容',
    `is_read`      TINYINT         NOT NULL DEFAULT 0      COMMENT '是否已读：0未读 1已读',
    `read_time`    DATETIME                 DEFAULT NULL   COMMENT '读取时间',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (`id`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_from_user` (`from_user_id`),
    KEY `idx_to_user_read` (`to_user_id`, `is_read`),
    KEY `idx_conversation` (`from_user_id`, `to_user_id`, `create_time`),
    CONSTRAINT `fk_message_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
    CONSTRAINT `fk_message_from_user` FOREIGN KEY (`from_user_id`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_message_to_user` FOREIGN KEY (`to_user_id`) REFERENCES `user` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '留言/私信表';

-- ---------------------------------------------------------------------------
-- 7. 订单表 orders
--    status 流转：0待付款 -> 1待交付 -> 2待收货 -> 3已完成
--                 0待付款 -> 4已取消（商品回到在售）
--                 任一状态 -> 5已退款（异常订单由管理员处理）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '订单ID，主键',
    `order_no`         VARCHAR(32)     NOT NULL                COMMENT '订单编号，对外展示，唯一',
    `product_id`       BIGINT UNSIGNED NOT NULL                COMMENT '商品ID',
    `buyer_id`         BIGINT UNSIGNED NOT NULL                COMMENT '买家用户ID',
    `seller_id`        BIGINT UNSIGNED NOT NULL                COMMENT '卖家用户ID',
    `product_title`    VARCHAR(100)    NOT NULL                COMMENT '商品标题快照',
    `product_image`    VARCHAR(255)             DEFAULT NULL   COMMENT '商品封面快照',
    `amount`           DECIMAL(10, 2)  NOT NULL                COMMENT '成交金额（元）',
    `status`           TINYINT         NOT NULL DEFAULT 0      COMMENT '状态：0待付款 1待交付 2待收货 3已完成 4已取消 5已退款',
    `delivery_type`    TINYINT         NOT NULL DEFAULT 1      COMMENT '交付方式：1校内面交 2快递',
    `trade_place`      VARCHAR(100)             DEFAULT NULL   COMMENT '面交地点',
    `receiver_name`    VARCHAR(50)              DEFAULT NULL   COMMENT '收货人姓名（快递时必填）',
    `receiver_phone`   VARCHAR(20)              DEFAULT NULL   COMMENT '收货人电话',
    `receiver_address` VARCHAR(255)             DEFAULT NULL   COMMENT '收货地址',
    `pay_type`         TINYINT                  DEFAULT NULL   COMMENT '支付方式：1模拟支付 2微信 3支付宝',
    `pay_time`         DATETIME                 DEFAULT NULL   COMMENT '支付时间',
    `deliver_time`     DATETIME                 DEFAULT NULL   COMMENT '卖家交付时间',
    `finish_time`      DATETIME                 DEFAULT NULL   COMMENT '交易完成时间',
    `cancel_time`      DATETIME                 DEFAULT NULL   COMMENT '取消时间',
    `cancel_reason`    VARCHAR(255)             DEFAULT NULL   COMMENT '取消/退款原因',
    `buyer_remark`     VARCHAR(255)             DEFAULT NULL   COMMENT '买家备注',
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_buyer_status` (`buyer_id`, `status`),
    KEY `idx_seller_status` (`seller_id`, `status`),
    KEY `idx_create_time` (`create_time`),
    CONSTRAINT `fk_order_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
    CONSTRAINT `fk_order_buyer` FOREIGN KEY (`buyer_id`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_order_seller` FOREIGN KEY (`seller_id`) REFERENCES `user` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '订单表';

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- 8. 初始化数据
-- ---------------------------------------------------------------------------

-- 8.1 管理员账号（用户名 admin / 初始密码 123456，入库为 BCrypt 密文）
INSERT INTO `user` (`username`, `password`, `nickname`, `real_name`, `role`, `status`, `campus`)
-- BCrypt 密文对应明文密码 123456（通过 bcrypt 生成并校验通过），首次登录后请立即修改
VALUES ('admin', '$2a$10$m9CT3hBn7eugreIZxoFL6ORFB3uxN9sNJYcrGOOXB5POBdzZJ6K26', '系统管理员', '平台管理员', 1, 1, '主校区');

-- 8.2 一级分类
INSERT INTO `category` (`parent_id`, `name`, `sort_order`, `status`) VALUES
    (0, '教材书籍', 1, 1),
    (0, '数码电子', 2, 1),
    (0, '生活用品', 3, 1),
    (0, '运动户外', 4, 1),
    (0, '服饰鞋包', 5, 1),
    (0, '美妆护肤', 6, 1),
    (0, '乐器文具', 7, 1),
    (0, '其他闲置', 99, 1);

-- 8.3 二级分类（parent_id 对应上面一级分类的自增 ID：1~8）
INSERT INTO `category` (`parent_id`, `name`, `sort_order`, `status`) VALUES
    (1, '公共课教材', 1, 1),
    (1, '专业课教材', 2, 1),
    (1, '考研资料', 3, 1),
    (1, '课外读物', 4, 1),
    (2, '手机', 1, 1),
    (2, '笔记本电脑', 2, 1),
    (2, '平板电脑', 3, 1),
    (2, '耳机音响', 4, 1),
    (2, '键盘鼠标', 5, 1),
    (2, '相机摄影', 6, 1),
    (3, '宿舍家具', 1, 1),
    (3, '行李收纳', 2, 1),
    (3, '日常洗护', 3, 1),
    (4, '自行车', 1, 1),
    (4, '球类器材', 2, 1),
    (4, '健身器材', 3, 1),
    (5, '男装', 1, 1),
    (5, '女装', 2, 1),
    (5, '鞋靴', 3, 1),
    (6, '护肤', 1, 1),
    (6, '彩妆', 2, 1),
    (7, '吉他', 1, 1),
    (7, '文具用品', 2, 1),
    (8, '其他', 1, 1);

/* =============================================================================
 * 八、用户行为表（v0.11 推荐模块）
 *    说明：本表为 v0.11 里程碑新增，用于支撑个性化推荐（Item-CF + 内容召回 + 热门）。
 *          历史行为可用 db_v011_recommend.sql 从 favorite / orders / message 回填。
 * ========================================================================== */

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

/* =============================================================================
 * 表 9：举报表 report（v0.13 管理后台）
 * 业务：用户举报违规商品或不良用户，管理员在后台处理（处理/忽略并留痕）
 * ========================================================================== */
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

/* =============================================================================
 * 表 10：管理员操作日志表 admin_log（v0.13 管理后台）
 * 业务：记录管理端关键写操作（审核商品、启禁用用户、处理举报），用于责任追溯
 * ========================================================================== */
DROP TABLE IF EXISTS `admin_log`;
CREATE TABLE `admin_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID，主键',
  `admin_id`    BIGINT UNSIGNED NOT NULL                COMMENT '操作管理员ID（外键 -> user.id）',
  `admin_name`  VARCHAR(50)     DEFAULT NULL            COMMENT '管理员用户名（冗余，便于查日志）',
  `action`      VARCHAR(50)     NOT NULL                COMMENT '操作类型：AUDIT_PRODUCT / OFFLINE_PRODUCT / DISABLE_USER / ENABLE_USER / HANDLE_REPORT',
  `target_type` VARCHAR(20)     DEFAULT NULL            COMMENT '对象类型：PRODUCT / USER / REPORT',
  `target_id`   BIGINT UNSIGNED DEFAULT NULL            COMMENT '对象ID',
  `detail`      VARCHAR(500)    DEFAULT NULL            COMMENT '操作详情',
  `ip`          VARCHAR(64)     DEFAULT NULL            COMMENT '操作人IP',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_admin_time` (`admin_id`, `create_time`),
  KEY `idx_action` (`action`),
  CONSTRAINT `fk_admin_log_admin` FOREIGN KEY (`admin_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '管理员操作日志表';

/* =============================================================================
 * 脚本结束。验证方式：
 *   SHOW TABLES;                                  -- 应输出 10 张表
 *   SHOW CREATE TABLE `orders`;                    -- 检查外键与索引
 *   SELECT COUNT(*) FROM `category`;               -- 应为 32
 *   SHOW CREATE TABLE `user_behavior`;              -- v0.11 推荐模块数据源
 *   SHOW CREATE TABLE `report`;                     -- v0.13 举报表
 *   SHOW CREATE TABLE `admin_log`;                  -- v0.13 管理员操作日志表
 * ========================================================================== */
