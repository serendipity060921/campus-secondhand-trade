# 二手校园交易系统 —— 系统需求与设计规格说明书

| 项目名称 | 二手校园交易系统（Campus Secondhand Trade） |
| --- | --- |
| 文档版本 | v1.0 |
| 文档类型 | 需求规格 + 概要设计（毕业设计） |
| 技术栈 | Spring Boot 3 + Vue 3 + MySQL 8 + MyBatis-Plus + Redis + JWT |
| 配套文件 | `db_schema.sql`（建表 SQL）、`spec.md`（本文档） |

> 说明：本文档只做需求与设计，不含业务代码实现。数据库脚本见同目录 `db_schema.sql`。
>
> **版本说明**：v0.01 需求分析（本文档）→ v0.02 数据库设计（`db_schema.sql`）→ **v0.03 前后端分离脚手架** → **v0.04 用户注册登录模块（JWT 鉴权）** → **v0.05 商品核心模块（含商品占位图）** → **v0.06 商品收藏模块** → **v0.07 私信聊天模块** → **v0.08 订单交易模块**，启动方式与各版本验收记录见 [README.md](./README.md)。

---

## 1. 项目概述

### 1.1 项目背景

校园内每年产生大量闲置物品（教材、电子产品、自行车、生活用品等），学生之间的二手交易需求旺盛，但现有渠道存在明显痛点：

- 微信群 / QQ 群 / 表白墙发帖：信息碎片化、无搜索、无分类、楼层刷屏后信息不可追溯；
- 闲鱼等公开平台：面向社会用户，交易对象不可信、同校见面交付不便、审核规则不适合校园场景；
- 缺少订单与评价闭环：交易过程无记录，出现纠纷难以追溯。

### 1.2 项目目标

构建一个**仅面向本校学生**的二手交易平台，实现「发布 → 审核 → 浏览/搜索 → 沟通 → 下单 → 交付 → 完成」的完整闭环，并具备管理员内容审核与基础运营管理能力。系统需满足毕业设计对**工程完整性**（分层架构、鉴权、参数校验、统一异常、分页、日志）与**技术广度**（Spring Boot 3 + Vue 3 前后端分离、JWT 无状态鉴权、Redis 缓存/验证码）的要求。

### 1.3 系统范围（边界）

| 范围内 | 范围外（本期不做） |
| --- | --- |
| 学生注册登录、浏览、发布、搜索、留言私信、下单、订单流转 | 在线支付（对接微信/支付宝支付网关） |
| 管理员对商品/用户/分类的审核与管理 | 物流对接、电子面单 |
| 校内交易（面交为主，可选快递信息登记） | 直播、短视频、算法推荐 |
| 图片上传（本地/对象存储） | 平台抽佣、资金托管、提现 |

> 设计取舍：**支付环节虚拟化**——订单状态由买卖双方在平台内手动推进（模拟支付按钮），以规避真实资金与支付资质问题，同时保证订单状态机完整可演示。

### 1.4 技术选型

| 层次 | 技术 | 版本 | 说明 |
| --- | --- | --- | --- |
| 后端框架 | Spring Boot | 3.2.x | Java 17 + Jakarta EE 9+ 命名空间 |
| 持久层 | MyBatis-Plus | 3.5.x | 单表 CRUD 零 SQL + 分页插件 |
| 数据库 | MySQL | 8.0 / 8.4 | InnoDB、utf8mb4 |
| 连接池 | HikariCP | 随 Boot | 默认 |
| 缓存 | Redis | 7.x | 验证码、热门搜索、Token 黑名单 |
| 鉴权 | JWT (jjwt) + Spring Security | 0.12.x / 6.x | 无状态鉴权 + 方法级权限 |
| 参数校验 | Jakarta Validation | - | `@Valid` + 全局异常处理 |
| 接口文档 | Knife4j / springdoc-openapi | 4.x / 2.x | Swagger 在线调试 |
| 工具 | Lombok、Hutool、MapStruct | - | 简化样板代码 |
| 前端框架 | Vue 3 | 3.4.x | Composition API + `<script setup>` |
| 构建工具 | Vite | 5.x | 开发热更新、生产打包 |
| UI 组件库 | Element Plus | 2.x | 后台管理 + 表单表格 |
| 状态管理 | Pinia | 2.x | 用户态、购物/收藏态 |
| 路由 | Vue Router | 4.x | 动态路由 + 路由守卫 |
| HTTP | Axios | 1.x | 请求/响应拦截器统一处理 Token 与错误 |
| 部署 | Nginx + jar | - | 前端静态资源 + 反向代理 |

