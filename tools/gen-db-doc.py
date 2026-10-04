# -*- coding: utf-8 -*-
"""由 information_schema 生成 docs/数据库说明.md（保证文档与真实库结构一致）

用法：python tools/gen-db-doc.py
依赖：mysql CLI 可执行；库 campus_trade 已建好
"""
import os
import json
import pathlib
import subprocess

MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
# 脚本位于 tools/，生成物仍输出到 docs/（v0.16 结构整理后修正）
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs' / '数据库说明.md'
DUMP = pathlib.Path(__file__).with_name('schema-dump.json')   # dump 与脚本同目录


def sql(query):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', '-e', f'USE campus_trade; {query}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    return [line.split('\t') for line in (r.stdout or '').strip().splitlines() if line]


# ---------------- 读取真实结构 ----------------
columns = sql("SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, IFNULL(COLUMN_DEFAULT,''), "
              "COLUMN_KEY, COLUMN_COMMENT FROM information_schema.COLUMNS "
              "WHERE TABLE_SCHEMA='campus_trade' ORDER BY TABLE_NAME, ORDINAL_POSITION")
schema = {}
for row in columns:
    schema.setdefault(row[0], []).append(row[1:])

table_counts = {}
for row in sql("SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES "
               "WHERE TABLE_SCHEMA='campus_trade' AND TABLE_TYPE='BASE TABLE'"):
    table_counts[row[0]] = row[1]

fks = sql("SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
          "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='campus_trade' "
          "AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME, COLUMN_NAME")

# 真实行数（比 information_schema 的估算准）
for t in list(schema.keys()):
    try:
        table_counts[t] = sql(f"SELECT COUNT(*) FROM `{t}` WHERE deleted = 0")[0][0]
    except Exception:  # noqa: BLE001
        pass

DUMP.write_text(json.dumps(dict(schema=schema, counts=table_counts, fks=fks),
                           ensure_ascii=False, indent=1), encoding='utf-8')

TABLE_INFO = {
    'user': ('用户表', '存放所有账号：普通学生 + 管理员（用 role 区分）。注册、登录、个人资料都读写这张表。'),
    'category': ('商品分类表', '商品分类，支持两级（parent_id=0 是一级分类，其余是二级分类）。发布商品时要选一个分类。'),
    'product': ('商品表', '二手商品主表。一条记录 = 一件商品，含价格、成色、状态、审核信息、浏览量等。'),
    'product_image': ('商品图片表', '一件商品可以有多张图，一张图一行记录（product_id 指向所属商品）。'),
    'favorite': ('收藏表', '用户收藏商品的记录，一个用户对一件商品最多一条（有唯一索引）。'),
    'message': ('留言/私信表', '站内消息。type=1 是商品留言，type=2 是私信；私信里 from_user_id 发给 to_user_id。'),
    'orders': ('订单表', '交易订单。一条记录 = 一次买卖，同时记录买家、卖家、商品快照、金额、订单状态。'),
    'user_behavior': ('用户行为表（v0.11）', '推荐算法的数据源。浏览/收藏/私信/下单都记一行，'
                                           '同一用户对同一商品的同类行为只保留一行并累加次数。'),
    'report': ('举报表（v0.13）', '用户举报违规商品或不良用户，管理员在后台处理。'
                                'target_type 区分举报的是商品还是用户，status 记录处理进度。'),
    'admin_log': ('管理员操作日志表（v0.13）', '管理端写操作的审计记录：审核商品、强制下架、'
                                             '启用/禁用用户、处理举报，可追溯到「谁在什么时候做了什么」。'),
}
ORDER = ['user', 'category', 'product', 'product_image', 'favorite', 'message', 'orders',
         'user_behavior', 'report', 'admin_log']

L = []
w = L.append
w('# 校园二手交易平台 · 数据库说明书')
w('')
w(f'> 数据库：`campus_trade`（MySQL 8.4.4）　|　共 **{len(ORDER)} 张表**、'
  f'**{len(columns)} 个字段**、**{len(fks)} 个外键**')
