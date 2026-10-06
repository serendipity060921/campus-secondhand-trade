# 校园二手交易平台（Campus Secondhand Trade）

> 毕业设计项目 · **当前版本 v0.16** · 功能开发与全站 UI 改版均已完成
> 技术栈：Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis + WebSocket + Vue 3 + Vite 5 + Element Plus + Pinia

面向校园的二手交易平台，覆盖**发布 → 审核 → 检索 → 沟通 → 下单 → 交付**的完整业务闭环：

| 模块 | 内容 |
| --- | --- |
| 商品 | 发布（多图上传）、管理员审核状态机、上下架权限、分类树筛选、关键词搜索、分页 |
| 交易 | 收藏、下单、订单状态流转、买卖双方订单列表与权限校验 |
| 沟通 | 站内实时私信（WebSocket 服务端推送、握手 JWT 鉴权、Redis 在线状态、已读回执） |
| 推荐 | 三路召回 + 融合排序的个性化推荐，含行为埋点与离线对比实验 |
| 后台 | 数据看板（ECharts）、商品审核、用户管理、举报处理、管理员操作审计日志 |
| 基础设施 | Redis 缓存与接口限流（防穿透/雪崩/击穿）、JWT 鉴权、Nginx 反向代理、Docker Compose |

**质量保障**（全部纳入 CI）：

| 检查 | 结果 |
| --- | --- |
| 端到端测试（Playwright，主业务闭环） | **31 条全通过** |
| 部署验证（接口级） | **28 条全通过** |
| 单元测试（Node） | **31 条全通过** |
| 后台管理端到端 | 通过（页面 JS 错误 0、测试数据残留 0） |
| 静态检查门禁 | 设计令牌纪律 0 错误、状态完备性 20/20、可访问性 12 页核心项通过 |

各版本实现内容见 [docs/milestones/](./docs/milestones/) 与 [CHANGELOG.md](./CHANGELOG.md)。

---

## 一、环境要求

| 软件 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | **17 或以上** | Spring Boot 3 最低要求 JDK 17（JDK 8/11 无法启动） |
| Maven | 3.8+ | 项目自带 Maven Wrapper（`mvnw.cmd`），无需单独安装 |
| MySQL | **8.0 或以上** | 字符集 utf8mb4 |
| Node.js | 18+（推荐 20 LTS） | 前端构建 |
| pnpm 或 npm | pnpm 8+ / npm 9+ | 二者任选，命令见下文 |

> 检查环境：`java -version`、`mysql --version`、`node -v`

---

## 二、目录结构