---

## 2. 用户角色与权限

### 2.1 角色定义

系统共 **2 个登录角色**（普通学生、管理员）与 **1 个非登录角色**（游客）。

| 角色 | 标识（`user.role`） | 说明 | 关键差异 |
| --- | --- | --- | --- |
| 游客 | —（未登录） | 未注册/未登录访问者 | 只能浏览、搜索商品；不能发布、留言、下单 |
| 普通学生 | `0` | 通过学号 + 手机号注册、经校内身份校验的学生。**同一账号既是买家也是卖家**，不区分两个角色 | 可发布商品（需审核）、买卖下单、留言私信 |
| 管理员 | `1` | 平台运营人员，由超级管理员在后台创建/授权，不开放自助注册 | 商品审核、用户管理、分类管理、订单查询与干预 |

> 设计说明：需求中的「买家/卖家」在本系统中是**同一学生在不同订单中的行为身份**，而不是两套账号。订单表中同时保存 `buyer_id` 与 `seller_id` 来表达该关系，避免角色冗余与切换成本。

### 2.2 权限矩阵

| 功能 | 游客 | 普通学生 | 管理员 |
| --- | :---: | :---: | :---: |
| 浏览首页 / 商品列表 | ✅ | ✅ | ✅ |
| 查看商品详情 | ✅ | ✅ | ✅ |
| 注册 / 登录 | ✅ | ✅ | ✅ |
| 发布 / 编辑 / 下架商品 | ❌ | ✅（仅本人） | ✅（可强制下架） |
| 收藏商品 | ❌ | ✅ | ✅ |
| 商品留言 / 私信 | ❌ | ✅ | ✅ |
| 创建订单 / 取消订单 / 确认收货 | ❌ | ✅（仅参与方） | ❌（仅查询/干预） |
| 商品审核（通过/驳回） | ❌ | ❌ | ✅ |
| 分类管理（增删改） | ❌ | ❌ | ✅ |
| 用户管理（禁用/解禁） | ❌ | ❌ | ✅ |
| 全平台订单查询 | ❌ | ❌（仅本人相关） | ✅ |

### 2.3 关键用户故事（User Story）

1. 作为**学生卖家**，我想上传商品图片、填写价格与成色后发布，以便把闲置物品卖出去；发布后商品处于「待审核」，审核通过才在列表可见。
2. 作为**学生买家**，我想按分类筛选或用关键词搜索，并按价格/时间排序，以便快速找到想要的教材。
3. 作为**学生买家**，我想在商品下留言询问或直接私信卖家，以便确认成色与面交地点。
4. 作为**学生买家**，我想下单并查看订单状态，以便与卖家约定线下交付并最终确认收货。
5. 作为**学生卖家**，我想在订单里看到买家信息和交付方式，以便完成交易并让商品自动变为「已售出」。
6. 作为**管理员**，我想批量审核待审商品并填写驳回理由，以便过滤违规内容。
7. 作为**管理员**，我想管理分类树和禁用违规用户，以便维护平台秩序。

---

## 3. 功能需求

### 3.1 功能模块总览

```
二手校园交易系统
├── 1. 用户模块       注册、登录、登出、个人资料、修改密码、头像、实名/学号认证
├── 2. 商品模块       发布、编辑、下架、删除、图片上传、我的商品、商品详情、商品列表
├── 3. 分类模块       分类树查询（前台）、分类增删改（后台）
├── 4. 搜索模块       关键词搜索、分类筛选、价格区间、排序、分页、搜索历史/热词
├── 5. 留言私信模块   商品留言（含回复）、私信会话、未读消息数、消息已读
├── 6. 订单模块       下单、订单列表（买家/卖家视角）、订单详情、取消、支付(模拟)、交付、确认收货、完成
├── 7. 后台管理模块   商品审核、用户管理、订单管理、分类管理、数据概览
└── 8. 公共支撑模块   文件上传、统一响应、全局异常、JWT 拦截、参数校验、操作日志
```

### 3.2 功能清单（Feature List）

优先级：P0 = 必做（答辩演示主线）；P1 = 应做；P2 = 可做（加分项）。