w('> 本文档的字段表由 `information_schema` 自动导出（`python tools/gen-db-doc.py` 可重新生成），与数据库真实结构完全一致。')
w('')
w('---')
w('')
w('## 一、先分清"结构"和"数据"')
w('')
w('在 HeidiSQL 里你会看到两个东西，它们不是一回事：')
w('')
w('| 你看到的 | 英文 | 是什么 | 类比 Excel |')
w('| --- | --- | --- | --- |')
w('| **「基本」标签页** | Structure / 结构 | 这张表**由哪些列组成**：列名、类型、是否允许为空、默认值、注释、索引、外键 | 表格的**表头**：告诉你有"姓名/学号/电话"这几列 |')
w('| **「数据」标签页** | Data / 数据 | 表里**实际存的一条条记录**（行） | 表格里**一行行的内容**：张三、2021001、138… |')
w('')
w('所以「结构」里看到 `id / username / password / nickname …` 是**列定义**（每行一列），')
w('而「数据」里看到的真实账号才是**数据**。建表 SQL 决定结构，注册/发布商品往里写的是数据。')
w('')
w('---')
w('')
w('## 二、10 张表分别是干什么的')
w('')
w('| 表名 | 中文名 | 作用 | 当前行数 |')
w('| --- | --- | --- | ---: |')
for t in ORDER:
    cn, desc = TABLE_INFO[t]
    w(f'| `{t}` | {cn} | {desc} | {table_counts.get(t, "-")} |')
w('')
w('---')
w('')
w('## 三、表之间的关系（ER 关系）')
w('')
w('![ER 图](images/ER图.png)')
w('')
w('| 关系 | 说明 |')
w('| --- | --- |')
w('| `product.seller_id` → `user.id` | 一件商品属于一个卖家（1 个用户能发多件商品，1:N） |')
w('| `product.category_id` → `category.id` | 一件商品属于一个分类（1 个分类下有多个商品，1:N） |')
w('| `product.auditor_id` → `user.id` | 商品被哪个管理员审核过 |')
w('| `product_image.product_id` → `product.id` | 一张图片属于一件商品 |')
w('| `favorite.user_id` / `favorite.product_id` | 收藏是"用户 ↔ 商品"的多对多关系，用这张中间表承载 |')
w('| `message.from_user_id` / `to_user_id` → `user.id` | 私信的发送人、接收人 |')
w('| `message.product_id` → `product.id` | 这条私信/留言是关于哪件商品的（可以不填） |')
w('| `orders.product_id` → `product.id` | 这笔订单买的是哪件商品 |')
w('| `orders.buyer_id` / `seller_id` → `user.id` | 订单的买家与卖家（同一张 user 表两个角色） |')
w('| `user_behavior.user_id` / `product_id` | 行为记录挂在用户与商品上，是推荐算法的"用户-物品"交互矩阵来源 |')
w('')
w('> 一句话概括：**user 是中心**，商品由 user 发布、归属 category，图片/收藏/消息/订单/行为都挂在 product 或 user 上。')
w('')
w('---')
w('')
w('## 四、每张表的字段含义')
w('')
for i, t in enumerate(ORDER, start=1):
    cn, desc = TABLE_INFO[t]
    w(f'### 4.{i} `{t}`（{cn}）')
    w('')
    w('| 字段 | 类型 | 允许空 | 默认值 | 键 | 含义 |')
    w('| --- | --- | :---: | --- | :---: | --- |')
    for (name, ctype, nullable, default, key, comment) in schema.get(t, []):
        keymark = {'PRI': '🔑主键', 'UNI': '唯一'}.get(key, '')
        if key == 'MUL':
            keymark = '外键' if any(f[0] == t and f[1] == name for f in fks) else '索引'
        w(f"| `{name}` | {ctype} | {'是' if nullable == 'YES' else '否'} | {default or '-'} | {keymark} | {comment} |")
    w('')