```
campus-secondhand-trade/
├── README.md                       # 项目简介、快速开始、文档索引（本文件）
├── CHANGELOG.md                    # 版本变更记录（v0.01 ~ 当前）
├── LICENSE                         # 开源许可
├── spec.md                         # 需求与设计规格说明书
│
├── backend/                        # 后端：Spring Boot 3 + MyBatis-Plus
│   └── src/main/java/com/campus/trade/
│       ├── common/                 # 统一响应 Result、全局异常、JWT 工具、缓存与限流基础设施
│       │   ├── result/  exception/  annotation/  aspect/  cache/  constant/  context/  util/
│       ├── config/                 # 跨域、MyBatis-Plus、上传、Redis、WebSocket、推荐/缓存参数
│       ├── entity/  mapper/        # 实体与持久层（10 张表）
│       ├── dto/  vo/               # 入参与出参对象
│       ├── service/ + service/impl # 业务层（含推荐、缓存、在线状态等协作服务）
│       ├── controller/             # 接口层（用户/商品/分类/收藏/消息/订单/推荐/举报/管理后台/健康检查）
│       └── websocket/              # 实时私信：握手鉴权、会话注册表、JSON 信封协议
│
├── frontend/                       # 前端：Vue 3 + Vite + Element Plus + ECharts
│   └── src/
│       ├── api/                    # 按模块封装的接口（request.js 统一注入 Token）
│       ├── layout/                 # 前台布局 + 管理后台布局
│       ├── router/index.js         # 路由表 + 登录/管理员双层守卫
│       ├── store/                  # Pinia：user（登录态）、chat（实时状态）
│       ├── utils/                  # auth（Token）、product、websocket（单例连接）
│       ├── components/             # 通用组件（推荐面板等）
│       └── views/                  # 页面：product / message / order / profile / admin
│
├── db/                             # 数据库脚本（v0.16 起集中管理）
│   ├── schema/                     # 生产建库脚本，按顺序执行
│   │   ├── 01-schema.sql           #   建表 + 分类初始化数据
│   │   ├── 02-recommend.sql        #   user_behavior 表 + 行为数据回填
│   │   └── 03-admin.sql            #   report / admin_log 表 + 演示数据
│   ├── test-data/                  # 仅测试用的造数脚本（勿在生产执行）
│   └── 常用查询.sql                 # 排查问题常用的 SQL 片段
│
├── tests/                          # 测试资产（v0.16 起从 docs/ 迁出）
│   ├── api/                        # 接口级用例：v0.10 全量 / v0.11~v0.15 专项
│   └── e2e/                        # 浏览器端到端：主业务闭环、管理后台、实时私信
│
├── tools/                          # 工具与生成器
│   ├── load-test.py                # 压测脚本（含数据库语句数统计）
│   ├── recommend-eval.py           # 推荐算法离线评测（6 种策略对比）
│   ├── gen-db-doc.py               # 由 information_schema 生成数据库说明书
│   ├── gen-test-doc.py             # 由测试结果生成用例表与测试报告
│   └── 打开数据库(HeidiSQL).bat      # 常用运维小工具
│
├── docs/                           # 文档（只放文档，不放代码）
│   ├── api.md                      # 接口文档（53 个接口 + WebSocket 协议）
│   ├── 数据库说明.md                 # 10 张表逐字段说明（自动生成）
│   ├── 推荐算法说明.md               # 算法设计与离线实验数据
│   ├── 缓存与限流说明.md             # 缓存策略与压测对比
│   ├── 管理后台说明.md               # 后台功能与权限设计
│   ├── 实时通信说明.md               # WebSocket 协议与可靠性设计
│   ├── 部署说明.md                  # 部署手册（Docker / Windows 两条路径）
│   ├── 数据库查看指南.md             # 怎么看库、常用查询
│   ├── test-cases.md / test-report-v0.10.md
│   ├── milestones/                 # 各版本里程碑详解（由 README 拆分而来）
│   └── test-evidence/              # 测试证据截图（按版本）
│
└── deploy/                         # 部署配置
    ├── docker/                     # docker-compose + 前后端 Dockerfile + 容器版 Nginx
    └── windows/                    # Windows 原生一键启停脚本 + Nginx 配置
```

---

## 三、启动步骤

### 第 0 步：初始化数据库（只需执行一次）

```bash
# 在项目根目录执行，会创建 campus_trade 库、7 张表和初始化数据
mysql -u root -p < db/schema/01-schema.sql

# 验证
mysql -u root -p -e "USE campus_trade; SHOW TABLES; SELECT COUNT(*) FROM category;"
# 预期：7 张表；分类 32 条
```

### 第 1 步：启动后端

**方式 A：IDEA / Eclipse（推荐）**

1. 用 IDEA 打开 `backend` 目录（作为 Maven 项目导入）；
2. 确认 Project SDK 为 **JDK 17**；
3. 修改数据库账号密码：`backend/src/main/resources/application-dev.yml`
   ```yaml
   spring:
     datasource:
       url: jdbc:mysql://localhost:3306/campus_trade?...
       username: root
       password: 你的MySQL密码      # ← 改成自己的密码
   ```
4. 运行 `CampusTradeApplication.java`。

**方式 B：命令行（Maven Wrapper）**

```bash
cd backend

# Windows
set JAVA_HOME=D:\path\to\jdk-17
mvnw.cmd spring-boot:run

# macOS / Linux
export JAVA_HOME=/path/to/jdk-17
./mvnw spring-boot:run
```

**方式 C：打包后运行**

```bash
cd backend
mvnw.cmd clean package -DskipTests
java -jar target/campus-trade-backend-0.0.3.jar
```

> 数据库账号密码也可以用环境变量覆盖，无需改代码：
> `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` / `MYSQL_USER` / `MYSQL_PASSWORD`
> 例如：`set MYSQL_PASSWORD=123456 && mvnw.cmd spring-boot:run`

**启动成功标志**

```
============================================================
  校园二手交易平台后端启动成功
  接口地址：http://localhost:8080/api/health
  版本信息：v0.03 - 前后端分离脚手架
============================================================
```