| 编号 | 模块 | 功能点 | 角色 | 优先级 | 验收要点 |
| --- | --- | --- | --- | :---: | --- |
| F01 | 用户 | 学生注册 | 游客 | P0 | 用户名唯一、学号唯一、两次密码一致、密码 BCrypt 加密入库 |
| F02 | 用户 | 账号密码登录 | 游客 | P0 | 返回 JWT；密码错误提示模糊化；连续失败锁定（Redis 计数） |
| F03 | 用户 | 图形/短信验证码登录 | 游客 | P2 | 验证码存 Redis，5 分钟过期 |
| F04 | 用户 | 登出 | 学生/管理员 | P1 | Token 加入 Redis 黑名单即时失效 |
| F05 | 用户 | 查看/修改个人资料 | 学生 | P0 | 头像、昵称、手机号、校区可改；学号不可改 |
| F06 | 用户 | 修改密码 | 学生 | P1 | 校验旧密码；修改后旧 Token 失效 |
| F07 | 商品 | 发布商品 | 学生 | P0 | 必填校验；最多 9 张图；发布后状态=待审核 |
| F08 | 商品 | 编辑商品 | 学生 | P0 | 仅本人且未被下单；编辑后重新进入待审核 |
| F09 | 商品 | 上下架商品 | 学生 | P0 | 在售 ↔ 已下架；已售出不可再上架 |
| F10 | 商品 | 删除商品 | 学生 | P1 | 逻辑删除；有进行中订单时禁止删除 |
| F11 | 商品 | 我的商品列表 | 学生 | P0 | 按状态 Tab 分类、分页 |
| F12 | 商品 | 商品详情 | 全部 | P0 | 浏览量 +1（Redis 计数异步落库） |
| F13 | 商品 | 商品列表/首页推荐 | 全部 | P0 | 仅展示审核通过且在售商品 |
| F14 | 分类 | 分类树查询 | 全部 | P0 | 二级分类、按 `sort_order` 排序 |
| F15 | 分类 | 分类增删改 | 管理员 | P0 | 有商品的分类不可删除 |
| F16 | 搜索 | 关键词搜索 | 全部 | P0 | 匹配标题/描述；结果高亮；防 SQL 注入 |
| F17 | 搜索 | 多条件筛选 | 全部 | P0 | 分类 + 价格区间 + 成色 + 校区 |
| F18 | 搜索 | 排序 | 全部 | P1 | 最新发布 / 价格升 / 价格降 / 浏览量 |
| F19 | 搜索 | 搜索历史与热词 | 学生 | P2 | Redis ZSet 统计热词 |
| F20 | 留言 | 商品留言 | 学生 | P0 | 支持对留言回复（`parent_id`）；展示留言者昵称与头像 |
| F21 | 留言 | 删除自己的留言 | 学生 | P2 | 逻辑删除；管理员可删任意留言 |
| F22 | 留言 | 留言通知 | 学生 | P1 | 商品被留言时通知卖家 |
| F23 | 私信 | 发起私信 | 学生 | P0 | 从商品详情「联系卖家」进入会话 |
| F24 | 私信 | 会话列表与聊天记录 | 学生 | P1 | 按对方维度聚合，展示最后一条与未读数 |
| F25 | 私信 | 未读消息数 | 学生 | P1 | 顶部红点；进入会话即清零 |
| F26 | 订单 | 创建订单 | 学生(买家) | P0 | 商品必须为在售；不可购买自己的商品；生成唯一订单号；商品状态→锁定/已售 |
| F27 | 订单 | 我的订单（买/卖） | 学生 | P0 | 双视角 Tab（我买到的 / 我卖出的）+ 状态筛选 |
| F28 | 订单 | 订单详情 | 学生(参与方) | P0 | 展示商品快照、金额、买家/卖家、交付方式 |
| F29 | 订单 | 取消订单 | 学生(买家)/管理员 | P0 | 仅待付款可取消；取消后商品回到在售 |
| F30 | 订单 | 模拟支付 | 学生(买家) | P1 | 待付款 → 待交付，写入 `pay_time` |
| F31 | 订单 | 卖家确认交付 | 学生(卖家) | P0 | 待交付 → 待收货；登记交付地点/面交时间 |
| F32 | 订单 | 买家确认收货 | 学生(买家) | P0 | 待收货 → 已完成；商品状态 → 已售出；双方信用分 +1 |
| F33 | 订单 | 订单超时自动取消 | 系统 | P2 | 定时任务扫描 30 分钟未支付订单 |
| F34 | 后台 | 商品审核列表 | 管理员 | P0 | 按状态筛选；支持关键词与提交时间范围 |
| F35 | 后台 | 商品审核通过与驳回 | 管理员 | P0 | 驳回必填理由；记录审核人与审核时间；通知卖家 |
| F36 | 后台 | 商品强制下架/删除 | 管理员 | P0 | 违规商品处理，保留操作日志 |
| F37 | 后台 | 用户管理 | 管理员 | P1 | 列表、检索、禁用/解禁、重置密码 |
| F38 | 后台 | 订单管理 | 管理员 | P1 | 全平台订单查询、查看详情、异常订单关闭 |
| F39 | 后台 | 数据概览 | 管理员 | P1 | 用户数、在售商品数、今日订单数、待审核数 |
| F40 | 支撑 | 图片上传 | 学生 | P0 | 校验后缀与大小（≤5MB），返回 URL |
| F41 | 支撑 | 统一响应与全局异常 | 系统 | P0 | `code/message/data` 结构；业务异常统一转换 |
| F42 | 支撑 | 操作日志 | 管理员 | P2 | 记录后台关键操作（审核、禁用） |

