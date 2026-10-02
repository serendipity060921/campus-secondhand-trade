# 校园二手交易平台（Campus Secondhand Trade）

> 毕业设计项目 · **当前版本 v0.03** · 里程碑：**搭建前后端分离项目脚手架**
> 技术栈：Spring Boot 3.2.5 + MyBatis-Plus 3.5.7 + MySQL 8 + Vue 3 + Vite 5 + Element Plus + Pinia

本里程碑**不包含业务逻辑**，只完成工程脚手架搭建，并保证「Vue 前端 → Spring Boot 后端 → MyBatis-Plus → MySQL」
整条链路可以连通访问。业务功能（注册登录、商品发布、留言私信、订单、后台审核等）从 v0.04 开始按
[spec.md](./spec.md) 的 F01~F42 逐步实现。

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
├── backend/                                  # 后端：Spring Boot 3
│   ├── mvnw / mvnw.cmd                       # Maven Wrapper（无需安装 Maven）
│   ├── .mvn/wrapper/                         # Wrapper 配置与 jar
│   ├── pom.xml                               # 依赖与构建配置
│   └── src/main/
│       ├── java/com/campus/trade/
│       │   ├── CampusTradeApplication.java   # 启动类（@MapperScan）
│       │   ├── common/
│       │   │   ├── result/                   # Result / ResultCode / PageResult 统一响应
│       │   │   └── exception/                # BusinessException + GlobalExceptionHandler
│       │   ├── config/                       # 配置层
│       │   │   ├── CorsConfig.java           # 跨域配置
│       │   │   ├── CorsProperties.java       # 跨域允许来源（配置文件读取）
│       │   │   ├── MybatisPlusConfig.java    # 分页插件 + 防全表更新插件
│       │   │   └── MyMetaObjectHandler.java  # create_time / update_time 自动填充
│       │   ├── entity/                       # 实体层（7 张表一一对应）
│       │   ├── mapper/                       # 持久层（BaseMapper）
│       │   ├── service/ + service/impl/      # 业务层（IService / ServiceImpl）
│       │   ├── controller/                   # 接口层（Health / Category / Product）
│       │   └── vo/                           # 出参对象（HealthVO）
│       └── resources/
│           ├── application.yml               # 主配置（端口、MyBatis-Plus、日志）
│           ├── application-dev.yml           # 开发环境（MySQL 连接、跨域来源）
│           ├── application-prod.yml          # 生产环境模板
│           └── mapper/ProductMapper.xml      # 复杂 SQL 存放位置（示例）
│
├── frontend/                                 # 前端：Vue 3 + Vite
│   ├── package.json
│   ├── vite.config.js                        # 别名 @ + /api 代理到 8080
│   ├── index.html
│   ├── .env.development / .env.production    # VITE_API_BASE_URL 等
│   ├── pnpm-workspace.yaml                   # pnpm 构建脚本白名单（esbuild / vue-demi）
│   └── src/
│       ├── main.js                           # 入口（Pinia / Router / Element Plus）
│       ├── App.vue
│       ├── api/
│       │   ├── request.js                    # axios 实例 + 请求/响应拦截器
│       │   └── common.js                     # 公共接口（health / categories / products）
│       ├── router/index.js                   # 路由表 + 登录守卫
│       ├── layout/FrontLayout.vue            # 前台基础布局（导航 + 内容 + 页脚）
│       ├── store/user.js                     # Pinia 用户状态
│       ├── utils/auth.js                     # Token 本地存储
│       ├── assets/styles/global.css          # 全局样式
│       └── views/
│           ├── Login.vue                     # 登录页（含连通性自检）
│           ├── Home.vue                      # 首页（连通性看板）
│           ├── Placeholder.vue               # 未实现页面占位
│           └── NotFound.vue                  # 404
│
├── db_schema.sql                             # 数据库建表脚本（7 张表 + 初始化数据）
├── db_smoke_test.sql                         # 数据库结构与业务冒烟测试脚本
├── spec.md                                   # 需求与设计规格说明书
└── README.md                                 # 本文件：启动说明
```

---

## 三、启动步骤

### 第 0 步：初始化数据库（只需执行一次）

```bash
# 在项目根目录执行，会创建 campus_trade 库、7 张表和初始化数据
mysql -u root -p < db_schema.sql

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
| 首页显示「数据库未连通」 | MySQL 未启动、库未创建（未执行 `db_schema.sql`）或账号密码错误 |
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
| v0.02 | 数据库设计 | `db_schema.sql`：7 张表 + 初始化数据，已在 MySQL 8.4.4 实测通过 |
| **v0.03** | **前后端分离脚手架** | 本文件：Spring Boot 分层骨架 + Vue3/Vite 骨架 + 连通性验证 |
| v0.04（计划） | 用户模块 | 注册、登录、JWT 鉴权、统一权限拦截 |

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