浏览器访问 <http://localhost:8080/api/health> 应返回：

```json
{ "code": 200, "message": "后端服务运行正常", "data": { "status": "UP", "version": "v0.03", ... } }
```

### 第 2 步：启动前端

```bash
cd frontend

# 安装依赖（任选其一）
pnpm install          # 推荐
# npm install

# 启动开发服务器
pnpm dev              # 或 npm run dev
```

启动成功后会输出：

```
  VITE v5.x  ready in xxx ms
  ➜  Local:   http://localhost:5173/
```

**验证前后端连通**

1. 浏览器打开 <http://localhost:5173/>；
2. 首页会自动请求 `/api/health`、`/api/health/db`、`/api/categories`、`/api/products`，
   页面上会显示：后端版本与 JDK、MySQL 中的用户/分类/商品数量、分类表真实数据；
3. 打开 <http://localhost:5173/login>，点击「检测后端接口」也可查看连通状态。

命令行验证（Vite 代理是否生效）：

```bash
curl http://localhost:5173/api/health        # 经 Vite 代理访问后端
curl http://localhost:8080/api/health/db     # 直连后端（含 MySQL 查询）
```

---

## 四、脚手架阶段已提供的接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 后端存活检查（返回应用名、版本、环境、JDK 版本） |
| GET | `/api/health/db` | MySQL 连通性检查（真实 count 查询：用户/分类/商品） |
| GET | `/api/categories?parentId=0` | 分类列表（演示 Controller→Service→Mapper→MySQL 分层调用） |
| GET | `/api/products?page=1&size=10` | 商品分页（演示 MyBatis-Plus 分页插件 + 统一分页响应） |
| GET | `/api/products/{id}` | 商品详情（演示路径参数与不存在时的统一错误响应） |

**统一响应格式**

```json
{ "code": 200, "message": "success", "data": {}, "timestamp": 1717200000000 }
```

分页响应 data 结构：

```json
{ "total": 0, "pages": 0, "current": 1, "size": 10, "records": [] }
```

**全局异常处理已覆盖**：业务异常、参数校验失败、缺少参数、类型不匹配、JSON 格式错误、
请求方法不支持、404、数据库唯一键冲突、兜底异常 —— 全部返回统一 JSON，不会出现 Spring 默认错误页。

---

## 五、跨域说明

前后端分离下有两种方式，本项目**两种都已配置**，任选其一：

| 方式 | 位置 | 特点 |
| --- | --- | --- |
| Vite 代理（开发推荐） | `frontend/vite.config.js` 的 `server.proxy` | 前端只写 `/api/xxx`，浏览器认为同源，无跨域 |
| 后端 CORS | `backend/.../config/CorsConfig.java` + `application-dev.yml` 的 `app.cors.allowed-origins` | 前端直连 `http://localhost:8080` 时需要 |

---

## 六、常见问题

| 现象 | 原因与解决 |
| --- | --- |
| 启动报 `UnsupportedClassVersionError` / 编译报版本错误 | 用了 JDK 8/11，必须换成 **JDK 17**（`java -version` 确认） |
| 启动报 `Access denied for user 'root'@'localhost'` | `application-dev.yml` 中的密码不对 |
| 启动报 `Public Key Retrieval is not allowed` | JDBC URL 已带 `allowPublicKeyRetrieval=true`，若自行修改请保留该参数 |
| 首页显示「后端未连通」 | 后端未启动或端口不是 8080；检查 `http://localhost:8080/api/health` |
| 首页显示「数据库未连通」 | MySQL 未启动、库未创建（未执行 `db/schema/01-schema.sql`）或账号密码错误 |
| `Port 8080 was already in use` | 改 `application.yml` 的 `server.port`，同时同步修改 `vite.config.js` 的代理 target |
| 前端 `pnpm dev` 报 Node 版本错误 | Node 需 18+，推荐 20 LTS |
| pnpm 提示 `Ignored build scripts: esbuild, vue-demi` | 项目已提供 `frontend/pnpm-workspace.yaml` 允许这两个包执行构建脚本，重新执行 `pnpm install` 即可消除 |
| 命令行启动时中文日志乱码 | 中文 Windows 控制台默认 GBK 编码，先执行 `chcp 65001`，或加参数 `-Dfile.encoding=UTF-8`（IDEA 控制台无此问题） |
| 接口返回 404 JSON「接口不存在」 | 路径写错（后端接口统一以 `/api` 开头） |