### 3.3 非功能性需求

| 类别 | 要求 |
| --- | --- |
| 性能 | 列表接口 P95 ≤ 500ms（数据量 10 万商品）；分页单页 ≤ 20 条；热门分类与首页列表走 Redis 缓存 |
| 安全 | 密码不可逆加密（BCrypt）；JWT 有效期 2h + Refresh 机制；接口防越权（资源归属校验，禁止横向越权改他人商品）；MyBatis 预编译防注入；上传文件类型白名单；敏感字段（密码、手机号）响应脱敏 |
| 一致性 | 下单、支付、确认收货使用 `@Transactional`；商品与订单状态变更需保证「商品不可被重复下单」（`product.status` 条件更新 + 乐观校验） |
| 可用性 | 前端适配 1366×768 及以上；列表空态/加载态/错误态齐全；操作有二次确认与 Toast 反馈 |
| 可维护性 | 后端严格分层（Controller/Service/Mapper/Entity/DTO/VO），统一命名；数据库表与字段均带注释；关键流程写清日志 |
| 兼容性 | Chrome / Edge 最新版；后端 JDK 17+；MySQL 8.0+ |

---

## 4. 核心业务流程

### 4.1 商品发布与审核流程

```
学生填写商品信息 + 上传图片
        │
        ▼
   提交成功 → product.status = 0（待审核）──通知管理员
        │
        ├── 管理员审核通过 → status = 1（在售）→ 出现在列表/搜索
        │
        └── 管理员驳回（必填理由）→ status = 2（审核不通过）→ 学生修改后重新提交（回到 status = 0）
```

### 4.2 交易主流程（订单状态机）

```
[在售] ──买家下单──▶ 订单 status=0 待付款 ──买家取消──▶ status=4 已取消（商品回到在售）
                          │
                    模拟支付│
                          ▼
                    status=1 待交付 ──卖家确认交付──▶ status=2 待收货
                                                          │
                                                    买家确认收货
                                                          ▼
                                             status=3 已完成（商品 → 已售出）
```

- **锁定策略**：下单成功即将 `product.status` 置为「交易中/已锁定」，防止一件商品被多人同时下单；订单取消则回滚为「在售」。
- **快照**：订单表冗余保存商品标题、封面、成交价，避免商品后续修改影响历史订单。

### 4.3 留言与私信流程

```
商品详情页
 ├── 留言区：登录后可发表留言 → 展示在商品下（含回复，parent_id 关联）→ 通知卖家
 └── 「联系卖家」按钮 → 创建/进入与卖家的私信会话 → 双方可互发消息 → 未读数提示
```

---

## 5. 数据库设计说明

> 完整建表语句见 **[db_schema.sql](./db_schema.sql)**。字符集统一 `utf8mb4`，引擎统一 `InnoDB`，所有表带 `create_time` / `update_time`，主表带逻辑删除字段 `deleted`。

### 5.1 数据表清单

| 序号 | 表名 | 中文名 | 说明 | 类型 |
| --- | --- | --- | --- | --- |
| 1 | `user` | 用户表 | 学生与管理员共用，`role` 区分 | 核心 |
| 2 | `category` | 商品分类表 | 支持二级分类（`parent_id` 自关联） | 核心 |
| 3 | `product` | 商品表 | 含审核字段与统计字段 | 核心 |
| 4 | `message` | 留言/私信表 | `type` 区分商品留言与私信，一张表承载两类消息 | 核心 |
| 5 | `orders` | 订单表 | 名称用复数规避 MySQL 保留字 `ORDER` | 核心 |
| 6 | `product_image` | 商品图片表 | 一商品多图，`sort_order` 控制首图 | 扩展 |
| 7 | `favorite` | 收藏表 | 用户 ↔ 商品多对多，唯一索引防重复收藏 | 扩展 |

