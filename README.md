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
| **v0.04** | **用户注册登录模块（JWT 身份认证）** | 注册、登录、BCrypt 加密、JWT 工具类、`@LoginRequired` 拦截器、登录/注册页、路由守卫（详见第十节） |
| **v0.05** | **商品核心模块（含商品占位图方案）** | 发布商品、图片上传、商品列表/详情、上下架、分类列表、9 张 800×800 占位图（详见第十一节） |
| **v0.06** | **商品收藏模块** | 收藏/取消收藏、我的收藏分页、是否已收藏查询、详情页收藏按钮、我的收藏页（详见第十二节） |
| **v0.07** | **私信聊天模块** | 发送私信、会话列表、聊天记录分页、已读更新、会话列表页、聊天窗口页、详情页「私聊卖家」（详见第十三节） |
| **v0.08** | **订单交易模块** | 下单、订单三态流转、买家/卖家订单列表、订单详情与权限校验、下单弹窗、订单页面（详见第十四节） |
| **v0.09** | **辅助功能模块** | 个人资料修改与头像上传、商品搜索（名称模糊+分类筛选+分页）、分类新增/修改、顶部搜索框、资料编辑页、搜索结果页（详见第十五节） |
| **v0.10** | **系统测试与 bug 修复** | 127 条测试用例、完整闭环冒烟、越权专项检查、修复 3 个缺陷、接口文档（详见第十六节） |
| **v0.11** | **个性化推荐模块** | 用户行为埋点表、Item-CF + 内容召回 + 热门三路融合、猜你喜欢/相似商品接口、离线评测实验（6 种策略对比）、首页与详情页推荐位（详见第十八节） |
| v0.12（计划） | Redis 缓存与限流 | 首页/分类树缓存、浏览量防刷、热门榜、注解限流、压测对比 |
| v0.13（计划） | 管理后台与数据看板 | 商品审核、用户管理、举报处理、ECharts 统计 |
| v0.14（计划） | WebSocket 实时私信 | 替换 5 秒轮询、在线状态、心跳与断线重连 |

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

## 十、v0.04 用户模块：注册 / 登录 / JWT 身份认证

### 10.1 接口清单

| 方法 | 路径 | 说明 | 是否需要 Token |
| --- | --- | --- | :---: |
| POST | `/api/user/register` | 注册（username / password / nickname） | 否 |
| POST | `/api/user/login` | 登录，返回 `token` + `userInfo` | 否 |
| GET | `/api/user/info` | 当前登录用户信息（用户ID 取自 Token，不可越权） | 是 |
| POST | `/api/user/logout` | 退出登录 | 是 |
| POST | `/api/auth/login` | 兼容脚手架预留路径，等价于 `/api/user/login` | 否 |
| POST | `/api/auth/logout` | 兼容脚手架预留路径 | 否 |

**注册**

```bash
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"stu_test01\",\"password\":\"abc12345\",\"nickname\":\"测试同学01\"}"
```

```json
{ "code": 200, "message": "注册成功", "data": { "id": 2, "username": "stu_test01", "nickname": "测试同学01", "role": 0, "creditScore": 100 } }
```

**登录**

```bash
curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"stu_test01\",\"password\":\"abc12345\"}"
```

```json
{
  "code": 200, "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "userInfo": { "id": 2, "username": "stu_test01", "nickname": "测试同学01", "role": 0 }
  }
}
```

**携带 Token 访问受保护接口**

```bash
curl http://localhost:8080/api/user/info -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

### 10.2 错误码约定

| code | 含义 | 触发场景 |
| --- | --- | --- |
| 200 | 成功 | - |
| 400 | 参数校验失败 | 用户名/密码/昵称为空、长度或格式不符 |
| **2001** | 用户名已被注册 | 注册时用户名重复 |
| **2002** | 账号不存在 | 登录时用户名不存在 |
| **2003** | 密码错误 | 登录时密码不匹配 |
| 2004 | 账号已被禁用 | `user.status = 0` |
| **401** | 未登录 / Token 无效 / Token 过期 | 未带 Token、Token 被篡改、Token 超过 2 小时 |
| 403 | 没有操作权限 | 非管理员访问 `@LoginRequired(admin = true)` 接口 |

> 说明：业务错误统一返回 HTTP 200 + 业务 code，前端 `request.js` 响应拦截器识别 `code === 401`
> 后会自动清除本地 Token 并跳转登录页（脚手架原有逻辑，未做修改）。

### 10.3 数据库测试

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_user_test.sql
```

脚本会依次检查：user 表结构、唯一索引、密码是否为 BCrypt 密文、是否存在明文密码、
注册落库形态、唯一索引冲突（预期 1062 报错）、登录时间更新，最后清理测试数据。

关键验证点：`password` 字段必须是 `$2a$10$` 开头的 60 位密文，明文条数必须为 **0**。

### 10.4 后端接口测试步骤（curl 全流程）

```bash
BASE=http://localhost:8080/api

# ① 注册成功
curl -X POST $BASE/user/register -H "Content-Type: application/json" \
  -d '{"username":"stu_test01","password":"abc12345","nickname":"测试同学01"}'

# ② 重复注册 → code 2001
curl -X POST $BASE/user/register -H "Content-Type: application/json" \
  -d '{"username":"stu_test01","password":"abc12345","nickname":"重复"}'

# ③ 参数校验 → code 400（含具体字段提示）
curl -X POST $BASE/user/register -H "Content-Type: application/json" \
  -d '{"username":"","password":"12","nickname":""}'

# ④ 登录成功，取出 token
TOKEN=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" \
  -d '{"username":"stu_test01","password":"abc12345"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

# ⑤ 密码错误 → 2003 ；⑥ 账号不存在 → 2002
curl -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"username":"stu_test01","password":"wrong123"}'
curl -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"username":"nobody","password":"abc12345"}'

# ⑦ 不带 Token → 401「未登录，请先登录」
curl $BASE/user/info
# ⑧ 伪造 Token → 401「Token 无效，请重新登录」
curl $BASE/user/info -H "Authorization: Bearer abc.def.ghi"
# ⑨ 携带有效 Token → 200 + 用户信息
curl $BASE/user/info -H "Authorization: Bearer $TOKEN"
# ⑩ 退出登录
curl -X POST $BASE/user/logout -H "Authorization: Bearer $TOKEN"
```

Token 过期分支的测试：把 `JwtProperties.expireSeconds` 改成 `10`（或在 `application-dev.yml`
中配置 `app.jwt.expire-seconds: 10`），登录后等 10 秒再访问 `/api/user/info`，
即可看到 401「登录已过期，请重新登录」。

### 10.5 前端页面测试步骤

1. 启动后端（8080）与前端（`pnpm dev`，5173），打开 <http://localhost:5173/register>；
2. 故意输入不符合规则的密码（如 `123456`）→ 表单提示「密码必须同时包含字母和数字」（前端校验）；
3. 输入合法信息注册 → 提示注册成功并跳回登录页，用户名自动填充；
4. 用错误密码登录 → 顶部弹出「密码错误，请重新输入」（后端 2003，由响应拦截器统一提示）；
5. 用正确密码登录 → 跳转首页，右上角显示昵称；刷新页面登录态仍在（Token 存 localStorage）；
6. 点击右上角「个人中心」→ 页面调用 `/api/user/info` 显示用户信息（说明 Token 校验通过）；
7. 打开浏览器控制台执行 `localStorage.removeItem('campus_trade_token')`，再点「刷新」按钮
   → 请求被后端拦截返回 401 → 自动跳转登录页（验证响应拦截器 401 逻辑）；
8. 执行 `localStorage.setItem('campus_trade_token','abc.def.ghi')` 后访问 <http://localhost:5173/profile>
   → 提示「Token 无效，请重新登录」并跳转登录页；
9. 未登录状态直接访问 <http://localhost:5173/profile> → 路由守卫直接跳转登录页（不发请求）。

### 10.6 安全设计说明

| 项目 | 做法 |
| --- | --- |
| 密码存储 | BCrypt 单向哈希 + 自动加盐，数据库只存 `$2a$10$...` 密文，不可逆推明文 |
| 密码比对 | 使用 `PasswordEncoder.matches()`，绝不用字符串相等比较 |
| 密码外泄防护 | 出参统一用 `UserVO`（不含 password 字段），实体类永不出现在 Controller 返回值中 |
| Token | JWT HS256 签名，载荷含 userId / username / role / iat / exp，默认 2 小时有效 |
| 密钥 | `app.jwt.secret` 可通过配置文件或环境变量 `APP_JWT_SECRET` 覆盖，默认值仅用于开发 |
| 越权防护 | `/api/user/info` 的用户ID 取自 Token 解析结果，不接受前端传参 |
| 退出登录 | JWT 无状态，前端清除本地 Token；如需服务端强制失效，可后续接入 Redis 黑名单 |

