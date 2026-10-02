# 校园二手交易平台 系统测试报告（v0.10）

| 项目 | 内容 |
| --- | --- |
| 报告版本 | v0.10（里程碑：系统测试与 bug 修复） |
| 测试对象 | 校园二手交易平台 v0.03 ~ v0.09 全部功能 |
| 测试时间 | 2026-10-02 |
| 测试环境 | Windows 11 · OpenJDK 17.0.2 · Maven 3.9.9 · MySQL 8.4.4 · Node 24.21 · Vite 5.4.21 · Element Plus 2.14 |
| 测试数据 | 4 个用户、33 个分类、14+ 件商品、若干订单/收藏/私信（含自动化测试临时数据） |
| 测试结论 | **冒烟 14/14、功能用例 86/86、越权专项 27/27，全部通过；发现并修复缺陷 3 个** |

---

## 一、测试范围与方法

| 层次 | 方法 | 工具 |
| --- | --- | --- |
| 接口功能测试 | 127 条用例（正常 / 异常 / 边界 / 越权），逐条断言业务状态码 | 自研 Python 脚本 `docs/test-suite-v0.10.py` |
| 冒烟测试 | 用全新账号跑通「注册→登录→发布商品→收藏→私聊→下单→完成」闭环 | 同上 |
| 越权专项 | 21 个受保护接口未登录检查、2 个管理员接口学生越权检查、伪造身份/Token、边界状态探测 | 同上 |
| 数据层测试 | 表结构、约束、状态联动、计数一致性校验 | `db_schema.sql`、`db_*_test.sql` 共 8 个 SQL 脚本 |
| 前端测试 | 页面/模块编译检查、构建检查、浏览器手工验证主流程 | Vite dev server + Chrome/Edge |

---

## 二、冒烟测试结果（完整业务闭环）

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| S1 | 注册新用户 `qa******` | ✅ 200 注册成功 |
| S2 | 新账号登录获取 Token | ✅ 返回 196 位 JWT |
| S3 | 卖家（admin）发布待售商品 | ✅ 商品ID=30 |
| S4 | 新用户发布商品（含图片） | ✅ 商品ID=31 |
| S5 | 打开商品详情 | ✅ 卖家昵称正确 |
| S6 | 收藏卖家商品 | ✅ 200 收藏成功 |
| S7 | 查看我的收藏 | ✅ 收藏数=1 |
| S8 | 私聊卖家（带关联商品） | ✅ 200 发送成功 |
| S9 | 查看会话列表 | ✅ 会话数=1 |
| S10 | 标记会话已读 | ✅ 200 |
| S11 | 下单购买 | ✅ 订单ID=13，商品转为「交易中」 |
| S12 | 查看订单详情（买家） | ✅ 状态=待交易 |
| S13 | 卖家确认完成 | ✅ 订单已完成、商品已售出、双方信用分 +1 |
| S14 | 查看「我买到的」 | ✅ 订单在列表中 |

> **结论：14/14 通过，完整业务闭环可用。**

---

## 三、缺陷清单与修复

### BUG-01【高 · 数据越权泄漏】脚手架演示接口 `/api/products` 返回非上架商品

| 项 | 内容 |
| --- | --- |
| 发现方式 | 用例 TC-P17：匿名调用 `GET /api/products?page=1&size=50`，断言结果必须全部为 `status=1` |
| 现象 | 返回了**待审核 / 已下架 / 交易中 / 已售出**的商品，匿名用户可获取未公开商品数据 |
| 根因 | v0.03 演示接口直接 `productService.page(new Page<>(page, size))`，未加状态条件；`getById` 详情同理 |
| 影响 | 商品未审核内容、下架/已售商品被匿名访问者拿到（越权 + 数据泄漏） |
| 修复 | `ProductController` 列表增加 `eq(status, 1)`；详情改为「只查在售商品」，非在售统一按 404 返回 |
| 验证 | TC-P17 由 FAIL → PASS；`/dev/health` 连通性看板仍正常 |

```java
// 修复后（ProductController.java）
@GetMapping
public Result<PageResult<Product>> page(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "10") long size) {
    Page<Product> result = productService.page(new Page<>(page, size),
            new LambdaQueryWrapper<Product>().eq(Product::getStatus, STATUS_ON_SALE));   // ← 只返回在售
    return Result.success(PageResult.of(result));
}
```