### 5.2 关键字段设计要点

| 表 | 字段 | 设计考虑 |
| --- | --- | --- |
| `user` | `password` | 存 BCrypt 密文，长度 100，禁止明文/MD5 |
| `user` | `student_no` | 唯一索引，作为校内身份凭证；注册后不可修改 |
| `user` | `role` | `0` 学生、`1` 管理员；用 TINYINT 节省空间，配合枚举常量 |
| `user` | `status` | `1` 正常、`0` 禁用；禁用用户登录直接拦截 |
| `category` | `parent_id` | `0` 表示一级分类，其余为二级；删除前校验是否存在子分类/商品 |
| `product` | `price` / `original_price` | `DECIMAL(10,2)`，禁止用 FLOAT/DOUBLE 存金额 |
| `product` | `condition_level` | 成色 1–4；**不叫 `condition`**，因其为 MySQL 保留字 |
| `product` | `status` | `0` 待审核、`1` 在售、`2` 审核不通过、`3` 已下架、`4` 交易中、`5` 已售出 |
| `product` | `audit_*` | 审核人、审核时间、驳回理由，满足后台审核可追溯要求 |
| `product` | 冗余 `seller_id` + 索引 | 支撑「我的商品」高频查询 |
| `message` | `type` + `product_id` + `from/to_user_id` | 一条记录同时表达「商品留言」与「私信」；`product_id` 为 NULL 表示纯私信 |
| `message` | `parent_id` | 留言盖楼回复，`0` 表示顶层 |
| `orders` | `order_no` | 唯一索引，业务订单号（如 `20250101120000` + 随机位），对外暴露而非自增 ID |
| `orders` | 双 `buyer_id`/`seller_id` | 支持买家视角与卖家视角双列表查询，均有索引 |
| `orders` | 商品快照字段 | 标题/封面/成交价冗余，保证历史订单可读 |
| `orders` | `status` | `0` 待付款、`1` 待交付、`2` 待收货、`3` 已完成、`4` 已取消、`5` 已退款 |
| 全表 | `deleted` | 逻辑删除，避免物理删除破坏订单/留言的历史关联 |
| 全表 | 外键 `FOREIGN KEY` | 脚本中显式声明外键约束，保证毕设 ER 图与实现一致；如追求高并发可改为应用层保证 |

### 5.3 索引设计

| 表 | 索引 | 目的 |
| --- | --- | --- |
| `user` | `uk_username`、`uk_student_no`、`uk_phone` | 登录与唯一性校验 |
| `category` | `idx_parent_id` | 分类树查询 |
| `product` | `idx_category_id`、`idx_seller_id`、`idx_status`、`idx_create_time`、`idx_title` | 分类筛选、我的商品、审核列表、按时间排序、标题检索 |
| `product` | 组合索引 `idx_status_category` | 覆盖「在售 + 分类」的列表主查询 |
| `message` | `idx_product_id`、`idx_to_user_read`、`idx_conversation` | 商品留言、未读数统计、私信会话查询 |
| `orders` | `uk_order_no`、`idx_buyer_status`、`idx_seller_status`、`idx_product_id` | 订单号唯一、双视角列表、按商品回溯订单 |
| `favorite` | `uk_user_product` | 防重复收藏 + 快速查询 |

> 设计取舍：全文检索需求若后续增强，可将 `product` 的标题/描述迁移到 Elasticsearch 或 MySQL 全文索引（`FULLTEXT`），本期先用 `LIKE '%kw%'` + 组合索引满足毕设数据量。

---

## 6. 接口设计概览（RESTful 约定）

- 统一前缀 `/api`，统一响应体 `{ "code": 200, "message": "success", "data": ... }`；分页响应 `{ "total": n, "pages": n, "current": n, "records": [...] }`。
- 鉴权：请求头 `Authorization: Bearer <token>`。

