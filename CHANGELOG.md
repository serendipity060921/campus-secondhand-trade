# 版本变更记录（CHANGELOG）

> 本项目按里程碑迭代，每个版本一条提交 + 一个 Git tag（`v0.01` ~ `v0.15.1`）。
> 各版本的实现细节见 [README 的版本里程碑表](README.md#版本里程碑详解) 与 [docs/milestones/](docs/milestones/)。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循「里程碑 + 补丁」的语义：`v0.15` 是里程碑，`v0.15.1` 是其后的问题修复。

---

## [v0.15.1] - 2026-10-04

### Fixed
- **前端版本号显示脱节**：登录页/注册页/顶栏/页脚/占位页原本各自硬编码 `v0.04`，
  与当前版本严重不符。新增 `frontend/src/utils/version.js` 作为前端版本号的**唯一来源**，
  5 处改为引用；后端 `application.yml` / `application-dev.yml` / `HealthController`
  默认值统一为 `v0.15`。
- **打包瘦包导致启动失败**：后端进程运行时会锁住 jar，此时执行 `maven repackage`
  会失败并留下约 0.4MB 的"瘦包"（内嵌依赖丢失），表现为进程启动后立即退出、
  健康检查一直不通过。`deploy/windows/start.ps1` 增加 jar 完整性检查
  （小于 10MB 直接报错并提示"先 stop 再 clean package"）。

## [v0.15] - 2026-10-04 — 部署与交付
### Added
- 生产配置分离 `application-prod.yml`：数据库/Redis/JWT 密钥/上传目录全部环境变量注入
  （密钥缺失即启动失败）、日志文件滚动（50MB / 保留 30 天）、关闭 SQL 明细打印。
- Nginx 反向代理（容器版 + Windows 版）：SPA history 回退、`/api` 反代、`/upload` 静态映射、
  **`/ws` 协议升级**（Upgrade + Connection，超时 300s）、hash 资源长缓存 + `index.html` 不缓存。
- Docker Compose 四服务编排（健康检查依赖、命名数据卷、SQL 自动初始化）+ 前后端多阶段
  Dockerfile（依赖层缓存、国内镜像加速、非 root 运行）。
- Windows 原生一键启停脚本 `deploy/windows/start.ps1` / `stop.ps1`（已真机验证）。
- 部署验证用例 28 条；文档 `docs/部署说明.md`。
### Changed
- 生产加固：限流不再跳过本机（`rate-limit-skip-local=false`）、
  `/api/health/db` 库表统计探针生产关闭（返回 403）。
### Fixed
- `upstream` 配 `keepalive` 但部分 location 缺 `proxy_http_version 1.1` / `Connection ""`，
  导致 `/health` 被后端判为非法请求返回 400。
- 本机 80 端口被第三方加速器占用（bind 10013），部署改用 8081 并记录排查方式。
- PowerShell 5.1 按 ANSI 解码无 BOM 的 UTF-8 脚本导致中文注释引发语法错乱（改存 UTF-8 with BOM）。

## [v0.14] - 2026-10-04 — WebSocket 实时私信
### Added
- `/ws/chat` 端点：握手阶段完成 JWT 鉴权（签名/有效期/Redis 黑名单/账号禁用），
  不合法返回 401/403；会话注册表支持多标签页，全部断开才算离线。
- 统一 JSON 信封协议（`chat`/`read`/`ping`/`queryOnline`/`welcome`/`pong`/`error`）。
- Redis ZSet 维护在线状态（score = 心跳时间戳，90 秒窗口）；WS 通道限流 30 条/分钟。
- 前端单例 WebSocket 客户端（25 秒心跳、指数退避重连）、实时状态 store、
  未读角标、新消息弹窗、已读回执、在线标识。
- 新接口 `GET /api/message/unreadTotal`、`GET /api/message/online`；测试用例 24 条。
### Changed
- 聊天页由「5 秒轮询」改为**服务端推送**，消息延迟由 0~5 秒降到 100ms 内；
  WebSocket 不可用时自动降级为 15 秒轮询，发送失败降级 REST（消息不丢）。
### Fixed
- Redis 5.0 不支持 `ZMSCORE`（6.2 才引入）导致批量在线查询静默返回全离线，
  改用 `ZRANGEBYSCORE`。
- JWT 的 `iat/exp` 只到秒，同一秒内为同一用户签发的 Token 完全相同，
  导致"登出黑名单"用例误伤主账号（改用独立临时账号验证）。

## [v0.13] - 2026-10-04 — 管理后台与数据看板
### Added
- 管理后台：ECharts 数据看板（概览 / 近 7 天趋势 / 分类与状态分布）、商品审核（通过·驳回）、
  强制下架、用户启用禁用、举报处理、管理员操作日志。
- 新表 `report`（举报）、`admin_log`（操作日志）；商品审核复用 v0.02 预留的审核字段。
- 用户侧举报入口（商品详情页）；`campus.audit.enabled` 审核开关（默认关闭以兼容既有流程）。
### Fixed
- 提交举报 500：未处理举报的 `handle_admin_id` 为 null，而 VO 转换用了 `Map.of()`
  创建的不可变 Map（对 null 查询直接抛 NPE），改用 `HashMap` 并判空。

## [v0.12] - 2026-10-03 — Redis 缓存与限流
### Added
- Cache-Aside 缓存：商品列表/详情、分类、推荐结果；空值缓存防穿透、
  TTL 随机抖动防雪崩、`SET NX EX` 分布式锁防击穿。
- 浏览量 Redis 去重（600 秒窗口）+ 计数 + 达阈值批量回写；行为热门榜（ZSet）。
- 注解式限流 `@RateLimit`（Lua 原子计数，注册/登录/发消息/发布/收藏）；JWT 登出黑名单。
### Performance
- 压测（20 并发 × 每场景 15 秒）：**QPS 656 → 6518（约 10 倍）**，平均延迟 30.75ms → 3.07ms，
  数据库 QPS 2333 → 2.1（**负载下降 99.9%**）。

## [v0.11] - 2026-10-03 — 个性化推荐模块
### Added
- 用户行为埋点表 `user_behavior`（浏览/收藏/私信/下单，带权重与频次）。
- Item-CF + 内容匹配 + 热门度三路召回与融合排序（权重可配置），过滤已收藏/已下单/自己发布，
  多样性控制（单分类上限）与可解释推荐理由。
- 接口：猜你喜欢、相似商品（支持 `strategy` 参数做离线消融与分桶）。
- 离线评测脚本 `tools/recommend-eval.py`：6 种策略对比（Top-10 命中率 / 覆盖率 / 耗时）。

## [v0.10] - 2026-10-02 — 系统测试与 bug 修复
### Added
- 161 条测试用例（接口 + 端到端）、完整业务闭环冒烟、越权专项检查、接口文档 `docs/api.md`。
### Fixed
- 匿名商品列表泄漏未上架商品；待审核商品详情对外可见；
  并发唯一索引冲突返回错误码不友好。

## [v0.09] - 2026-10-02 — 辅助功能模块
### Added
- 个人资料修改与头像上传、商品搜索（名称模糊 + 分类筛选 + 分页）、分类新增/修改。

## [v0.08] - 2026-10-02 — 订单交易模块
### Added
- 下单、订单状态流转（待交易/已完成/已取消）、买家与卖家订单列表、订单详情与权限校验。

## [v0.07] - 2026-10-02 — 私信聊天模块
### Added
- 发送私信、会话列表、聊天记录分页、消息已读；商品详情页「私聊卖家」入口。

## [v0.06] - 2026-10-02 — 商品收藏模块
### Added
- 收藏 / 取消收藏、我的收藏分页、是否已收藏查询；详情页收藏按钮。

## [v0.05] - 2026-10-02 — 商品核心模块
### Added
- 发布商品、图片上传、分页列表、商品详情、上下架权限控制、9 张 800×800 演示占位图。

## [v0.04] - 2026-10-02 — 用户模块（JWT 鉴权）
### Added
- 注册、登录、BCrypt 密码加密、JWT 工具类与 `@LoginRequired` 拦截器、
  登录/注册页、前端路由守卫。

## [v0.03] - 2026-10-02 — 前后端分离脚手架
### Added
- Spring Boot 3 分层骨架（统一响应、全局异常、MyBatis-Plus、跨域）+ Vue 3 + Vite 骨架，
  前后端连通性自检看板。

## [v0.02] - 2026-10-02 — 数据库设计
### Added
- `db/schema/01-schema.sql`：10 张表设计（当时 7 张）+ 初始化数据，MySQL 8.4.4 实测通过；
  结构与业务冒烟脚本。

## [v0.01] - 2026-10-02 — 需求分析
### Added
- `spec.md`：角色定义、功能清单、业务流程、目录规划。