### BUG-02【中 · 未发布内容可见】待审核商品详情对任意访问者可见

| 项 | 内容 |
| --- | --- |
| 发现方式 | 用例 TC-P18：用 SQL 把商品置为 `status=0（待审核）`，匿名访问 `GET /api/product/{id}` |
| 现象 | 匿名访问返回 200，未审核商品内容提前外泄（审核机制上线后会成为正式漏洞） |
| 根因 | `ProductModuleServiceImpl.detail` 只判断"商品存在"，未区分状态与访问者身份；且公开接口拿不到当前登录用户 |
| 修复 | ① `LoginInterceptor` 对**公开接口**也尝试解析 Token（可选鉴权，失败静默忽略）；② 详情接口对待审核商品做「卖家本人 or 管理员可见」判断，其余按 3001 处理 |
| 验证 | TC-P18（匿名 3001）、TC-P19（卖家本人 200）、安全检查 3.5（管理员 200）全部通过 |

```java
// ① LoginInterceptor：公开接口的可选鉴权（不影响原逻辑，Token 异常一律忽略）
if (loginRequired == null) {
    tryPopulateContext(request);
    return true;
}

// ② ProductModuleServiceImpl.detail：待审核商品仅本人/管理员可见
if (Integer.valueOf(STATUS_PENDING).equals(product.getStatus())) {
    LoginUser current = UserContext.get();
    boolean isOwner = current != null && Objects.equals(current.userId(), product.getSellerId());
    boolean isAdmin = current != null && current.isAdmin();
    if (!isOwner && !isAdmin) throw ProductException.notFound();
}
```

### BUG-03【低 · 前端重复请求】搜索页每次操作发送两次相同请求

| 项 | 内容 |
| --- | --- |
| 发现方式 | 代码走查 + 浏览器 Network 面板（搜索/翻页时同一接口出现 2 次请求） |
| 根因 | `handleSearch` 里既 `router.replace`（触发 `watch(route.query)` 里的加载）又手动调用 `loadProducts()` |
| 修复 | 新增 `applyAndReload()`：URL 有变化交给 watcher 统一加载，URL 未变化才直接查询 |
| 验证 | 搜索/切换分类/翻页/重置均只发 1 次请求，结果与 URL 保持一致 |

### 历史缺陷回归

| 编号 | 缺陷 | 状态 |
| --- | --- | --- |
| BUG-04 | 分类更新接口 `name` 被强制必填，导致"只改上级分类"这类局部更新被 400 拦截（无法返回 7004） | v0.09 修复，v0.10 用例 TC-A09 回归通过 |
| BUG-05 | 上传超过 5MB 的图片被 Servlet 层拦截后返回 500，而不是友好的 3007 | v0.05 修复（新增高优先级上传异常处理器），v0.10 用例 TC-U18 回归通过 |

---

## 四、越权与安全检查结论

### 4.1 检查结果（27/27 通过）

| 检查项 | 覆盖范围 | 结果 |
| --- | --- | --- |
| 受保护接口未登录访问 | **全部 21 个需登录接口**（用户/商品/收藏/消息/订单/分类管理） | ✅ 全部返回 401 |
| 管理员接口越权 | `POST /api/category/add`、`PUT /api/category/update` 用普通学生 Token | ✅ 全部返回 403 |
| 伪造 Token | 4 个受保护接口传 `fake.token.value` | ✅ 全部 401 |
| 过期 Token | 手工用同一密钥签发过期 JWT 访问 `/api/user/info` | ✅ 401「登录已过期」 |
| 伪造身份改他人资料 | 请求体塞 `id=1 / userId=1` 试图改管理员昵称 | ✅ 管理员昵称未被篡改 |
| 操作他人商品 | 上下架他人商品（TC-P12）、向他人商品补传图片（3.4） | ✅ 3002 无权操作 |
| 查看他人订单 | 第三方查看/修改他人订单（TC-O10/O11） | ✅ 6004 无权操作 |
| 标记他人消息已读 | 传入"接收人是别人"的消息 ID（TC-M09） | ✅ 更新 0 条 |
| 收藏数据隔离 | 比对两个用户的收藏列表（TC-F10） | ✅ 无交集 |
| 待审核商品可见性 | 匿名/卖家/管理员三种身份（3.5） | ✅ 3001 / 200 / 200 |