| 模块 | 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- | --- |
| 认证 | POST | `/api/auth/register` | 学生注册 | 公开 |
| 认证 | POST | `/api/auth/login` | 登录，返回 Token | 公开 |
| 认证 | POST | `/api/auth/logout` | 登出 | 登录 |
| 用户 | GET/PUT | `/api/user/profile` | 查询/修改个人资料 | 登录 |
| 用户 | PUT | `/api/user/password` | 修改密码 | 登录 |
| 分类 | GET | `/api/categories/tree` | 分类树 | 公开 |
| 分类 | POST/PUT/DELETE | `/api/admin/categories/**` | 分类管理 | 管理员 |
| 商品 | GET | `/api/products` | 商品列表（分类/关键词/价格/排序/分页） | 公开 |
| 商品 | GET | `/api/products/{id}` | 商品详情 | 公开 |
| 商品 | POST/PUT/DELETE | `/api/products` | 发布/编辑/删除 | 登录 |
| 商品 | PUT | `/api/products/{id}/shelf` | 上/下架 | 登录（本人） |
| 商品 | GET | `/api/products/mine` | 我的商品 | 登录 |
| 留言 | GET/POST | `/api/products/{id}/messages` | 留言列表/发表留言 | 公开读 / 登录写 |
| 私信 | GET | `/api/messages/conversations` | 会话列表 | 登录 |
| 私信 | GET/POST | `/api/messages/chat/{userId}` | 聊天记录/发送私信 | 登录 |
| 订单 | POST | `/api/orders` | 创建订单 | 登录（买家） |
| 订单 | GET | `/api/orders` | 我的订单（`role=buyer/seller`） | 登录 |
| 订单 | GET | `/api/orders/{id}` | 订单详情 | 参与方 |
| 订单 | PUT | `/api/orders/{id}/pay` | 模拟支付 | 买家 |
| 订单 | PUT | `/api/orders/{id}/deliver` | 卖家确认交付 | 卖家 |
| 订单 | PUT | `/api/orders/{id}/confirm` | 买家确认收货 | 买家 |
| 订单 | PUT | `/api/orders/{id}/cancel` | 取消订单 | 买家 |
| 后台 | GET | `/api/admin/products/audit` | 待审核商品列表 | 管理员 |
| 后台 | PUT | `/api/admin/products/{id}/audit` | 审核通过/驳回 | 管理员 |
| 后台 | GET/PUT | `/api/admin/users/**` | 用户列表/禁用 | 管理员 |
| 后台 | GET | `/api/admin/orders` | 全平台订单 | 管理员 |
| 后台 | GET | `/api/admin/stats` | 数据概览 | 管理员 |
| 上传 | POST | `/api/files/upload` | 图片上传 | 登录 |

---

## 7. 项目目录结构

### 7.1 仓库根目录

```
campus-secondhand-trade/
├── backend/                      # Spring Boot 3 后端工程
├── frontend/                     # Vue 3 前端工程（学生端 + 管理端）
├── docs/                         # 设计文档与图表
│   ├── spec.md                   # 需求与设计规格（软链/副本）
│   ├── er-diagram.png            # ER 图（后续补充）
│   └── api.md                    # 接口详细文档（后续补充）
├── db_schema.sql                 # 数据库建表脚本（含初始化数据）
├── db_smoke_test.sql             # 数据库结构与业务冒烟测试脚本（在 MySQL 8.4.4 实测通过）
├── spec.md                       # 系统需求与设计规格说明书（本文件）
├── README.md                     # 项目说明与启动指南
└── .gitignore
```

### 7.2 后端目录（Spring Boot 3，Maven 单模块，包名 `com.campus.trade`）

