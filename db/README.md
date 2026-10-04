# 数据库脚本目录说明

本目录集中管理所有 SQL 脚本（v0.16 结构整理时从仓库根目录与 `docs/` 迁入）。

## 目录结构

```
db/
├── schema/          # 生产建库脚本：按编号顺序执行，缺一不可
│   ├── 01-schema.sql       建表（10 张表）+ 分类初始化数据
│   ├── 02-recommend.sql    user_behavior 表 + 从收藏/订单/私信回填行为数据
│   └── 03-admin.sql        report / admin_log 表 + 审核与举报演示数据
├── test-data/       # 仅测试用的造数脚本，**不要在生产执行**
│   ├── db_user_test.sql      用户模块测试数据
│   ├── db_product_test.sql   商品模块测试数据
│   ├── db_favorite_test.sql  收藏模块测试数据
│   ├── db_message_test.sql   私信模块测试数据
│   ├── db_order_test.sql     订单模块测试数据
│   ├── db_v009_test.sql      辅助功能测试数据
│   └── db_smoke_test.sql     结构与业务冒烟校验
├── 常用查询.sql     排查问题时常用的 SQL 片段（统计、一致性校验）
└── README.md        本文件
```

## 初始化数据库（顺序不能换）

三个脚本之间存在依赖：`02` 依赖 `01` 建好的 `product` 表，`03` 依赖 `01` 的 `user` 表。

```bash
# 方式一：命令行逐个执行
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < db/schema/01-schema.sql
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < db/schema/02-recommend.sql
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < db/schema/03-admin.sql
```

```bash
# 方式二：Docker Compose 一键部署时自动执行
# deploy/docker/docker-compose.yml 已把这三个脚本挂载到
# mysql 容器的 /docker-entrypoint-initdb.d/，首次启动按文件名顺序自动导入。
cd deploy/docker && docker compose up -d
```

> 文件名用 `01/02/03` 前缀就是为了让"执行顺序"显式化 ——
> MySQL 容器初始化目录、以及各类自动化脚本都按字典序执行。

## 与旧文件名的对应关系

如果你在旧文档/旧笔记里看到这些名字，对应关系如下：

| 旧文件名（根目录） | 现在的路径 |
| --- | --- |
| `db_schema.sql` | `db/schema/01-schema.sql` |
| `db_v011_recommend.sql` | `db/schema/02-recommend.sql` |
| `db_v013_admin.sql` | `db/schema/03-admin.sql` |
| `db_*_test.sql`（7 个） | `db/test-data/` 下同名文件 |
| `docs/常用查询.sql` | `db/常用查询.sql` |

## 查看数据库结构文档

`docs/数据库说明.md` 由 `tools/gen-db-doc.py` 依据 `information_schema` 自动生成，
与真实库结构始终一致：

```bash
python tools/gen-db-doc.py         # 重新生成 docs/数据库说明.md
```
