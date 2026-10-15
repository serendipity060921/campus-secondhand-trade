-- =============================================================================
-- 04-moderation.sql —— 内容机审记录表（v0.18 / C3 阶段交付）
--
-- 背景：v0.17 引入内容机审（机审 + 人审双层治理）后，机审结论只写进了应用日志，
--       只能靠 tools/moderation-stats.py 读日志文件统计，存在三个问题：
--         ① 日志会轮转/被清理，历史数据留不住；
--         ② 无法按任意时间范围聚合，也做不出趋势；
--         ③ 统计口径依赖日志文本格式，改一行日志就可能统计错。
--       因此把机审结论落库，成为可查询、可聚合、可画趋势的数据。
--
-- 说明：本表是**只追加**的事实表（不设 deleted 逻辑删除列）——
--       机审记录属于审计留痕，不应被业务删除；用户注销时随外键级联清理即可。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `moderation_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '机审记录ID，主键',
  `seller_id`       BIGINT UNSIGNED NOT NULL                COMMENT '发布者ID（外键 -> user.id）',
  `product_id`      BIGINT UNSIGNED DEFAULT NULL            COMMENT '商品ID：机审发生在商品落库之前，故当前恒为空，保留字段便于后续回填',
  `title`           VARCHAR(255)    DEFAULT NULL            COMMENT '被审标题（超长截断保存）',
  `action`          VARCHAR(10)     NOT NULL                COMMENT '机审结论：PASS 放行 / REVIEW 转人审 / REJECT 拒绝',
  `risk_rule`       VARCHAR(200)    DEFAULT NULL            COMMENT '命中的规则编号，多条用逗号连接（如 R1-违禁词,R2-联系方式）',
  `hit_count`       INT             NOT NULL DEFAULT 0      COMMENT '命中条数',
  `hits`            VARCHAR(1000)   DEFAULT NULL            COMMENT '命中明细：rule|field|matched，多条用 ; 连接',
  `reason`          VARCHAR(500)    DEFAULT NULL            COMMENT '给用户看的原因文本',
  `audit_required`  TINYINT         NOT NULL DEFAULT 0      COMMENT '是否进入人工审核：0否 1是',
  `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '机审时间',
  PRIMARY KEY (`id`),
  KEY `idx_action_time` (`action`, `create_time`),
  KEY `idx_seller_time` (`seller_id`, `create_time`),
  KEY `idx_create_time` (`create_time`),
  CONSTRAINT `fk_moderation_log_seller` FOREIGN KEY (`seller_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '内容机审记录表（只追加）';

-- -----------------------------------------------------------------------------
-- 演示数据说明：
--   本表**不预置**演示数据 —— 机审记录应由真实发布行为产生（发布时自动写入）。
--   若要造演示数据，直接调用发布接口发布若干商品即可，
--   然后用 tools/moderation-stats.py --source db 查看统计与趋势。
-- -----------------------------------------------------------------------------