### 4.2 已知可接受风险（已评估，建议生产环境加固）

| 风险点 | 说明 | 建议 |
| --- | --- | --- |
| `/api/health/db` 公开 | 暴露用户/分类/商品**数量**，无明细数据 | 生产环境关闭该接口或用 `@Profile("dev")` 限定 |
| JWT 存 localStorage | 若存在 XSS 可被读取 | 生产可改 httpOnly Cookie + CSRF 防护 |
| 注册/登录无频率限制 | 可被暴力尝试 | 接入验证码 + Redis 计数限流（v0.04 已预留 Redis 设计） |
| 上传文件可被直接访问 | URL 为 UUID，不可枚举，但拿到链接即可访问 | 敏感文件可改为鉴权下载 |
| 图片上传大小限制 5MB | Servlet 与业务层双重限制 | 已符合预期 |

---

## 五、测试操作步骤

### 5.1 环境准备

```bat
:: 1) 数据库（Windows 服务已设为自动启动）
sc query MySQL
mysql -h 127.0.0.1 -P 3306 -u root -p123456 -e "USE campus_trade; SHOW TABLES;"

:: 2) 后端（8080）
cd D:\campus-secondhand-trade\backend
mvnw.cmd spring-boot:run

:: 3) 前端（5173）
cd D:\campus-secondhand-trade\frontend
pnpm dev
```

### 5.2 执行数据库层测试（8 个 SQL 脚本）

```bat
cd D:\campus-secondhand-trade
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_schema.sql
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_smoke_test.sql
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_user_test.sql
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_product_test.sql
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_favorite_test.sql
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_message_test.sql
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_order_test.sql
mysql -h 127.0.0.1 -P 3306 -u root -p123456 --default-character-set=utf8mb4 < db_v009_test.sql
```

### 5.3 执行自动化接口测试（冒烟 + 127 条用例）

```bat
cd D:\campus-secondhand-trade
python docs\test-suite-v0.10.py
```

脚本会依次执行 PART1 冒烟、PART2 功能用例、PART3 越权专项，并把结果写入
`%TEMP%\dsh-sqlval\v010_result.json`；用 `python <项目>\docs\\gen-test-doc.py` 可重新生成用例文档。
（提示：脚本依赖 `mysql.exe` 构造少量边界状态，PATH 中已有该命令。）

### 5.4 浏览器手工验证（关键 6 步）

1. 打开 <http://localhost:5173/>，顶部搜索框输入「教材」→ 进入搜索结果页，检查分类下拉与分页；
2. 未登录点商品详情「☆ 收藏」→ 跳登录；用 `stu_test01 / abc12345` 登录后收藏成功，按钮变「★ 已收藏」；
3. 商品详情「私聊卖家」→ 进入聊天窗口发送消息；用 `admin / 123456` 在另一浏览器回复，验证 5 秒内自动刷新与未读红点；
4. 「立即购买」→ 填交易地点/备注 → 下单成功跳订单详情；切换 `admin` 账号在「订单 → 我卖出的」确认完成；
5. 个人中心 →「编辑资料 / 上传头像」→ 换头像、改昵称/校区，保存后顶部导航同步更新；
6. 用 `stu_demo / 123456` 直接访问 `http://localhost:5173/orders/{别人的订单ID}` → 页面提示「无权查看该订单」。

---

## 六、结论与遗留问题

1. **功能完整性**：v0.03~v0.09 的 37 个接口、9 个前端页面在主流程上全部可用，127 条用例 100% 通过。
2. **安全性**：修复了 2 个越权/泄漏缺陷（BUG-01、BUG-02）；21 个受保护接口、2 个管理员接口的鉴权与越权检查全部通过；剩余 4 项为已评估的低风险项，给出了生产加固建议。
3. **遗留问题（不属于本版本缺陷，作为后续里程碑规划）**：
   - 商品审核流程尚未启用（v0.05 发布即上架），待后台管理里程碑接入 `status=0 待审核`；
   - 前端未做响应式适配（按 ≥1366×768 设计）；
   - 私信为 5 秒轮询，后续可升级 WebSocket；
   - 未接入 Redis（验证码、热词、Token 黑名单）。

---

**相关文档**：[测试用例](./test-cases.md) · [接口文档](./api.md) · [需求与设计](../spec.md) · [项目说明](../README.md)