---

## 七、v0.03 验收记录（实测）

| 验收项 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| 后端编译打包 | `mvnw clean package -DskipTests` | BUILD SUCCESS，产物 `campus-trade-backend-0.0.3.jar`（28MB） |
| 后端启动 | `java -jar campus-trade-backend-0.0.3.jar` | 2.7 秒启动，Tomcat 监听 8080，profile=dev |
| 后端存活接口 | `GET /api/health` | `code=200`，返回应用名、v0.03、JDK 17.0.2、服务端时间 |
| 数据库连通 | `GET /api/health/db` | `code=200`，真实 count：用户 1、分类 32、商品 0 |
| 分层调用（真实表查询） | `GET /api/categories?parentId=0` | `code=200`，返回 8 个一级分类（Controller→Service→Mapper→MySQL） |
| MyBatis-Plus 分页 | `GET /api/products?page=1&size=5` | `code=200`，返回 `{total:0,pages:0,current:1,size:5,records:[]}` |
| 全局异常：不存在的数据 | `GET /api/products/999` | `code=404`，`商品不存在：id=999` |
| 全局异常：接口不存在 | `GET /api/not-exist` | `code=404`，`接口不存在：/api/not-exist` |
| 全局异常：参数类型错误 | `GET /api/categories?parentId=abc` | `code=400`，`参数类型错误：parentId` |
| 跨域预检 | `OPTIONS /api/health`（Origin: http://localhost:5173） | 200，返回 `Access-Control-Allow-Origin` 等完整响应头 |
| 前端依赖安装 | `pnpm install` | 83 个包，无错误 |
| 前端生产构建 | `pnpm build` | 1677 个模块转换成功，产出 `dist/` |
| 前端开发服务器 | `pnpm dev` | Vite 5.4.21 启动于 5173 |
| 前端页面与模块编译 | `GET /`、`/login`、全部 `.vue`/`.js` 模块 | 全部 HTTP 200，无编译错误 |
| **前后端连通（端到端）** | `curl http://localhost:5173/api/health/db` | 经 Vite 代理返回后端 JSON：`{userCount:1, categoryCount:32, productCount:0}` |

> 结论：v0.03 里程碑达成 —— 前端（Vue3+Vite）、后端（Spring Boot 3 + MyBatis-Plus）、数据库（MySQL 8）
> 三层已打通，脚手架可直接作为 v0.04 用户模块开发的起点。

---

## 八、版本记录