```
backend/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/campus/trade/
│   │   │   ├── CampusTradeApplication.java        # 启动类
│   │   │   ├── common/                            # 公共层
│   │   │   │   ├── result/                        # Result、PageResult、ResultCode
│   │   │   │   ├── exception/                     # 业务异常 + 全局异常处理器
│   │   │   │   ├── constant/                      # 常量、枚举（商品状态、订单状态、角色）
│   │   │   │   ├── annotation/                    # @LoginRequired、@AdminOnly 等自定义注解
│   │   │   │   └── util/                          # JwtUtil、OrderNoUtil、FileUtil
│   │   │   ├── config/                            # 配置层
│   │   │   │   ├── SecurityConfig.java            # Spring Security + 过滤器链
│   │   │   │   ├── MybatisPlusConfig.java         # 分页插件、字段自动填充
│   │   │   │   ├── RedisConfig.java
│   │   │   │   ├── WebMvcConfig.java              # 静态资源、跨域、拦截器注册
│   │   │   │   └── Knife4jConfig.java             # 接口文档
│   │   │   ├── controller/                        # 接口层
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── UserController.java
│   │   │   │   ├── CategoryController.java
│   │   │   │   ├── ProductController.java
│   │   │   │   ├── MessageController.java
│   │   │   │   ├── OrderController.java
│   │   │   │   ├── FileController.java
│   │   │   │   └── admin/                         # 后台管理接口
│   │   │   │       ├── AdminProductController.java
│   │   │   │       ├── AdminUserController.java
│   │   │   │       ├── AdminCategoryController.java
│   │   │   │       └── AdminOrderController.java
│   │   │   ├── service/                           # 业务接口
│   │   │   │   ├── impl/                          # 业务实现
│   │   │   │   ├── UserService.java
│   │   │   │   ├── ProductService.java
│   │   │   │   ├── CategoryService.java
│   │   │   │   ├── MessageService.java
│   │   │   │   └── OrderService.java
│   │   │   ├── mapper/                            # MyBatis-Plus Mapper
│   │   │   │   ├── UserMapper.java
│   │   │   │   ├── ProductMapper.java
│   │   │   │   ├── CategoryMapper.java
│   │   │   │   ├── MessageMapper.java
│   │   │   │   └── OrderMapper.java
│   │   │   ├── entity/                            # 与数据表一一对应
│   │   │   │   ├── User.java
│   │   │   │   ├── Product.java
│   │   │   │   ├── Category.java
│   │   │   │   ├── Message.java
│   │   │   │   ├── Order.java
│   │   │   │   ├── ProductImage.java
│   │   │   │   └── Favorite.java
│   │   │   ├── dto/                               # 入参对象（含校验注解）
│   │   │   │   ├── RegisterDTO.java / LoginDTO.java
│   │   │   │   ├── ProductDTO.java / ProductQueryDTO.java
│   │   │   │   ├── MessageDTO.java
│   │   │   │   └── OrderCreateDTO.java / AuditDTO.java
│   │   │   ├── vo/                                # 出参对象（脱敏、聚合）
│   │   │   │   ├── LoginVO.java / UserVO.java
│   │   │   │   ├── ProductVO.java / ProductDetailVO.java
│   │   │   │   ├── MessageVO.java / ConversationVO.java
│   │   │   │   └── OrderVO.java / StatsVO.java
│   │   │   └── interceptor/                       # JwtInterceptor、AuthInterceptor
│   │   └── resources/
│   │       ├── application.yml                    # 主配置
│   │       ├── application-dev.yml                # 开发环境（本地 MySQL/Redis）
│   │       ├── application-prod.yml               # 生产环境
│   │       ├── mapper/                            # 复杂 SQL 的 XML（可选）
│   │       │   ├── ProductMapper.xml
│   │       │   └── OrderMapper.xml
│   │       └── static/upload/                     # 本地上传目录（开发用）
│   └── test/java/com/campus/trade/                # 单元测试
│       ├── service/OrderServiceTest.java
│       └── mapper/ProductMapperTest.java
└── target/                                        # 构建产物（.gitignore）
```

### 7.3 前端目录（Vue 3 + Vite）

```
frontend/
├── index.html
├── package.json
├── vite.config.js
├── .env.development / .env.production             # VITE_API_BASE 等
├── public/
│   └── favicon.ico
└── src/
    ├── main.js                                    # 应用入口（挂载 Pinia / Router / Element Plus）
    ├── App.vue
    ├── api/                                       # 接口封装（按模块拆分）
    │   ├── request.js                             # Axios 实例 + 拦截器（Token、401 跳转、错误提示）
    │   ├── auth.js
    │   ├── user.js
    │   ├── category.js
    │   ├── product.js
    │   ├── message.js
    │   ├── order.js
    │   └── admin.js
    ├── assets/                                    # 图片、全局样式
    │   ├── images/
    │   └── styles/global.scss
    ├── components/                                # 通用组件
    │   ├── ProductCard.vue                        # 商品卡片
    │   ├── CategoryTree.vue                       # 分类树
    │   ├── ImageUpload.vue                        # 图片上传
    │   ├── OrderStatusTag.vue
    │   ├── EmptyState.vue
    │   └── Pagination.vue
    ├── layout/                                    # 布局
    │   ├── FrontLayout.vue                        # 学生端：顶部导航 + 内容 + 页脚
    │   ├── AdminLayout.vue                        # 管理端：侧边菜单 + 面包屑
    │   └── components/Navbar.vue, Sidebar.vue, Footer.vue
    ├── router/
    │   ├── index.js                               # 路由表 + 全局前置守卫（鉴权/角色）
    │   └── modules/front.js, admin.js
    ├── store/                                     # Pinia
    │   ├── index.js
    │   ├── modules/user.js                        # Token、用户信息、角色
    │   ├── modules/category.js
    │   └── modules/message.js                     # 未读消息数
    ├── utils/
    │   ├── auth.js                                # Token 存取
    │   ├── format.js                              # 价格、时间格式化
    │   └── validate.js                            # 手机号/学号等校验规则
    └── views/
        ├── home/Index.vue                         # 首页（分类入口 + 最新商品）
        ├── auth/Login.vue, Register.vue
        ├── product/List.vue                       # 列表 + 搜索 + 筛选
        ├── product/Detail.vue                     # 详情 + 留言 + 联系卖家
        ├── product/Publish.vue                    # 发布/编辑商品
        ├── user/Profile.vue, MyProducts.vue, MyFavorites.vue
        ├── message/Chat.vue                       # 私信会话
        ├── order/Create.vue, MyOrders.vue, Detail.vue
        ├── error/404.vue
        └── admin/
            ├── Dashboard.vue                      # 数据概览
            ├── ProductAudit.vue                   # 商品审核
            ├── CategoryManage.vue
            ├── UserManage.vue
            └── OrderManage.vue
```

