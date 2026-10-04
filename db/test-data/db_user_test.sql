-- =============================================================================
-- 校园二手交易平台 v0.04 用户模块 —— 数据库测试脚本
-- 用途：验证注册/登录相关的数据落库是否正确（尤其是密码加密）
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_user_test.sql
-- 说明：脚本可重复执行；末尾的"清理"段会删除测试数据，不影响 admin 账号
-- =============================================================================

USE campus_trade;

SELECT '================ 1. user 表结构（v0.04 关注的字段） ================' AS step;
SELECT COLUMN_NAME AS `字段`, COLUMN_TYPE AS `类型`, IS_NULLABLE AS `可空`,
       COLUMN_DEFAULT AS `默认值`, COLUMN_COMMENT AS `注释`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'user'
  AND COLUMN_NAME IN ('id', 'username', 'password', 'nickname', 'role', 'status',
                      'credit_score', 'last_login_time', 'create_time', 'deleted')
ORDER BY ORDINAL_POSITION;

SELECT '================ 2. 唯一约束（用户名不能重复） ================' AS step;
SELECT INDEX_NAME AS `索引`, COLUMN_NAME AS `列`, NON_UNIQUE AS `非唯一`
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME = 'user'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

SELECT '================ 3. 当前用户数据（密码必须是 BCrypt 密文） ================' AS step;
SELECT id                       AS `用户ID`,
       username                 AS `用户名`,
       nickname                 AS `昵称`,
       LEFT(password, 7)        AS `密文前缀`,
       CHAR_LENGTH(password)    AS `密文长度`,
       role                     AS `角色(0学生/1管理员)`,
       status                   AS `状态(1正常/0禁用)`,
       credit_score             AS `信用分`,
       last_login_time          AS `最后登录时间`,
       create_time              AS `注册时间`
FROM user
ORDER BY id;

SELECT '================ 4. 明文密码检查（结果必须为 0） ================' AS step;
SELECT COUNT(*) AS `明文或非BCrypt条数`
FROM user
WHERE password IN ('123456', 'abc12345', 'password', 'admin')
   OR password NOT LIKE '$2a$%';

SELECT '================ 5. 模拟"注册"落库（等价的 SQL 形态） ================' AS step;
-- 后端注册接口实际执行的 SQL 等价于：
--   INSERT INTO user (username, password, nickname, role, status, credit_score, gender, deleted)
--   VALUES ('sql_demo_user', '$2a$10$....BCrypt密文....', 'SQL测试用户', 0, 1, 100, 0, 0);
-- 下面用一条演示数据验证字段约束（密文对应明文 abc12345）
DELETE FROM user WHERE username = 'sql_demo_user';
INSERT INTO user (username, password, nickname, role, status, credit_score, gender, campus)
VALUES ('sql_demo_user',
        '$2a$10$m9CT3hBn7eugreIZxoFL6ORFB3uxN9sNJYcrGOOXB5POBdzZJ6K26',
        'SQL测试用户', 0, 1, 100, 0, '东校区');

SELECT id, username, nickname, LEFT(password, 7) AS `密文前缀`, role, status, credit_score
FROM user WHERE username = 'sql_demo_user';

SELECT '================ 6. 唯一索引冲突验证（预期报 1062 Duplicate entry） ================' AS step;
-- 故意插入重复用户名，验证数据库层面的唯一约束（后端注册前的查重是第一道防线，唯一索引是最后一道）
INSERT INTO user (username, password, nickname) VALUES ('admin', 'x', '重复管理员');

SELECT '================ 7. 登录时间更新验证 ================' AS step;
-- 后端登录成功后执行：UPDATE user SET last_login_time = NOW() WHERE id = ?
SELECT username AS `用户名`, last_login_time AS `最后登录时间`,
       TIMESTAMPDIFF(MINUTE, last_login_time, NOW()) AS `距现在(分钟)`
FROM user WHERE last_login_time IS NOT NULL;

SELECT '================ 8. 清理测试数据 ================' AS step;
DELETE FROM user WHERE username IN ('sql_demo_user', 'stu_test01');
SELECT id, username, nickname, role FROM user ORDER BY id;

-- =============================================================================
-- 附：常用排查 SQL
--   查看某用户：      SELECT * FROM user WHERE username = 'stu_test01';
--   手动禁用账号：    UPDATE user SET status = 0 WHERE username = 'stu_test01';
--   逻辑删除用户：    UPDATE user SET deleted = 1 WHERE username = 'stu_test01';
--   统计注册量：      SELECT DATE(create_time) AS 日期, COUNT(*) AS 注册数 FROM user GROUP BY DATE(create_time);
-- =============================================================================