| 版本 | 里程碑 | 内容 |
| --- | --- | --- |
| v0.01 | 需求分析 | `spec.md`：角色、42 项功能清单、业务流程、目录规划 |
| v0.02 | 数据库设计 | `db/schema/01-schema.sql`：7 张表 + 初始化数据，已在 MySQL 8.4.4 实测通过 |
| **v0.03** | **前后端分离脚手架** | 本文件：Spring Boot 分层骨架 + Vue3/Vite 骨架 + 连通性验证 |
| **v0.04** | **用户注册登录模块（JWT 身份认证）** | 注册、登录、BCrypt 加密、JWT 工具类、`@LoginRequired` 拦截器、登录/注册页、路由守卫（详见 [v0.04-用户模块](docs/milestones/v0.04-用户模块.md)） |
| **v0.05** | **商品核心模块（含商品占位图方案）** | 发布商品、图片上传、商品列表/详情、上下架、分类列表、9 张 800×800 占位图（详见 [v0.05-商品核心模块](docs/milestones/v0.05-商品核心模块.md)） |
| **v0.06** | **商品收藏模块** | 收藏/取消收藏、我的收藏分页、是否已收藏查询、详情页收藏按钮、我的收藏页（详见 [v0.06-商品收藏模块](docs/milestones/v0.06-商品收藏模块.md)） |
| **v0.07** | **私信聊天模块** | 发送私信、会话列表、聊天记录分页、已读更新、会话列表页、聊天窗口页、详情页「私聊卖家」（详见 [v0.07-私信聊天模块](docs/milestones/v0.07-私信聊天模块.md)） |
| **v0.08** | **订单交易模块** | 下单、订单三态流转、买家/卖家订单列表、订单详情与权限校验、下单弹窗、订单页面（详见 [v0.08-订单交易模块](docs/milestones/v0.08-订单交易模块.md)） |
| **v0.09** | **辅助功能模块** | 个人资料修改与头像上传、商品搜索（名称模糊+分类筛选+分页）、分类新增/修改、顶部搜索框、资料编辑页、搜索结果页（详见 [v0.09-辅助功能模块](docs/milestones/v0.09-辅助功能模块.md)） |
| **v0.10** | **系统测试与 bug 修复** | 127 条测试用例、完整闭环冒烟、越权专项检查、修复 3 个缺陷、接口文档（详见 [v0.10-系统测试与bug修复](docs/milestones/v0.10-系统测试与bug修复.md)） |
| **v0.11** | **个性化推荐模块** | 用户行为埋点表、Item-CF + 内容召回 + 热门三路融合、猜你喜欢/相似商品接口、离线评测实验（6 种策略对比）、首页与详情页推荐位（详见 [v0.11-个性化推荐模块](docs/milestones/v0.11-个性化推荐模块.md)） |
| **v0.12** | **Redis 缓存与限流** | 商品列表/详情/分类/推荐缓存、穿透·雪崩·击穿防护、浏览量 Redis 去重与批量回写、行为热门榜（ZSet）、注解式限流（Lua）、JWT 登出黑名单、**压测：QPS 提升约 10 倍、数据库负载下降 99.9%**（详见 [v0.12-缓存与限流](docs/milestones/v0.12-缓存与限流.md)） |
| **v0.13** | **管理后台与数据看板** | 后台布局与路由守卫、ECharts 数据看板（概览/7 天趋势/分类与状态分布）、商品审核（通过·驳回）、强制下架、用户启用禁用、举报提交与处理、管理员操作日志（详见 [v0.13-管理后台与数据看板](docs/milestones/v0.13-管理后台与数据看板.md)） |
| **v0.14** | **WebSocket 实时私信** | 替换 5 秒轮询：握手鉴权、心跳保活、指数退避重连、已读回执、在线状态（Redis ZSet）、多端在线、离线消息不丢、WS 通道限流、REST 兜底（详见 [v0.14-实时私信](docs/milestones/v0.14-实时私信.md)） |
| **v0.15.1** | **修复：版本号显示与打包瘦包** | 前端版本号收敛到 `src/utils/version.js` 单一来源（原先 5 处硬编码 v0.04）；`start.ps1` 增加 jar 完整性检查，拦截"后端运行中打包"产生的瘦包 |
| **v0.15** | **部署与交付** | 生产配置分离（环境变量注入/日志滚动/限流收紧/健康探针关闭）、Nginx 反向代理（SPA 回退 + `/api` 反代 + **`/ws` 协议升级**）、Docker Compose 一键编排、Windows 原生一键启停脚本、部署验证用例 **28/28**、E2E **31/31 经 Nginx 通过**（详见 [v0.15-部署与交付](docs/milestones/v0.15-部署与交付.md)） |

---

## 九、本机已安装的开发环境（2026-10-02 安装记录）

| 组件 | 版本 | 安装路径 | 说明 |
| --- | --- | --- | --- |
| OpenJDK | 17.0.2 | `D:\major\tool\openjdk-17.0.2_windows-x64_bin\jdk-17.0.2` | 系统变量 `JAVA_HOME` 与 `PATH` 原本就指向该目录，恢复后 `java -version` 直接可用，无需再配置 |
| MySQL | 8.4.4 | `D:\major\tool\mysql-8.4.4-winx64` | 已注册为 Windows 服务 `MySQL`，启动类型「自动」，端口 3306 |
| Maven | 3.9.9 | `C:\Users\<用户名>\.m2\wrapper\dists\apache-maven-3.9.9-bin\...` | 由项目自带 `mvnw.cmd` 首次运行时自动下载，无需单独安装 |

**数据库信息**

- 连接：`127.0.0.1:3306`，账号 `root`，密码 `123456`（与 `application-dev.yml` 默认值一致，无需改配置）
- 配置文件：`D:\major\tool\mysql-8.4.4-winx64\my.ini`（utf8mb4、时区 +08:00、错误日志 `data\mysql-error.log`）
- 数据目录：`D:\major\tool\mysql-8.4.4-winx64\data`
- 已导入数据库 `campus_trade`：7 张表、32 条分类、管理员账号 `admin / 123456`