---

## 十一、v0.05 商品核心模块（含商品占位图）

### 11.1 接口清单

| 方法 | 路径 | 说明 | 是否需要 Token |
| --- | --- | --- | :---: |
| POST | `/api/product/publish` | 发布商品，卖家 = 当前登录用户 | 是 |
| POST | `/api/product/upload` | 图片上传（单张 `file` / 多张 `files`），可带 `productId` 直接写入 `product_image` | 是 |
| GET | `/api/product/list` | 商品分页列表，**只返回上架商品**，支持分类/关键词/价格区间/排序 | 否 |
| GET | `/api/product/{id}` | 商品详情（浏览量 +1，含图片列表与卖家信息） | 否 |
| GET | `/api/product/mine` | 我的商品（含已下架） | 是 |
| PUT | `/api/product/status` | 上架 / 下架，**只能操作自己发布的商品** | 是 |
| GET | `/api/category/list` | 分类列表（发布页与首页筛选用） | 否 |

**发布商品示例**

```bash
TOKEN=<登录后拿到的 token>
curl -X POST http://localhost:8080/api/product/publish \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"iPad Air 5 64G","description":"自用一年，无磕碰","categoryId":15,
       "price":2680.00,"originalPrice":4399.00,"conditionLevel":3,
       "campus":"东校区","tradePlace":"一食堂门口",
       "imageUrls":["/upload/2026/10/xxx.png"]}'
```

**图片上传示例**

```bash
# 单张
curl -X POST http://localhost:8080/api/product/upload -H "Authorization: Bearer $TOKEN" -F "file=@a.png"
# 多张
curl -X POST http://localhost:8080/api/product/upload -H "Authorization: Bearer $TOKEN" -F "files=@a.png" -F "files=@b.png"
# 带商品ID：上传后直接把图片写入 product_image 表（会校验商品归属）
curl -X POST http://localhost:8080/api/product/upload -H "Authorization: Bearer $TOKEN" -F "file=@a.png" -F "productId=1"
```

返回：`{"code":200,"data":[{"originalName":"a.png","url":"/upload/2026/10/a1b2c3.png","size":5121}]}`

### 11.2 商品模块错误码

| code | 含义 | 触发场景 |
| --- | --- | --- |
| 400 | 参数不合法 | 标题/价格/分类/成色校验失败；`status` 不是 1 或 3 |
| 3001 | 商品不存在或已被删除 | 详情/上下架/传图时商品 ID 不存在 |
| 3002 | 无权操作他人发布的商品 | 上下架或补传图片时不是商品所有者 |
| 3003 | 商品当前状态不允许该操作 | 交易中(4)/已售出(5) 的商品再上下架 |
| 3004 | 商品分类不存在 | 发布时传了不存在的分类 ID |
| 3006 | 图片格式不支持 | 上传 .txt 等非图片文件 |
| 3007 | 图片大小超出限制 | 单张超过 5MB |
| 3009 | 图片数量超出限制 | 一次超过 9 张 / 单个商品超过 9 张 |

### 11.3 商品占位图方案

位置：`frontend/public/demo-images/`（Vite 直接以 `/demo-images/xxx.png` 提供访问）

| 文件名 | 类别 | 主色 | 对应数据库分类示例 |
| --- | --- | --- | --- |
| `textbook.png` | 教材课本 | 蓝 #4C8DF6 | 教材书籍 / 公共课教材 / 专业课教材 / 考研资料 |
| `digital.png` | 手机数码 | 靛 #6E7BFF | 数码电子 / 手机 / 平板电脑 / 耳机音响 / 相机摄影 |
| `computer.png` | 电脑配件 | 青 #46B3A9 | 笔记本电脑 / 键盘鼠标 |
| `daily.png` | 生活用品 | 橙 #F2994A | 生活用品 / 日常洗护 |
| `clothes.png` | 衣物鞋子 | 紫 #7B61FF | 服饰鞋包 / 男装 / 女装 / 鞋靴 |
| `sport.png` | 运动器材 | 红 #EB5757 | 运动户外 / 自行车 / 球类器材 / 健身器材 |
| `dorm.png` | 宿舍用品 | 亮蓝 #56CCF2 | 宿舍家具 / 行李收纳 |
| `beauty.png` | 美妆护肤 | 粉 #F178B6 | 美妆护肤 / 护肤 / 彩妆 |
| `default.png` | 通用兜底 | 绿 #27AE60 | 其他闲置 / 乐器文具 / 未匹配分类 |

规格：**800×800 PNG**，纯白背景 + 浅色圆形色块 + 居中扁平主体，单张约 5KB，风格统一。
预览图（9 张拼版）：[docs/demo-images-preview.png](./docs/demo-images-preview.png)

**兜底逻辑**（`frontend/src/utils/product.js`）：

- 商品有 `coverImage` → 用真实图片（`/upload/...`）；
- 没有封面，或图片加载失败 → 按 `categoryName` 查表返回对应占位图；
- 详情页 `product_image` 为空 → 退化为「封面 / 占位图」单图。

> 生成脚本（Pillow 程序化绘制，可重新生成/改色）保留在开发记录中，重新生成只需调整主色与图形函数。

### 11.4 图片上传的存储与访问

| 项 | 说明 |
| --- | --- |
| 保存目录 | `backend/uploads/yyyy/MM/{uuid}.{ext}`（可用 `app.upload.path` 配置；已加入 .gitignore） |
| 文件名 | 服务端用 UUID 重新生成，彻底避免路径穿越与重名 |
| 校验 | 非空 → 扩展名白名单（jpg/jpeg/png/gif/webp/bmp）→ Content-Type 必须 `image/*` → 单张 ≤ 5MB → 单次 ≤ 9 张 |
| 访问 | 后端 `FileUploadConfig` 把上传目录映射为 `/upload/**`；前端开发环境由 Vite 代理 `/upload` 到 8080，生产环境由 Nginx 代理 |
| 数据表 | 图片地址写入 `product_image`（`productId` 在发布时提交，或上传时直接带 `productId`） |

### 11.5 数据库测试

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_product_test.sql
```

脚本包含：表结构与外键检查、分类统计、商品总览（含图片数）、上架列表等价 SQL、
**插入 9 条使用占位图的演示商品**（覆盖全部 8 类图片 + 2 条多图商品）、上下架 SQL 验证、
3 条约束反向验证（分类/卖家/商品不存在 → 预期 1452），以及统计校验与常用排查 SQL。

### 11.6 测试步骤

**后端（curl）**

```bash
BASE=http://localhost:8080/api
TOKEN=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" \
  -d '{"username":"stu_test01","password":"abc12345"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl $BASE/category/list                                    # ① 分类列表
curl -X POST $BASE/product/publish -H "Content-Type: application/json" -d '{...}'   # ② 不带 Token → 401
curl -X POST $BASE/product/upload -H "Authorization: Bearer $TOKEN" -F "file=@a.png"  # ③ 上传图片
curl -X POST $BASE/product/publish -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"title":"...","categoryId":10,"price":15,"conditionLevel":2,"imageUrls":["/upload/..."]}'
curl "$BASE/product/list?page=1&size=12"                    # ④ 列表（只含上架）
curl $BASE/product/6                                        # ⑤ 详情
curl -X PUT $BASE/product/status -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"productId":6,"status":3}'   # ⑥ 下架自己的商品
curl -X PUT $BASE/product/status -H "Authorization: Bearer <别人的token>" \
  -H "Content-Type: application/json" -d '{"productId":6,"status":3}'   # ⑦ 越权 → 3002