---

## 8. 后续实施计划（建议排期）

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| 第 1 周 | 需求确认、数据库落地、工程骨架搭建 | 本文档、`db_schema.sql`、前后端空工程可启动 |
| 第 2 周 | 用户模块 + JWT 鉴权 + 统一响应/异常 | 注册登录联调通过 |
| 第 3 周 | 分类 + 商品发布 + 图片上传 + 商品列表/详情 | 商品主线可演示 |
| 第 4 周 | 搜索筛选 + 留言私信 | 互动功能可用 |
| 第 5 周 | 订单模块（状态机）+ 后台商品审核 | 交易闭环完成 |
| 第 6 周 | 后台用户/订单/分类管理 + 数据概览 | 管理端完整 |
| 第 7 周 | 联调、压测、异常场景修复、论文撰写 | 测试报告、论文 |
| 第 8 周 | 部署（Nginx + jar）、答辩演示脚本与 PPT | 答辩材料 |

---

## 9. 附：数据库字段与需求对应关系（可追溯性）

| 需求编号 | 涉及表 | 关键字段 |
| --- | --- | --- |
| F01–F06 用户 | `user` | `username`、`password`、`student_no`、`role`、`status` |
| F07–F13 商品 | `product`、`product_image`、`category` | `title`、`price`、`condition_level`、`status`、`audit_remark` |
| F14–F15 分类 | `category` | `parent_id`、`name`、`sort_order` |
| F16–F19 搜索 | `product` | `title`、`description`、`category_id`、`price`、`view_count`、`create_time` |
| F20–F25 留言私信 | `message` | `type`、`product_id`、`from_user_id`、`to_user_id`、`parent_id`、`is_read` |
| F26–F33 订单 | `orders`、`product` | `order_no`、`buyer_id`、`seller_id`、`amount`、`status`、`pay_time` |
| F34–F39 后台 | `product`、`user`、`orders`、`category` | `auditor_id`、`audit_time`、`status` |
| F40–F42 支撑 | `product_image` | `url`、`sort_order` |

---

## 10. SQL 脚本校验记录

`db_schema.sql` 已在**真实 MySQL 8.4.4 社区版服务器**（`sql_mode` 含 `STRICT_TRANS_TABLES`）上整体执行并通过，同时用 `db_smoke_test.sql` 完成结构与业务冒烟测试。

| 校验项 | 结果 |
| --- | --- |
| `db_schema.sql` 首次执行 | 退出码 0，无 ERROR、无 WARNING（仅 `DROP TABLE IF EXISTS` 的 1051 Note） |
| `db_schema.sql` 重复执行 | 退出码 0，仅 1007 Note（库已存在），脚本可重复运行 |
| 建表数量 / 列数量 / 外键数量 / 索引条目 | 7 / 99 / 12 / 42 |
| 引擎与字符集 | 7 张表全部 InnoDB + `utf8mb4_general_ci` |
| 注释覆盖率 | 表注释 7/7，列注释 99/99 |
| 唯一索引 | `uk_username`、`uk_student_no`、`uk_phone`、`uk_parent_name`、`uk_order_no`、`uk_user_product` 全部生效 |
| 初始化数据 | 分类 32 条（一级 8 + 二级 24），管理员账号 1 个 |
| 业务链路 | 注册 → 发布(待审核) → 审核通过(在售) → 留言+回复 → 私信 → 下单(交易中) → 支付 → 交付 → 确认收货(已完成/已售出)，全部成功 |
| 约束反向验证 | 用户名重复 → 1062；外键引用不存在用户 → 1452；缺必填列 → 1364；重复收藏 → 1062，均为预期失败 |

> 结论：脚本语法与语义正确，可直接用于开发环境初始化。管理员初始密码为 `123456`（BCrypt 已校验），首次登录后请立即修改。