**常用命令**

```bat
:: 查看 / 启动 / 停止 MySQL 服务（启动、停止服务需要管理员权限）
sc query MySQL
net start MySQL
net stop MySQL

:: 以管理员身份启动服务（会弹一次 UAC 确认框）
D:\major\tool\start-mysql-service.bat

:: 连接数据库
mysql -h 127.0.0.1 -P 3306 -u root -p123456
```

> 提示：`mysql` / `mysqld` 命令可在任意命令行直接使用 —— 系统 PATH 里的 `D:\major\tool\mysql84\bin`
> 已通过目录联接（Junction）指向 `D:\major\tool\mysql-8.4.4-winx64`，因此无需修改 PATH。

---

---

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [spec.md](spec.md) | 需求与设计规格说明书（角色、功能清单、业务流程） |
| [docs/api.md](docs/api.md) | 接口文档：53 个 REST 接口 + WebSocket 协议 |
| [docs/数据库说明.md](docs/数据库说明.md) | 10 张表、逐字段说明、表关系（自动生成，与真实库一致） |
| [docs/推荐算法说明.md](docs/推荐算法说明.md) | Item-CF + 内容 + 热门三路融合设计与离线实验数据 |
| [docs/缓存与限流说明.md](docs/缓存与限流说明.md) | 缓存三防策略、浏览量优化、压测对比（QPS 10 倍提升） |
| [docs/管理后台说明.md](docs/管理后台说明.md) | 后台功能、权限双层校验、审核状态机、举报流程 |
| [docs/实时通信说明.md](docs/实时通信说明.md) | WebSocket 握手鉴权、消息协议、可靠性设计、在线状态 |
| [docs/部署说明.md](docs/部署说明.md) | 部署手册：Docker Compose 与 Windows 原生两条路径、运维手册 |
| [docs/数据库查看指南.md](docs/数据库查看指南.md) | 怎么看库结构、常用查询语句 |
| [docs/test-cases.md](docs/test-cases.md) | 测试用例表（含预期/实际/结论） |
| [docs/test-report-v0.10.md](docs/test-report-v0.10.md) | v0.10 系统测试报告 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |

## 版本里程碑详解

各版本的实现细节、关键代码与验收记录已拆分到独立文件：

| 版本 | 里程碑 | 详解 |
| --- | --- | --- |
| v0.03 | 前后端分离脚手架 | 见上文「七、v0.03 验收记录」 |
| v0.04 | 用户注册登录（JWT） | [docs/milestones/v0.04-用户模块.md](docs/milestones/v0.04-用户模块.md) |
| v0.05 | 商品核心模块 | [docs/milestones/v0.05-商品核心模块.md](docs/milestones/v0.05-商品核心模块.md) |
| v0.06 | 商品收藏 | [docs/milestones/v0.06-商品收藏模块.md](docs/milestones/v0.06-商品收藏模块.md) |
| v0.07 | 私信聊天 | [docs/milestones/v0.07-私信聊天模块.md](docs/milestones/v0.07-私信聊天模块.md) |
| v0.08 | 订单交易 | [docs/milestones/v0.08-订单交易模块.md](docs/milestones/v0.08-订单交易模块.md) |
| v0.09 | 辅助功能 | [docs/milestones/v0.09-辅助功能模块.md](docs/milestones/v0.09-辅助功能模块.md) |
| v0.10 | 系统测试与 bug 修复 | [docs/milestones/v0.10-系统测试与bug修复.md](docs/milestones/v0.10-系统测试与bug修复.md) |
| v0.11 | 个性化推荐 | [docs/milestones/v0.11-个性化推荐模块.md](docs/milestones/v0.11-个性化推荐模块.md) |
| v0.12 | Redis 缓存与限流 | [docs/milestones/v0.12-缓存与限流.md](docs/milestones/v0.12-缓存与限流.md) |
| v0.13 | 管理后台与数据看板 | [docs/milestones/v0.13-管理后台与数据看板.md](docs/milestones/v0.13-管理后台与数据看板.md) |
| v0.14 | WebSocket 实时私信 | [docs/milestones/v0.14-实时私信.md](docs/milestones/v0.14-实时私信.md) |
| v0.15 | 部署与交付 | [docs/milestones/v0.15-部署与交付.md](docs/milestones/v0.15-部署与交付.md) |