curl $BASE/product/mine -H "Authorization: Bearer $TOKEN"    # ⑧ 我的商品
```

**前端（浏览器）**

1. 打开 <http://localhost:5173/>（首页即商品列表）：卡片展示图片、名称、价格、发布时间；搜索「台灯」、切换分类与排序、翻页；
2. 打开任意商品详情：大图可点击放大、缩略图切换、描述/分类/价格/发布者信息齐全；
3. 登录后点导航「发布商品」：填表 + 选图片（本地立即预览，上传成功后有提示），不选图片也能发布并自动显示占位图；
4. 发布成功后自动跳转详情页；点「我的商品」可看到刚发布的商品；
5. 在「我的商品」点「下架」→ 确认 → 商品从首页列表消失；再点「上架」→ 重新出现；
6. 用另一个账号登录，尝试下架他人商品 → 提示「无权操作他人发布的商品」（后端 3002）。

### 11.7 与既有模块的兼容性

| 项 | 说明 |
| --- | --- |
| v0.04 用户模块 | 未改动任何文件；`/api/user/**` 回归测试通过 |
| v0.03 脚手架接口 | `/api/products`、`/api/categories`、`/api/health/**` 全部保留可用（类名不同，路径不同，互不冲突） |
| 连通性自检看板 | 由 `/home` 移到 `/dev/health`（页脚有入口），`views/Home.vue` 文件本身未修改 |
| 首页 | 由看板改为商品列表（这是 v0.05 的明确需求） |

---

## 十二、v0.06 商品收藏模块

### 12.1 接口清单（三个接口都需要登录）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/favorite/operate` | 收藏 / 取消收藏，`{productId, type}`，type：1 收藏（默认）、2 取消 |
| GET | `/api/favorite/list?page=1&size=12` | 我的收藏分页（**只返回上架商品**） |
| GET | `/api/favorite/hasFavorite?productId=8` | 当前用户是否已收藏该商品 |

**收藏 / 取消收藏**

```bash
curl -X POST http://localhost:8080/api/favorite/operate \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"productId":8}'
# {"code":200,"message":"收藏成功","data":{"productId":8,"favorited":true,"favoriteCount":1}}

curl -X POST http://localhost:8080/api/favorite/operate \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"productId":8,"type":2}'
# {"code":200,"message":"已取消收藏","data":{"productId":8,"favorited":false,"favoriteCount":0}}
```

**我的收藏列表**：返回结构与商品列表一致（`PageResult`），每条含 `favoriteId / favoriteTime /
productId / title / price / originalPrice / coverImage / categoryName / conditionLevel / campus /
productStatus / sellerId / sellerNickname / viewCount / productCreateTime`。

### 12.2 收藏模块错误码

| code | 含义 | 触发场景 |
| --- | --- | --- |
| 3001 | 商品不存在或已被删除 | 收藏不存在的商品ID（复用商品模块错误码） |
| 400 | 参数不合法 | `type` 不是 1/2；`page < 1`；`size` 超出 1~100 |
| 4001 | 已收藏该商品，请勿重复收藏 | 重复点击收藏 |
| 4002 | 不能收藏自己发布的商品 | 收藏自己发布的商品 |
| 4003 | 尚未收藏该商品，无法取消 | 对未收藏的商品执行取消 |
| 401 | 未登录 | 三个接口都由 `@LoginRequired` 保护 |

### 12.3 两个关键设计点

**① 取消收藏为什么用物理删除？**

`favorite` 表有唯一索引 `uk_user_product(user_id, product_id)`，而实体又带 `@TableLogic` 逻辑删除。
若取消收藏走逻辑删除（`deleted = 1`），旧行仍在表里，用户再次收藏时会直接撞唯一索引插入失败。
因此新增 `FavoriteModuleMapper.physicalDelete()` 用注解 SQL 做物理删除 ——
**数据库唯一索引作为"禁止重复收藏"的最后一道防线**（后端先查重返回 4001，插库时再被 1062 兜底）。

**② 收藏数怎么保证准确？**

不做 `favorite_count + 1 / - 1` 累加，而是每次收藏/取消后
`SELECT COUNT(*) FROM favorite WHERE product_id = ?` 再回写 `product.favorite_count`，
避免异常或并发场景下计数漂移（`db_favorite_test.sql` 第 5 节可校验一致性）。

### 12.4 数据库测试

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_favorite_test.sql
```

脚本包含：表结构与唯一索引、"我的收藏"等价关联查询（只含在售）、**收藏数一致性校验**、
重复收藏验证（预期 1062）、取消收藏 + 再次收藏（验证物理删除方案）、计数修复语句、
业务规则排查 SQL（自收藏/重复收藏/收藏排行）、统计总览。

### 12.5 测试步骤

**后端（curl）**

```bash
BASE=http://localhost:8080/api
TOKEN=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" \
  -d '{"username":"stu_test01","password":"abc12345"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -X POST $BASE/favorite/operate -H "Content-Type: application/json" -d '{"productId":8}'   # ① 未登录 → 401
curl -X POST $BASE/favorite/operate -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"productId":8}'          # ② 收藏成功
curl -X POST $BASE/favorite/operate -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"productId":8}'          # ③ 重复收藏 → 4001
curl -X POST $BASE/favorite/operate -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"productId":5}'          # ④ 自己的商品 → 4002
curl -X POST $BASE/favorite/operate -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"productId":999999}'     # ⑤ 不存在 → 3001
curl "$BASE/favorite/hasFavorite?productId=8" -H "Authorization: Bearer $TOKEN"        # ⑥ 是否已收藏 → true
curl -X POST $BASE/favorite/operate -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"productId":8,"type":2}' # ⑦ 取消收藏
curl -X POST $BASE/favorite/operate -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"productId":8,"type":2}' # ⑧ 再次取消 → 4003
curl "$BASE/favorite/list?page=1&size=12" -H "Authorization: Bearer $TOKEN"            # ⑨ 我的收藏（只含在售）
```

**前端（浏览器）**

1. 未登录打开商品详情 → 点「☆ 收藏」→ 提示「请先登录后再收藏」并跳转登录页（带 redirect 参数）；
2. 登录后回到详情页 → 按钮显示「☆ 收藏（0）」；点击 → 变成「★ 已收藏（1）」，收藏量同步 +1；
3. 再次点击 → 取消收藏，按钮与收藏量回到初始；重复点击不会产生重复记录；
4. 打开自己发布的商品详情 → 点收藏 → 提示「不能收藏自己发布的商品」（4002）；
5. 顶部头像下拉 → 「我的收藏」（或个人中心 → 快捷入口 → 我的收藏）→ 分页展示收藏的商品卡片，卡片可点击进详情；
6. 在收藏页点「取消收藏」→ 二次确认 → 该商品从列表移除；若是本页最后一条会自动回退一页；
7. 卖家把自己收藏的商品下架 → 刷新收藏页，该商品不再显示（但收藏记录仍在数据库中，重新上架后会再次出现）。

### 12.6 与既有模块的兼容性

| 项 | 说明 |
| --- | --- |
| v0.04 用户模块 | 未改动后端任何文件；仅 `Profile.vue` 增加了一组「快捷入口」按钮（纯 UI） |
| v0.05 商品模块 | 后端未改动任何文件；`ProductModuleServiceImpl` 等逻辑原样保留。仅 `Detail.vue` 按要求新增收藏按钮与收藏量展示 |
| 复用既有能力 | 收藏服务注入既有的 `FavoriteService` / `ProductService`，只用它们的通用 CRUD，不改其业务方法 |
| 鉴权 | 三个接口均由 `@LoginRequired` 保护，沿用 v0.04 的拦截器与前端 Token 拦截、路由守卫 |

---

## 十三、v0.07 私信聊天模块

### 13.1 接口清单（全部需要登录）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/message/send` | 发送私信 `{toUserId, content, productId?}` |
| GET | `/api/message/conversationList` | 会话列表：聊天对象 + 最后一条消息 + 未读数 |
| GET | `/api/message/history?peerId=&page=&size=` | 与某人的聊天记录（分页，**最新在前**） |
| PUT | `/api/message/read` | 标记已读 `{peerId}` 或 `{messageIds:[...]}` |
| GET | `/api/message/peer?peerId=` | 聊天对象公开信息（聊天窗口顶部展示，配套接口） |

**发送消息**

```bash
curl -X POST http://localhost:8080/api/message/send \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"toUserId":1,"content":"你好，罗技键盘还在吗？","productId":8}'
# {"code":200,"message":"发送成功","data":{"id":1,"fromUserId":5,"toUserId":1,
#   "content":"你好，罗技键盘还在吗？","productId":8,"productTitle":"罗技 K380 蓝牙键盘","isRead":0,...}}
```

**会话列表**（每条含 `peerId / peerNickname / peerAvatar / peerCampus / lastMessage /
lastMessageTime / lastFromMe / productId / productTitle / unreadCount`）

**聊天记录**：`PageResult` 结构，**按时间倒序返回（最新在前）**，前端展示时翻转成正序；
向上翻页（page=2、3…）拿更早的消息。

### 13.2 私信模块错误码

| code | 含义 | 触发场景 |
| --- | --- | --- |
| 400 | 参数不合法 | 内容为空/超 1000 字；`read` 未传 peerId 与 messageIds；`page/size` 越界；缺 `peerId` |
| 401 | 未登录 | 五个接口都由 `@LoginRequired` 保护 |
| 3001 | 商品不存在或已被删除 | `send` 带了一个不存在的 `productId`（复用商品模块错误码） |
| 5001 | 不能给自己发送消息 | `toUserId` 等于当前登录用户 |
| 5002 | 接收人不存在 | `toUserId` 查不到用户；`peer` 接口查不到对象 |
| 5003 | 消息不存在或无权操作 | 预留（消息级操作） |

### 13.3 三个关键设计点

**① 私信与商品留言共用 message 表，用 `type` 隔离**

`type = 2` 为私信（本模块），`type = 1` 留给商品留言（后续里程碑）。
本模块所有查询都带 `type = 2`，两套功能互不干扰 —— 这也是 v0.02 建表时把 `type` 放进 message 表的原因。

**② 会话列表用一条 SQL 完成"取每个聊天对象的最后一条 + 未读数"**

```sql
-- 子查询：把"我发的/发给我的"统一成 (peer_id, max_id)
SELECT IF(m.from_user_id = #{userId}, m.to_user_id, m.from_user_id) AS peer_id,
       MAX(m.id) AS max_id
FROM message m
WHERE m.deleted = 0 AND m.type = 2
  AND (m.from_user_id = #{userId} OR m.to_user_id = #{userId})
GROUP BY peer_id
-- 再 JOIN 回 message 取最后一条内容，未读数用相关子查询统计
```
注意：子查询里的 `peer_id` 是别名，MySQL 支持在 `GROUP BY` 中直接引用；
用 `MAX(id)` 取最新消息（自增主键与时间同序）。

**③ 已读更新必须带 `to_user_id = 当前用户`**

```java
new LambdaUpdateWrapper<Message>()
    .set(Message::getIsRead, 1).set(Message::getReadTime, now)
    .eq(Message::getToUserId, userId)   // ← 只能标记"我收到的"消息
    .eq(Message::getIsRead, 0)
```
实测：用 A 账号传 B 收到的消息 ID，更新条数为 **0**（越权无效），从 SQL 层面杜绝篡改他人消息。

> 实时性说明：毕设阶段前端用 **5 秒轮询** 拉取新消息模拟实时（聊天窗口）+
> 15 秒刷新会话列表；后续可平滑升级为 WebSocket（只需替换推送层，接口与表结构不变）。

### 13.4 数据库测试

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_message_test.sql
```

脚本包含：表结构、消息类型分布、私信明细（双方昵称 + 关联商品）、**会话列表等价 SQL**、
**聊天记录分页 SQL**、未读统计、标记已读 SQL、补充演示数据（新增聊天对象 `stu_demo`）、
3 条外键反向验证（预期 1452）、业务规则排查（自聊检测/会话对数/会话消息排行）。

### 13.5 测试步骤

**后端（curl）**

```bash
BASE=http://localhost:8080/api
STU=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" \
  -d '{"username":"stu_test01","password":"abc12345"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
ADM=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -X POST $BASE/message/send -H "Content-Type: application/json" -d '{"toUserId":1,"content":"hi"}'          # ① 未登录 → 401
curl -X POST $BASE/message/send -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"toUserId":5,"content":"hi"}'   # ② 给自己发 → 5001
curl -X POST $BASE/message/send -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"toUserId":999999,"content":"hi"}' # ③ 接收人不存在 → 5002
curl -X POST $BASE/message/send -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"toUserId":1,"content":""}'        # ④ 内容为空 → 400
curl -X POST $BASE/message/send -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"toUserId":1,"content":"hi","productId":999999}' # ⑤ 商品不存在 → 3001
curl -X POST $BASE/message/send -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"toUserId":1,"content":"键盘还在吗","productId":8}' # ⑥ 发送成功
curl -X POST $BASE/message/send -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" -d '{"toUserId":5,"content":"在的"}'  # ⑦ 对方回复
curl "$BASE/message/conversationList" -H "Authorization: Bearer $STU"                      # ⑧ 会话列表（含未读数）
curl "$BASE/message/history?peerId=1&page=1&size=20" -H "Authorization: Bearer $STU"        # ⑨ 聊天记录（最新在前）
curl -X PUT $BASE/message/read -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"peerId":1}'   # ⑩ 标记已读
curl "$BASE/message/peer?peerId=1" -H "Authorization: Bearer $STU"                          # ⑪ 对方信息
```

**前端（浏览器）**

1. 打开商品详情 <http://localhost:5173/product/8> → 点「**私聊卖家**」→ 未登录时先跳登录（带 redirect）；
2. 登录后自动进入聊天窗口，输入框已预填「你好，这件商品还在吗？」→ 点发送，消息以蓝色气泡出现在右侧；
3. 用另一个浏览器（或隐身窗口）登录卖家账号 → 顶部导航「消息」→ 会话列表出现未读红点 → 点进聊天窗口；
4. 卖家回复后，买家窗口 **5 秒内自动出现**新消息（轮询）；聊天记录按时间正序展示、超过 5 分钟显示时间分隔；
5. 会话列表显示"最后一条消息 + 时间 + 未读数"，自己发的最后一条会带「我：」前缀；点「刷新」可手动更新；
6. 聊天窗口点「加载更早的消息」翻看历史（分页），已读后未读数归零；
7. 在自己发布的商品详情页，「私聊卖家」按钮变灰（不能私聊自己）；直接访问 `/chat/<自己的ID>` 会提示"这是你自己"并禁用输入框；
8. 未登录直接访问 <http://localhost:5173/messages> 或 `/chat/1` → 路由守卫跳登录页。

### 13.6 与既有模块的兼容性

| 项 | 说明 |
| --- | --- |
| v0.04 用户模块 | 未改动后端任何文件 |
| v0.05 商品模块 | 后端未改动；仅 `Detail.vue` 把原来禁用的「联系卖家」替换为可用的「私聊卖家」按钮（需求 3） |
| v0.06 收藏模块 | 未改动任何文件 |
| 复用既有能力 | 注入既有的 `MessageService` / `UserService` / `ProductService`，只用通用 CRUD；新增 `MessageModuleMapper` 承载两条联表查询 |
| 前端既有逻辑 | Token 请求拦截、401 跳登录、路由守卫（`requiresAuth`）全部沿用，未改动 |

---

## 十四、v0.08 订单交易模块

### 14.1 接口清单（全部需要登录）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/order/create` | 创建订单 `{productId, deliveryType?, tradePlace?, buyerRemark?}`，买家 = 当前登录用户 |
| PUT | `/api/order/status` | 修改订单状态 `{orderId, status, cancelReason?}`，status：**3 已完成 / 4 已取消** |
| GET | `/api/order/buyList?status=&page=&size=` | 我买到的订单（分页，可按状态筛选） |
| GET | `/api/order/sellList?status=&page=&size=` | 我卖出的订单（分页，可按状态筛选） |
| GET | `/api/order/{id}` | 订单详情（**仅买卖双方可查看**） |

### 14.2 订单状态机与商品状态联动

```
                  ┌──────────── 取消（买卖任一方）───────────┐
                  ▼                                        │
商品：在售(1) ──下单──▶ 交易中(4) ──完成──▶ 已售出(5)        │
订单：       ──下单──▶ 待交易(0) ──取消──▶ 已取消(4) ────────┘ 商品回到在售(1)
```

| 订单状态 | 状态码 | 说明 |
| --- | --- | --- |
| 待交易 | 0 | 下单后的初始状态（本模块把"支付+交付"简化为一次线下见面交易） |
| 已完成 | 3 | 商品标记「已售出」，买卖双方信用分 +1 |
| 已取消 | 4 | 商品回到「在售」，记录取消原因 |

> db_schema.sql 中 orders.status 注释还有 1 待交付 / 2 待收货 / 5 已退款，这些**保留给后续「支付与物流」里程碑**，本模块不会产生。
> 调用 `/api/order/status` 传 0 会明确提示：「status 只能为 3（已完成）或 4（已取消）；待交易(0) 是下单后的初始状态」。

### 14.3 订单模块错误码

| code | 含义 | 触发场景 |
| --- | --- | --- |
| 400 | 参数不合法 | `status` 不是 3/4；`page/size` 越界 |
| 401 | 未登录 | 五个接口都由 `@LoginRequired` 保护 |
| 3001 | 商品不存在或已被删除 | 下单时商品ID无效（复用商品模块错误码） |
| 6001 | 商品已下架或已被预订，无法下单 | 商品不是「在售」状态（含被别人抢先下单） |
| 6002 | 不能购买自己发布的商品 | 买自己发布的商品 |
| 6003 | 订单不存在 | 订单ID无效 |
| 6004 | 无权查看或操作该订单 | 非买卖双方访问详情/改状态 |
| 6005 | 订单当前状态不允许该操作 | 已完成/已取消的订单再次流转 |

### 14.4 防「一物多卖」的实现

下单时不用「先查再改」，而是一条**条件更新**：

```java
int locked = productService.getBaseMapper().update(null, new LambdaUpdateWrapper<Product>()
        .set(Product::getStatus, PRODUCT_TRADING)   // 改成"交易中"
        .eq(Product::getId, product.getId())
        .eq(Product::getStatus, PRODUCT_ON_SALE));  // ← 只有仍是"在售"才更新成功
if (locked == 0) {
    throw OrderException.productNotOnSale();        // 6001 已被别人抢先下单
}
```
两个买家同时点"立即购买"，数据库层面只会有一个 UPDATE 成功（`affected rows = 1`），另一个拿到 6001。
测试 SQL 第 9.2 节的「一物多卖异常组数」会校验这一点（恒为 0）。

订单同时保存了 **商品标题/封面/价格快照**，商品之后被改名、下架、删除都不影响历史订单。

### 14.5 数据库测试

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_order_test.sql
```

脚本包含：orders 表结构、订单总览（含商品与买卖双方）、买家/卖家列表等价 SQL、订单详情 SQL、
**订单状态与商品状态联动校验**、三态流转 SQL 演示（下单→取消→再下单，并留下一条待交易订单供前端演示）、
3 条约束反向验证（商品/买家不存在 → 1452，订单号重复 → 1062）、业务规则排查
（自买自卖、一物多卖、状态悬挂）、统计总览与信用分。

### 14.6 测试步骤

**后端（curl / Python）**

```bash
BASE=http://localhost:8080/api
STU=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"username":"stu_test01","password":"abc12345"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
ADM=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -X POST $BASE/order/create -H "Content-Type: application/json" -d '{"productId":8}'                       # ① 未登录 → 401
curl -X POST $BASE/order/create -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"productId":999999}'  # ② 商品不存在 → 3001
curl -X POST $BASE/order/create -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"productId":5}'        # ③ 买自己的商品 → 6002
curl -X PUT  $BASE/product/status -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" -d '{"productId":14,"status":3}'   # ④ 先下架
curl -X POST $BASE/order/create -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"productId":14}'       # ⑤ 已下架 → 6001
curl -X POST $BASE/order/create -H "Authorization: Bearer $STU" -H "Content-Type: application/json" \
  -d '{"productId":8,"tradePlace":"东校区图书馆门口","buyerRemark":"明天下午三点"}'                                              # ⑥ 下单成功
curl -X POST $BASE/order/create -H "Authorization: Bearer $DEMO" -H "Content-Type: application/json" -d '{"productId":8}'      # ⑦ 别人再买同款 → 6001
curl "$BASE/order/buyList?page=1&size=10"  -H "Authorization: Bearer $STU"    # ⑧ 我买到的
curl "$BASE/order/sellList?page=1&size=10" -H "Authorization: Bearer $ADM"    # ⑨ 我卖出的
curl "$BASE/order/1" -H "Authorization: Bearer $STU"                          # ⑩ 买家看详情 → 200
curl "$BASE/order/1" -H "Authorization: Bearer $DEMO"                         # ⑪ 第三人看详情 → 6004
curl -X PUT $BASE/order/status -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" -d '{"orderId":1,"status":3}'        # ⑫ 卖家确认完成
curl -X PUT $BASE/order/status -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"orderId":1,"status":4}'        # ⑬ 已完成再操作 → 6005
curl -X PUT $BASE/order/status -H "Authorization: Bearer $STU" -H "Content-Type: application/json" -d '{"orderId":2,"status":4,"cancelReason":"不需要了"}'  # ⑭ 取消订单
```

**前端（浏览器）**

1. 登录后打开别人的商品详情 → 点「**立即购买**」→ 弹出下单确认框（记住交易地点 + 买家备注）→ 确认下单；
2. 下单成功后自动跳到订单详情，状态为「待交易」，顶部显示「我是买家」，右侧有「确认交易完成 / 取消订单」；
3. 该商品同时被别人打开时，会看到「商品已下架或已被预订」（商品状态已变「交易中」）；
4. 顶部导航「订单」→【我买到的】/【我卖出的】两个页签可互相切换，列表可按状态筛选、分页；
5. 用卖家账号（`admin / 123456`）登录 →「订单」→【我卖出的】→ 打开详情 → 点「确认交易完成」→ 状态变「已完成」，
   商品变「已售出」，双方信用分 +1；
6. 再下一单并点「取消订单」→ 填取消原因 → 状态变「已取消」，商品回到「在售」出现在首页；
7. 在自己发布的商品详情页，「立即购买」按钮为禁用状态（不能买自己的商品）；
8. 用第三个账号直接访问 `http://localhost:5173/orders/1` → 显示「无权查看该订单」（后端 6004）；
9. 未登录访问 `/orders/bought` → 路由守卫跳登录页。

### 14.7 与既有模块的兼容性

| 项 | 说明 |
| --- | --- |
| v0.04 用户模块 | 未改动后端任何文件（完成订单时通过 `UserService` 通用方法给信用分 +1） |
| v0.05 商品模块 | 后端未改动；仅商品详情页把原来禁用的「立即购买」改为可用的下单弹窗（需求 1） |
| v0.06 收藏模块 | 未改动任何文件 |
| v0.07 私信模块 | 未改动后端文件；订单详情页新增「私聊对方」按钮，直接跳到 v0.07 的聊天窗口 |
| 前端既有逻辑 | Token 拦截、401 跳登录、路由守卫全部沿用 |

---

## 十五、v0.09 辅助功能模块（个人信息 / 商品搜索 / 分类筛选）

### 15.1 接口清单

| 方法 | 路径 | 说明 | 登录 |
| --- | --- | --- | :---: |
| GET | `/api/user/profile` | 查询自己的完整资料（含手机号/邮箱/真实姓名） | 是 |
| PUT | `/api/user/update` | 修改个人资料（只提交要改的字段） | 是 |
| POST | `/api/user/avatar` | 上传头像（multipart，字段名 `file`），上传即生效 | 是 |
| GET | `/api/product/search` | 商品搜索：名称模糊 + 分类筛选 + 分页 + 排序 | 否 |
| POST | `/api/category/add` | 新增分类 | **管理员** |
| PUT | `/api/category/update` | 修改分类 | **管理员** |

**修改资料 / 上传头像**

```bash
curl -X PUT http://localhost:8080/api/user/update \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"nickname":"测试同学01改","campus":"东校区A栋","gender":1,"school":"示范大学","email":"stu01@campus.edu"}'

curl -X POST http://localhost:8080/api/user/avatar \
  -H "Authorization: Bearer $TOKEN" -F "file=@avatar.png"
# {"code":200,"message":"头像已更新","data":{"avatar":"/upload/2026/10/xxxx.png", ...}}
```

**商品搜索**

```bash
curl "http://localhost:8080/api/product/search?keyword=教材&categoryId=1&sort=new&page=1&size=12"
```

**分类管理（管理员）**

```bash
curl -X POST http://localhost:8080/api/category/add \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"乐器租赁","parentId":0,"sortOrder":20,"icon":"/demo-images/default.png"}'

curl -X PUT http://localhost:8080/api/category/update \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"id":33,"name":"乐器租赁服务","sortOrder":18,"status":1}'
```

### 15.2 错误码

| code | 含义 | 触发场景 |
| --- | --- | --- |
| 400 | 参数不合法 | 昵称长度/手机号格式/邮箱格式错误；关键词超 50 字；`size` 超 100；新增分类未填名称 |
| 401 | 未登录 | 个人资料三个接口 |
| **403** | 没有操作权限 | **普通学生调用分类新增/修改接口**（`@LoginRequired(admin = true)` 生效） |
| 2006 | 该手机号已被其他账号绑定 | 改成别人已用的手机号 |
| 3006 / 3007 | 图片格式不支持 / 大小超限 | 头像上传（复用 v0.05 文件服务） |
| 7001 | 同一上级分类下已存在同名分类 | 分类重名（数据库 `uk_parent_name` 兜底） |
| 7002 | 分类不存在 | 修改不存在的分类 |
| 7003 | 上级分类不存在 | `parentId` 指向不存在的分类 |
| 7004 | 不能把分类的上级设置为自己 | 分类父子关系成环 |

### 15.3 三个实现要点

**① 隐私字段分层：UserVO vs UserProfileVO**

v0.04 的 `UserVO` 只含对外展示字段（昵称/头像/校区/信用分），商品详情、订单、会话都用它。
个人中心需要手机号、邮箱、真实姓名，因此 v0.09 新增 `UserProfileVO extends UserVO`，
由 `/api/user/profile` 返回 —— **没有修改 v0.04 的 UserVO**，避免隐私字段泄漏到商品/订单接口。

**② 资料修改支持"只改提交的字段"**

利用 MyBatis-Plus `updateById` 默认忽略 null 字段的特性，前端只提交改动项即可；
手机号改动时先查重（2006），数据库 `uk_phone` 唯一索引作为最后防线。
用户名与学号是账号凭证，**不在 DTO 中**，从接口层面就无法修改。

**③ 搜索的服务端实现**

```java
wrapper.eq(Product::getStatus, 1);                       // 只搜上架商品
if (hasText(keyword)) wrapper.like(Product::getTitle, keyword.trim());   // 按名称模糊
if (categoryId != null) {                                // 一级分类自动含子分类
    Set<Long> ids = new LinkedHashSet<>();
    ids.add(categoryId);
    categoryService.list(eq(parentId, categoryId)).forEach(c -> ids.add(c.getId()));
    wrapper.in(Product::getCategoryId, ids);
}
// 排序：new（最新）/ priceAsc / priceDesc / hot（浏览量），结果批量补齐分类名与卖家昵称
```

分类管理首次启用了 v0.04 就预留好的 `@LoginRequired(admin = true)`：
拦截器从 Token 解析 `role`，非管理员直接返回 **403「没有操作权限」**。

### 15.4 数据库测试

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_v009_test.sql
```

脚本包含：资料相关字段与唯一索引、当前用户资料、**搜索等价 SQL**（名称模糊 / 分类含子分类 / 分页排序 / 各关键词命中统计，
其中「键盘」在售 0 条、含已售出 1 条，正好验证"只搜上架商品"）、分类树与层级统计、
分类新增/修改等价 SQL 与 3 条约束反向验证（同级重名 1062、上级不存在 1452、手机号重复 1062）、
资料完整度与头像统计。

### 15.5 测试步骤

**后端（curl / Python）**

```bash
BASE=http://localhost:8080/api
TOKEN=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"username":"stu_test01","password":"abc12345"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
ADM=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -X PUT $BASE/user/update -H "Content-Type: application/json" -d '{"nickname":"x"}'                      # ① 未登录 → 401
curl -X PUT $BASE/user/update -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"nickname":"测试同学01改","campus":"东校区A栋","gender":1,"email":"stu01@campus.edu"}'                 # ② 修改成功
curl -X PUT $BASE/user/update -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"phone":"13800000099"}'  # ③ 手机号被占用 → 2006
curl -X PUT $BASE/user/update -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"phone":"123"}'          # ④ 格式错误 → 400
curl -X POST $BASE/user/avatar -H "Authorization: Bearer $TOKEN" -F "file=@a.png"          # ⑤ 头像上传 → 200
curl -X POST $BASE/user/avatar -H "Authorization: Bearer $TOKEN" -F "file=@a.txt"          # ⑥ 非图片 → 3006
curl "$BASE/product/search?keyword=教材"                                                    # ⑦ 名称模糊搜索
curl "$BASE/product/search?categoryId=1&page=1&size=12"                                     # ⑧ 分类筛选（含子分类）
curl "$BASE/product/search?size=999"                                                        # ⑨ 参数非法 → 400
curl -X POST $BASE/category/add -H "Content-Type: application/json" -d '{"name":"测试分类"}'            # ⑩ 未登录 → 401
curl -X POST $BASE/category/add -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"学生分类"}'  # ⑪ 学生 → 403
curl -X POST $BASE/category/add -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" -d '{"name":"乐器租赁","parentId":0}'  # ⑫ 管理员新增 → 200
curl -X POST $BASE/category/add -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" -d '{"name":"乐器租赁","parentId":0}'  # ⑬ 重名 → 7001
curl -X PUT $BASE/category/update -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" -d '{"id":33,"name":"乐器租赁服务"}'   # ⑭ 修改 → 200
```

**前端（浏览器）**

1. 首页顶部导航右侧有**搜索框**，输入「教材」回车 → 跳到 `/search?keyword=教材` 结果页；
2. 结果页可切换分类下拉、排序，翻页；关键词/分类/页码都同步在 URL 上，刷新后条件不丢；
3. 首页商品列表的分类下拉筛选照常可用（也可用 `/home?categoryId=1` 直接带条件进入）；
4. 登录后进入「个人中心」→「编辑资料 / 上传头像」：
   - 点头像 → 选择本地图片 → 立即上传并刷新（顶部导航头像同步变化）；
   - 修改昵称/真实姓名/性别/学校/校区/手机号/邮箱 → 保存 → 提示「资料已保存」并跳回个人中心；
   - 手机号填成别人已用的（如 `13800000099`）→ 提示「该手机号已被其他账号绑定」；
5. 用**学生账号**尝试分类管理接口（可让后端同学用 curl 验证）→ 返回 403「没有操作权限」；
   用**管理员账号**（admin/123456）则能成功新增/修改分类，新分类立刻出现在搜索页与发布页的下拉里。

### 15.6 与既有模块的兼容性

| 项 | 说明 |
| --- | --- |
| v0.04 用户模块 | 后端未改动（新增 UserProfileVO 承载隐私字段，原 UserVO 保持不变）；`Profile.vue` 只加了「编辑资料」入口 |
| v0.05 商品模块 | 后端未改动；`List.vue` 增加"从 URL 读关键词/分类"的小增强，原有筛选逻辑不变 |
| v0.06 / v0.07 / v0.08 | 未改动任何文件 |
| 复用既有能力 | 搜索复用 `ProductService`/`CategoryService`/`UserService`；头像上传复用 v0.05 的 `FileService` |
| 前端既有逻辑 | Token 拦截、401 跳登录、路由守卫全部沿用 |

---

## 十六、v0.10 系统测试与 bug 修复

### 16.1 测试文档（`docs/` 目录）

| 文档 | 内容 |
| --- | --- |
| [docs/test-cases.md](docs/test-cases.md) | **功能测试用例与执行结果**：161 条（主业务闭环 20 + 权限越权 33 + 各模块独立功能 103 + 异常场景交叉视图），含用例编号、场景、输入、预期结果、**实际结果**、测试结论 |
| [docs/test-report-v0.10.md](docs/test-report-v0.10.md) | **测试总结报告**：测试范围与方法、执行结果总览、缺陷清单（现象/原因/修复/验证）、安全性评价、结论与遗留问题、复现步骤 |
| [docs/api.md](docs/api.md) | **接口文档**：37 个接口的地址、鉴权、请求参数、返回结构、错误码、示例 |
| [docs/数据库说明.md](docs/数据库说明.md) | **数据库说明书**：7 张表作用、99 个字段含义、状态码字典、ER 关系 |
| [docs/e2e-browser-test.py](docs/e2e-browser-test.py) | **浏览器端到端测试脚本**（Playwright + 系统 Edge，A/B 双独立上下文，自动截图） |
| [docs/test-suite-v0.10.py](docs/test-suite-v0.10.py) | 接口层测试脚本：冒烟 14 + 功能 86 + 越权专项 27 |
| [docs/api-extra-test.py](docs/api-extra-test.py) | 各模块数据层补充用例 17 条（唯一索引、状态流转、密文、计数一致性） |
| [docs/gen-test-doc.py](docs/gen-test-doc.py) | 由测试结果 JSON 重新生成测试用例文档与报告 |
| [docs/test-evidence/](docs/test-evidence) | 测试过程截图证据（38 张） |

### 16.2 测试结果总览

| 类别 | 数量 | 通过 | 说明 |
| --- | --- | --- | --- |
| 一、主业务闭环（浏览器 E2E） | 20 | **20** | 注册→登录→完善资料/头像→发布商品→浏览→收藏/取消→私聊/已读/回复→下单→双方订单→完成/取消 |
| 二、权限与越权 | 33 | **33** | 8 个受保护页面跳转实测、21 个需登录接口 401 扫描、管理员接口 403、伪造 Token/身份、第三方越权查看订单 |
| 三、各模块独立功能 | 103 | **103** | 用户 23、商品 24、收藏 14、私信 15、订单 16、搜索分类 11（含数据库断言） |
| 四、异常场景 | 32 | **32** | 空参数（前端拦截 + 后端 400）、非法 ID（3001/6003 无 500）、非图片/超 5MB、并发竞态 |
| 附带：接口冒烟闭环 | 14 | **14** | 注册-登录-发布-收藏-私聊-下单-完成 |
| **合计执行检查** | **175** | **175（100%）** | 用例表 161 条 + 冒烟 14 条 |

### 16.3 发现并修复的缺陷

| 编号 | 级别 | 缺陷 | 状态 |
| --- | --- | --- | --- |
| **BUG-01** | 高 | 脚手架演示接口 `GET /api/products` 未过滤状态，**匿名可获取待审核/已下架/已售出商品**（越权泄漏） | 已修复（v0.10） |
| **BUG-02** | 中 | 待审核商品详情对任意访问者可见（未发布内容外泄） | 已修复（v0.10） |
| **BUG-03** | 低 | 前端搜索页每次操作重复发送两次相同请求 | 已修复（v0.10） |
| **BUG-04** | 低 | 并发下唯一索引冲突返回通用错误码 `1002 数据已存在`，而非业务码 4001/2001（**本轮并发测试新发现**） | 已修复（v0.10） |
| BUG-05 | 低 | 分类更新接口 `name` 强制必填，局部更新被 400 拦截 | v0.09 修复，本轮回归通过 |
| BUG-06 | 中 | 上传 >5MB 图片返回 500 而非友好提示 3007 | v0.05 修复，本轮回归通过 |

### 16.4 越权检查结论

- **21 个需登录接口**未携带 Token 时全部返回 401（含商品、收藏、消息、订单、分类管理）；
- **2 个管理员接口**用普通学生 Token 访问全部返回 403；
- 伪造 Token、过期 Token、伪造 `userId` 改他人资料、操作他人商品/订单/消息、跨用户收藏列表比对 —— **全部被正确拦截**；
- 已评估的 4 项低风险（`/api/health/db` 公开统计、JWT 存 localStorage、注册无限流、上传文件直链访问）在报告中给出生产加固建议。

### 16.5 测试操作步骤（速查）

```bash
# 1) 启动：MySQL 服务 → 后端 8080 → 前端 5173（见第三节）
# 2) 数据库层：依次执行 8 个 SQL 脚本（db_schema / db_smoke_test / db_user_test /
#    db_product_test / db_favorite_test / db_message_test / db_order_test / db_v009_test）
# 3) 接口层：python docs/test-suite-v0.10.py   → 冒烟 + 86 条用例 + 27 项越权检查
# 4) 重新生成用例文档：python docs/gen-test-doc.py
# 5) 浏览器手工验证 6 步：见 docs/test-report-v0.10.md 第六节
```

> 自动化脚本会创建临时账号（`qa******`）与临时商品（标题带「【测试】/【冒烟】」）；
> 本项目已在测试后清理完毕，数据库恢复到 4 个用户 / 14 件商品 / 33 个分类的演示状态，
> 且订单与商品状态一致性校验为 0 条不一致。

---

## 十七、数据库怎么查看与理解（配套工具 + 文档）

### 17.1 一键打开数据库

双击项目根目录的 **[打开数据库(HeidiSQL).bat](打开数据库(HeidiSQL).bat)** —— 自动启动 HeidiSQL 并连上 `campus_trade`。

| 项 | 值 |
| --- | --- |
| 工具 | HeidiSQL Portable 12.21（免安装，位于 `D:\major\tool\HeidiSQL`） |
| 备选 | VS Code 扩展 `cweijan.vscode-mysql-client2`（已安装，连接参数同上） |
| 连接参数 | 主机 `127.0.0.1`、端口 `3306`、用户 `root`、密码 `123456`、数据库 `campus_trade` |
| 数据目录 | `D:\major\tool\mysql-8.4.4-winx64\data`（备份整个库直接复制该目录） |

### 17.2 「结构」和「数据」不是一回事

| HeidiSQL 里的标签 | 是什么 | 类比 Excel |
| --- | --- | --- |
| **「基本」** | 表的**结构**：有哪些列、每列类型/是否可空/默认值/注释/索引/外键 | 表格的**表头**（有"姓名/学号/电话"这几列） |
| **「数据」** | 表里的**数据**：一行行的真实记录 | 表格里**一行行的内容**（张三、2021001、138…） |

> 看数据：在左侧树里**双击表名**（单击只显示结构），或点顶部「数据」标签，或点「查询」标签粘贴 SQL 按 F9。

### 17.3 配套文档

| 文档 | 内容 |
| --- | --- |
| [docs/数据库说明.md](docs/数据库说明.md) | **数据库说明书**：结构与数据的区别、7 张表的作用、**全部 99 个字段的含义**（取自 information_schema，与真实库一致）、状态码字典、ER 关系、常见疑问解答 |
| [docs/images/ER图.png](docs/images/ER图.png) | **ER 图**：7 张表 / 12 个外键的实体关系图（论文"数据库设计"章节可直接使用） |
| [docs/常用查询.sql](docs/常用查询.sql) | 8 组现成查询（用户/商品/图片/分类/收藏/消息/订单/概览）+ 常用管理 SQL（重置密码、禁用账号等） |

---

## 十八、v0.11 个性化推荐模块

### 18.1 模块概览

> 详细算法推导、参数说明与实验分析见 **[docs/推荐算法说明.md](docs/推荐算法说明.md)**（论文"推荐算法设计与实现"章节素材）。

```
用户行为埋点（浏览/收藏/私信/下单）
        ↓  写入 user_behavior（幂等：同一用户+商品+行为只保留一行，累加次数与时间）
三路召回 ──┬── Item-CF 协同过滤：sim(i,j)=co(i,j)/√(pop(i)·pop(j))，按用户历史兴趣加权
           ├── 内容匹配：分类偏好 0.55 + 价格相近度 0.30 + 校区一致 0.15
           └── 热门度：log(浏览量/收藏量/行为人数) × 时间新鲜度
        ↓  各自 min-max 归一化
加权融合：score = 0.50·cf + 0.35·content + 0.15·hot（权重可配置）
        ↓  过滤（在售 / 非自己发布 / 未收藏 / 未下单）+ 已浏览降权(×0.55)
排序 → 分类多样性截断（同分类 ≤3）→ TopN → 生成可解释推荐理由
```

### 18.2 新增内容

| 类型 | 内容 |
| --- | --- |
| 数据表 | `user_behavior` 用户行为表（第 8 张表，唯一索引 `uk_user_product_type`），历史行为由 [db_v011_recommend.sql](db_v011_recommend.sql) 从收藏/订单/私信回填 |
| 后端 | `entity/UserBehavior`、`mapper/UserBehaviorMapper`（共现矩阵 SQL 聚合）、`service/UserBehaviorService(+Impl)`、`service/RecommendService(+Impl)`、`controller/RecommendController`、`config/RecommendProperties`、`vo/RecommendItemVO`、`vo/RecommendResultVO`、2 个聚合 DTO |
| 前端 | [components/RecommendPanel.vue](frontend/src/components/RecommendPanel.vue)（可复用推荐面板，展示来源标签与推荐理由）、[api/recommend.js](frontend/src/api/recommend.js)；首页「猜你喜欢」+ 详情页「相关推荐」 |
| 埋点接入 | 商品详情（浏览）、收藏、私信（带商品）、下单 4 个业务点接入行为记录，**全部旁路化**（埋点异常不影响主业务） |
| 评测工具 | [tools/recommend-eval.py](tools/recommend-eval.py) 离线评测脚本（仿真数据 + 留一法 + 6 种策略对比 + 自动清理） |
| 测试 | [docs/test-v011-recommend.py](docs/test-v011-recommend.py)：21 条用例（接口 / 算法规则 / 埋点数据层），**21/21 通过** |

### 18.3 接口

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/product/recommend?size=8&strategy=auto` | 否（带 Token 则个性化） | 猜你喜欢：登录个性化、未登录热门冷启动 |
| GET | `/api/product/similar/{id}?size=6` | 否 | 相似商品：物品共现相似度，不足时同父分类热门补齐 |

`strategy`：`auto`/`hybrid`（0.50/0.35/0.15）、`hot`、`content`、`cf`、`hybrid-cf`（0.70/0.20/0.10）、
`hybrid-content`（0.30/0.60/0.10）—— 用于离线消融实验与线上 A/B，**所有策略共用同一套过滤规则**保证可比。

### 18.4 算法参数（`application.yml` → `campus.recommend.*`，调参不改代码）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | true | 关闭后所有场景退化为热门推荐（可做 A/B 对照） |
| `behavior-weights` | 1/3/2/5 | 浏览/收藏/私信/下单 的兴趣权重 |
| `cf-weight` / `content-weight` / `hot-weight` | 0.50 / 0.35 / 0.15 | 融合权重 |
| `half-life-days` | 30 | 时间衰减半衰期 |
| `candidate-pool-size` | 200 | 候选池大小（按热度截断，避免全表精排） |
| `category-cap` | 3 | 同一分类最多几条（多样性） |
| `seen-penalty` | 0.55 | 仅浏览未收藏/未下单的降权系数 |
| `campus-bonus` | 0.15 | 同校区内容加成 |
| `cold-start-size` | 12 | 冷启动返回条数 |

### 18.5 离线评测结果（留一法，60 用户 / 92 件候选 / Top-10）

| 召回策略 | HitRate@K | Precision@K | 覆盖率 | 平均耗时 | 活跃用户 | 稀疏用户 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 纯热门（基线） | 8.33% | 0.83% | 11.96% | 29.3 ms | 7.69% | 9.52% |
| 纯内容匹配 | 25.00% | 2.50% | 92.39% | 65.9 ms | 30.77% | 14.29% |
| 纯协同过滤 | **36.67%** | **3.67%** | 81.52% | 70.2 ms | **41.03%** | 28.57% |
| 融合 0.50/0.35/0.15（默认） | 35.00% | 3.50% | 82.61% | 71.0 ms | **41.03%** | 23.81% |
| 融合 0.70/0.20/0.10 | 31.67% | 3.17% | 81.52% | 69.6 ms | 33.33% | 28.57% |
| 融合 0.30/0.60/0.10 | 35.00% | 3.50% | 86.96% | 70.3 ms | 35.90% | **33.33%** |

**结论**：个性化相对热门基线提升 **+28.34 个百分点（约 3.4 倍）**，覆盖率从 11.96% 提升到 80%+；
协同过滤在行为充足时最优，而**偏内容的融合策略在稀疏/冷启动用户上最优（33.33%）**，
即"用 1.67 个百分点的整体损失换取稀疏场景的显著提升"——这是选择融合方案而非单一算法的实验依据。
（复现：`python tools/recommend-eval.py`，随机种子 20261003，结果存 `tools/recommend-eval-result.json`）

### 18.6 兼容性说明

| 项 | 说明 |
| --- | --- |
| 既有功能 | 埋点采用旁路方式接入，仅新增调用不改原逻辑；v0.10 全量回归 **127 + 17 条全部通过** |
| 前序页面 | 首页/详情页只新增一行组件标签，原筛选、收藏、下单等逻辑未改动 |
| 数据库 | 仅新增 1 张表（`user_behavior`），原 7 张表结构不变 |
| 接口 | 新增 2 个公开接口，原 37 个接口签名与行为不变（总接口数 39） |

---

## 十九、v0.12 Redis 缓存与限流

> 详细设计、缓存一致性策略与压测数据分析见 **[docs/缓存与限流说明.md](docs/缓存与限流说明.md)**（论文"系统性能优化"章节素材）。

### 19.1 模块概览

```
读请求 ──→ Redis 缓存命中？ ──是──→ 直接返回（不查库）
                     │否
                     ↓  SET NX EX 分布式锁（防击穿：只放行一个线程回源）
                 查 MySQL → 写缓存（TTL + 随机抖动，防雪崩）→ 返回
                     查不到 → 写 30s 空值标记（防穿透）

写请求 ──→ 先写 MySQL ──→ CacheEvictor 统一删除相关缓存
行为请求 ─→ Redis ZSet 热门榜加分 + 仅失效该用户的推荐缓存
```

### 19.2 环境与配置

| 项 | 内容 |
| --- | --- |
| Redis | Windows 版 Redis 5.0.14.1，安装于 `D:\major\tool\redis`，已注册为 **Windows 服务 `RedisCampus`（自动启动）** |
| 配置文件 | `redis-campus.conf`（仅监听本机、`maxmemory 256mb` + `allkeys-lru`、关闭 RDB/AOF 以适配缓存场景） |
| 连接配置 | `application-dev.yml` → `spring.data.redis.*`（支持 `REDIS_HOST/PORT/PASSWORD/DB` 环境变量覆盖） |
| 缓存参数 | `application.yml` → `campus.cache.*`（TTL、抖动比例、去重窗口、回写阈值、热门榜权重、限流跳过本机等 15 项） |
| 一键降级 | 启动参数 `--campus.cache.enabled=false` 可整体关闭缓存（业务代码不改，用于压测对照） |

### 19.3 缓存了什么

| 缓存对象 | Key | TTL | 失效时机 |
| --- | --- | --- | --- |
| 商品列表 | `campus:product:list:{条件指纹}` | 60s | 商品发布/上下架/补图、收藏数变化、订单状态变更、分类变更 |
| 商品详情 | `campus:product:detail:{id}` | 120s | 同上；**待审核商品不缓存**（可见性与访问者身份相关） |
| 分类列表 | `campus:category:list:all\|:top` | 600s | 分类新增/修改（按模式删除） |
| 推荐结果 | `campus:recommend:{用户\|anon}:{size}:{策略}` | 300s | 该用户产生新行为时**只失效自己的** |
| 热门榜 | `campus:hot:{yyyyMMdd}`（ZSet） | 3 天 | 由行为实时加分，`/api/product/hot` 与热门推荐直接读取 |

### 19.4 三个经典缓存问题与防护

| 问题 | 本系统做法 | 验证用例 |
| --- | --- | --- |
| 缓存穿透 | 查不到时写 `__NULL__` 空值标记（TTL 30s） | C8 |
| 缓存雪崩 | TTL 乘 `[0.8, 1.2]` 随机抖动 + 分级 TTL | C1/C4/C5（观察 TTL 抖动） |
| 缓存击穿 | 回源前 `SET NX EX` 分布式锁 + 等待重读；释放用 Lua 校验值 | — |

> **两条一致性纪律（v0.12 测试中真实踩到并修复）**：
> ① **缓存对象必须与访问者身份无关** —— 待审核商品"卖家可见、匿名不可见"，若把卖家视角写进公共缓存，
> 匿名用户随后也会命中，因此待审核商品一律不缓存；
> ② **缓存命中路径也要执行副作用** —— 行为埋点原写在回源方法内，缓存命中时被跳过，
> 导致"重复浏览不再计入兴趣"，已把埋点提到缓存判断之前。

### 19.5 浏览量优化、限流与安全

- **浏览量**：由"每次访问都 `UPDATE view_count + 1`"改为 **Redis 去重（600s 窗口，按用户/IP）+ 计数 + 达阈值(20)批量回写**，把 N 次写库压成 1 次（用例 C9/C10 验证）；
- **限流**：注解 `@RateLimit` + Lua 原子计数，覆盖注册(10/5min·IP)、登录(20/min·IP)、发消息(30/min·用户)、发布商品(10/5min·用户)、收藏(60/min·用户)，触发返回 **429**；Redis 故障时降级放行；本机回环默认跳过（`rate-limit-skip-local`），可用 `X-Forwarded-For` 验证（用例 C13/C15）；
- **JWT 黑名单**：退出登录把 Token 的 SHA-256 摘要写入 Redis（TTL = 剩余有效期），拦截器校验，实现"可撤销会话"（用例 C16/C17）。

### 19.6 压测对比结果（20 并发 × 每场景 15 秒）

| 场景 | 缓存关 QPS | 缓存开 QPS | 提升 | 缓存关 平均延迟 | 缓存开 平均延迟 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 首页商品列表 | 680.7 | **6507.2** | 9.6× | 29.36 ms | **3.07 ms** |
| 商品详情 | 710.8 | **6783.8** | 9.5× | 28.11 ms | **2.95 ms** |
| 猜你喜欢(推荐) | 553.0 | **6172.4** | 11.2× | 36.14 ms | **3.24 ms** |
| 分类列表 | 680.3 | **6613.9** | 9.7× | 29.38 ms | **3.02 ms** |
| **平均** | **656.2** | **6518.1** | **9.9×** | **30.75 ms** | **3.07 ms** |

| 数据库压力 | 缓存关 | 缓存开 | 变化 |
| --- | ---: | ---: | ---: |
| 数据库执行语句数（4 场景合计） | 139,797 | **126** | **−99.91%** |
| 数据库 QPS | 2333.5 | **2.1** | **−99.91%** |

> 测量说明：单机环境（应用/Redis/MySQL 同机）+ 演示数据集，绝对数值高于真实多机部署；
> 结论关注的是**同一环境下开关缓存的相对提升**与**数据库减压比例**。
> 复现：`python tools/load-test.py --label cache-on`（关缓存需用 `--campus.cache.enabled=false` 启动）。

### 19.7 新增/改动清单

| 类型 | 内容 |
| --- | --- |
| 后端新增（9 个） | `config/RedisConfig`、`config/CacheProperties`、`common/cache/{CacheKeys,CacheEvictor,TokenBlacklist}`、`common/annotation/RateLimit`、`common/aspect/RateLimitAspect`、`service/CacheService(+Impl)` |
| 后端改动 | 商品（列表/详情缓存、浏览量、埋点位置）、分类（列表缓存与失效）、收藏/订单（缓存失效）、推荐（结果缓存 + 热门榜接口）、用户（登出黑名单）、拦截器（黑名单校验）、`ResultCode` 增加 429 |
| 接口 | `GET /api/product/hot`（新增，读 Redis ZSet），总接口数 40 |
| 工具 | [tools/load-test.py](tools/load-test.py)（自研压测脚本，含数据库语句数统计） |
| 测试 | [docs/test-v012-cache.py](docs/test-v012-cache.py)：**19 条用例全部通过** |
| 前端 | **无需改动**（纯后端优化，接口协议不变） |

### 19.8 兼容性与回归

| 项 | 说明 |
| --- | --- |
| 数据库 | **无表结构变更** |
| 接口 | 原 39 个接口签名与返回结构不变（新增 1 个热门榜接口） |
| 前端 | 无需改动，缓存对前端完全透明 |
| 回归 | v0.10（127 条）+ 补充（17 条）+ v0.11（21 条）+ v0.12（19 条）**全部通过** |