w('---')
w('')
w('## 五、状态/类型字段对照表（重点）')
w('')
w('| 表.字段 | 取值 | 含义 |')
w('| --- | --- | --- |')
w('| `user.role` | 0 / 1 | 0 = 普通学生，1 = 管理员 |')
w('| `user.status` | 1 / 0 | 1 = 正常，0 = 禁用（禁用后不能登录） |')
w('| `user.gender` | 0 / 1 / 2 | 0 = 保密，1 = 男，2 = 女 |')
w('| `product.status` | 0 / 1 / 2 / 3 / 4 / 5 | 0 = 待审核，1 = 在售，2 = 审核不通过，3 = 已下架，4 = 交易中，5 = 已售出 |')
w('| `product.condition_level` | 1 / 2 / 3 / 4 | 成色：1 = 全新，2 = 几乎全新，3 = 轻微使用痕迹，4 = 明显使用痕迹 |')
w('| `orders.status` | 0 / 3 / 4 | 0 = 待交易，3 = 已完成，4 = 已取消（1/2/5 为预留：待交付/待收货/已退款） |')
w('| `message.type` | 1 / 2 | 1 = 商品留言，2 = 私信 |')
w('| `message.is_read` | 0 / 1 | 0 = 未读，1 = 已读 |')
w('| `category.parent_id` | 0 / 其他 | 0 = 一级分类；其他值 = 它的父分类 id（二级分类） |')
w('| `user_behavior.behavior_type` | 1 / 2 / 3 / 4 | 1 = 浏览（权重 1），2 = 收藏（权重 3），3 = 私信（权重 2），4 = 下单（权重 5） |')
w('| `report.target_type` | 1 / 2 | 1 = 举报商品，2 = 举报用户 |')
w('| `report.reason_type` | 1 / 2 / 3 / 4 | 1 = 虚假信息，2 = 违禁物品，3 = 辱骂骚扰，4 = 其他 |')
w('| `report.status` | 0 / 1 / 2 | 0 = 待处理，1 = 已处理，2 = 已忽略 |')
w('| 所有表 `deleted` | 0 / 1 | 0 = 未删除，1 = 已逻辑删除（**逻辑删除**：数据还在，只是查询时自动过滤掉） |')
w('')
w('---')
w('')
w('## 六、几个"看起来奇怪"的地方，其实都是设计')
w('')
w('| 现象 | 原因 |')
w('| --- | --- |')
w('| `password` 是 `$2a$10$...` 60 位乱码 | 用 **BCrypt 单向加密**存的，不可逆。登录时是把用户输入的密码再加密后比对，所以数据库里永远没有明文 |')
w('| `id` 是 1、5、6、7… 不连续 | 自增主键。中间被删的记录 id 不会回收（正常现象） |')
w('| `create_time` / `update_time` 会自动变 | 建表时设了 `DEFAULT CURRENT_TIMESTAMP` 和 `ON UPDATE CURRENT_TIMESTAMP`，插入/修改时数据库自动填 |')
w('| `price` 用 `DECIMAL(10,2)` | 金额**不能用 FLOAT/DOUBLE**（会有精度误差），DECIMAL 精确到分 |')
w('| 表名叫 `orders` 而不是 `order` | `order` 是 MySQL 保留字（`ORDER BY`），直接当表名会报错 |')
w('| 成色字段叫 `condition_level` | `condition` 同样是 MySQL 保留字 |')
w('| `product.favorite_count` 是冗余计数 | 收藏表里已经有明细，但列表页要频繁显示"多少人收藏"，冗余一个计数避免每次 COUNT；**应用在收藏/取消时同步维护**（v0.10 已修复并发下的计数与错误码问题） |')
w('| `user_behavior` 同一用户同一商品只有一行 | 唯一索引 `uk_user_product_type`：避免"浏览"把表撑爆，用 `behavior_count` 记频次、`update_time` 记最近时间 |')
w('| 外键（Foreign Key）的含义 | 比如 `orders.product_id` 只能填 `product` 表里真实存在的 id；填不存在的值数据库直接报 1452，保证数据不会"指错" |')
w('')
w('---')
w('')
w('## 七、想看数据时，直接跑这几条 SQL')
w('')
w('```sql')
w('USE campus_trade;')
w('')
w('SELECT * FROM `user`;                     -- 所有账号')
w('SELECT * FROM product WHERE status = 1;   -- 在售商品')
w('SELECT * FROM orders;                    -- 订单')
w('SELECT * FROM favorite;                  -- 收藏')
w('SELECT * FROM message WHERE type = 2;    -- 私信')
w('SELECT * FROM user_behavior ORDER BY update_time DESC LIMIT 20;  -- 最近行为（推荐数据源）')
w('```')
w('')
w('更多现成查询见 [常用查询.sql](./常用查询.sql)。')
w('')
w('---')
w('')
w('## 八、一张表怎么"读"（以 user 为例）')
w('')
w('在 HeidiSQL 里双击 `user` 打开数据后，你可以这样读一行：')
w('')
w('```')
w('id=6  username=serendipity  nickname=薇尔莉特  password=$2a$10$e2C…（密文）')
w('role=0（普通学生）  status=1（正常）  credit_score=100  create_time=2026-10-02 19:36:45')
w('```')
w('')
w('翻译成业务语言：**"2026-10-02 19:36:45 注册了一个普通学生账号，用户名 serendipity、昵称薇尔莉特，'
  '密码经过 BCrypt 加密存储，当前状态正常、信用分 100 分。"**')
w('')
w('> 这就是"数据"：一条记录 = 现实里的一个对象（一个用户、一件商品、一笔订单、一次行为）。')
w('> 而"结构"回答的是"这个对象有哪些属性、每个属性什么类型、能不能为空"。')
w('')
w('---')
w('')
w('相关文档：[需求与设计](../spec.md) · [接口文档](./api.md) · [建表脚本](../db/schema/01-schema.sql) · '
  '[推荐算法说明](./推荐算法说明.md) · [测试用例](./test-cases.md)')

OUT.write_text('\n'.join(L), encoding='utf-8')
print(f'已生成 {OUT.name}：{len(L)} 行、{len(ORDER)} 张表、{len(columns)} 个字段、{len(fks)} 个外键')
print('各表行数：', {t: table_counts.get(t) for t in ORDER})
