# 校园二手交易平台 接口文档（v0.10）

> 适用版本：v0.03 ~ v0.10 全部接口　|　最后更新：v0.10（系统测试与 bug 修复）
> 后端：Spring Boot 3.2.5 + MyBatis-Plus 3.5.7　|　数据库：MySQL 8.4.4（库名 `campus_trade`）

---

## 一、通用约定

| 项 | 说明 |
| --- | --- |
| 服务地址 | `http://localhost:8080`（开发环境；前端 Vite 通过 `/api` 代理访问） |
| 接口前缀 | 统一为 `/api` |
| 请求体格式 | `application/json;charset=UTF-8`；文件上传为 `multipart/form-data` |
| 鉴权方式 | 请求头 `Authorization: Bearer <token>`（Token 由登录接口返回，默认 2 小时有效） |
| 时间格式 | `yyyy-MM-dd HH:mm:ss` |
| 分页参数 | `page`（从 1 开始）、`size`（默认 10/12/20，上限 100） |

### 1.1 统一响应格式

所有接口（含异常）都返回同一结构，**HTTP 状态码统一为 200**，业务结果看 `code`：

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": 1790940604403
}
```

分页数据的 `data` 结构：

```json
{
  "total": 14, "pages": 2, "current": 1, "size": 12,
  "records": [ ... ]
}
```

### 1.2 全局错误码

| code | 含义 | 说明 |
| --- | --- | --- |
| 200 | 成功 | - |
| 400 | 参数校验失败 | 字段级提示以「；」拼接返回 |
| 401 | 未登录 / Token 无效 / Token 过期 | 前端拦截器会清 Token 并跳登录页 |
| 403 | 没有操作权限 | 非管理员访问管理员接口 |
| 404 | 接口不存在 | 路径写错 |
| 500 | 服务器内部错误 | 详见后端日志 |
| 2001 | 用户名已被注册 | 注册 |
| 2002 | 账号不存在 | 登录、查资料 |
| 2003 | 密码错误 | 登录 |
| 2004 | 账号已被禁用 | 登录 |
| **2006** | 该手机号已被其他账号绑定 | 修改资料 |
| 3001 | 商品不存在或已被删除 | 商品、收藏、订单 |
| 3002 | 无权操作他人发布的商品 | 上下架、补传图片 |
| 3003 | 商品当前状态不允许该操作 | 交易中/已售出 不允许上下架 |
| 3004 | 商品分类不存在 | 发布商品 |
| 3005 / 3006 / 3007 / 3009 | 文件为空 / 格式不支持 / 超出大小 / 数量超限 | 图片上传 |
| 4001 / 4002 / 4003 | 重复收藏 / 收藏自己的商品 / 尚未收藏 | 收藏 |
| 5001 / 5002 | 不能给自己发消息 / 接收人不存在 | 私信 |
| 6001 | 商品已下架或已被预订 | 下单 |
| 6002 | 不能购买自己发布的商品 | 下单 |
| 6003 / 6004 / 6005 | 订单不存在 / 无权操作该订单 / 订单状态不允许 | 订单 |
| 7001 / 7002 / 7003 / 7004 | 分类重名 / 分类不存在 / 上级分类不存在 / 上级不能是自己 | 分类管理 |

---

## 二、认证与用户模块

### 2.1 学生注册

| 项 | 值 |
| --- | --- |
| 方法/路径 | `POST /api/user/register` |
| 鉴权 | 否 |
| 请求体 | `username`(4~20 位字母数字下划线，唯一)、`password`(6~20 位且含字母和数字)、`nickname`(2~20 位) |

```bash
curl -X POST http://localhost:8080/api/user/register -H "Content-Type: application/json" \
  -d '{"username":"stu_test01","password":"abc12345","nickname":"测试同学01"}'
```

```json
{ "code": 200, "message": "注册成功",
  "data": { "id": 5, "username": "stu_test01", "nickname": "测试同学01", "role": 0, "creditScore": 100 } }
```

> 密码用 BCrypt 加密存储；用户名重复返回 2001；参数不合法返回 400（带字段提示）。

### 2.2 学生登录

| 项 | 值 |
| --- | --- |
| 方法/路径 | `POST /api/user/login` |
| 鉴权 | 否 |
| 请求体 | `username`、`password` |
| 返回 | `token`、`tokenType`、`expiresIn`(秒)、`userInfo` |

```json
{ "code": 200, "message": "登录成功",
  "data": { "token": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 7200,
            "userInfo": { "id": 5, "username": "stu_test01", "nickname": "测试同学01", "role": 0 } } }
```

> 登录接口别名：`POST /api/auth/login`（等价，兼容脚手架预留路径）；`POST /api/auth/logout`、`POST /api/user/logout` 为无状态退出。

### 2.3 查询当前用户信息（公开字段）

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/user/info` |
| 鉴权 | **是** |
| 返回 | `UserVO`：id、username、nickname、avatar、gender、school、campus、role、status、creditScore、lastLoginTime、createTime |

### 2.4 查询自己的完整资料（含隐私字段）

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/user/profile` |
| 鉴权 | **是** |
| 返回 | `UserProfileVO`：在 UserVO 基础上增加 `realName`、`studentNo`、`phone`、`email` |

### 2.5 修改个人资料

| 项 | 值 |
| --- | --- |
| 方法/路径 | `PUT /api/user/update` |
| 鉴权 | **是** |
| 请求体 | `nickname`、`avatar`、`realName`、`gender`(0/1/2)、`school`、`campus`、`phone`、`email`（**只需提交要修改的字段**） |

```bash
curl -X PUT http://localhost:8080/api/user/update -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"测试同学01改","campus":"东校区A栋","gender":1,"email":"stu01@campus.edu"}'
```

> 用户名与学号不可修改；手机号需全平台唯一，重复返回 2006。

### 2.6 上传头像

| 项 | 值 |
| --- | --- |
| 方法/路径 | `POST /api/user/avatar` |
| 鉴权 | **是** |
| 请求体 | `multipart/form-data`，字段名固定 `file` |
| 限制 | 支持 jpg/jpeg/png/gif/webp/bmp，单张 ≤ 5MB |
| 返回 | 更新后的 `UserProfileVO`（其中 `avatar` 为新地址，如 `/upload/2026/10/ab12.png`） |

```bash
curl -X POST http://localhost:8080/api/user/avatar -H "Authorization: Bearer $TOKEN" -F "file=@avatar.png"
```

---

## 三、商品模块

### 3.1 发布商品

| 项 | 值 |
| --- | --- |
| 方法/路径 | `POST /api/product/publish` |
| 鉴权 | **是**（卖家 = 当前登录用户，前端不能指定 sellerId） |
| 请求体 | `title`(2~100)、`description`(≤2000)、`categoryId`(**必填**)、`price`(**必填** >0，2 位小数)、`originalPrice`、`conditionLevel`(1~4)、`campus`、`tradePlace`、`imageUrls`(≤9 个，第一张作封面) |
| 返回 | `ProductDetailVO`（含 id、categoryName、sellerNickname、productStatusLabel 等） |

### 3.2 图片上传

| 项 | 值 |
| --- | --- |
| 方法/路径 | `POST /api/product/upload` |
| 鉴权 | **是** |
| 请求体 | `multipart/form-data`：单张 `file` 或多张 `files`；可选 `productId` |
| 返回 | `[{ originalName, url, size }]`；带 `productId` 时会把图片写入 `product_image`（校验商品归属，非本人返回 3002） |

```bash
curl -X POST http://localhost:8080/api/product/upload -H "Authorization: Bearer $TOKEN" \
  -F "files=@a.png" -F "files=@b.png"
# {"code":200,"data":[{"originalName":"a.png","url":"/upload/2026/10/xxx.png","size":5121}]}
```

> 图片保存在 `backend/uploads/yyyy/MM/uuid.ext`，通过 `/upload/**` 静态访问。

### 3.3 商品分页列表（首页）

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/product/list` |
| 鉴权 | 否 |
| 查询参数 | `page`、`size`、`categoryId`（一级分类自动含子分类）、`keyword`（名称或描述模糊）、`minPrice`、`maxPrice`、`sort`（`new` 默认 / `priceAsc` / `priceDesc` / `hot`） |
| 返回 | `PageResult<ProductVO>`，**只含上架商品** |

### 3.4 商品详情

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/product/{id}` |
| 鉴权 | 否（但**待审核商品只有卖家本人和管理员可见**，其他人按 3001 处理） |
| 返回 | `ProductDetailVO`：商品字段 + `images[]`（product_image 表）+ 卖家昵称/头像/信用分/校区 + `favoriteCount` 等；每次访问浏览量 +1 |

### 3.5 我的商品

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/product/mine` |
| 鉴权 | **是** |
| 查询参数 | `page`、`size`、`status`（可选，0/1/2/3/4/5） |
| 返回 | `PageResult<ProductVO>`（自己的全部状态商品） |

### 3.6 商品上下架

| 项 | 值 |
| --- | --- |
| 方法/路径 | `PUT /api/product/status` |
| 鉴权 | **是**（只能操作自己发布的商品） |
| 请求体 | `productId`、`status`（**1 上架 / 3 下架**） |
| 返回 | 空 data |

> 越权返回 3002；状态不是 1/3 返回 400；交易中(4)/已售出(5) 返回 3003。

### 3.7 商品搜索

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/product/search` |
| 鉴权 | 否 |
| 查询参数 | `keyword`（≤50 字，**按商品名称模糊匹配**）、`categoryId`（含子分类）、`minPrice`、`maxPrice`、`sort`、`page`、`size` |
| 返回 | `PageResult<ProductVO>`，只含上架商品 |

```bash
curl "http://localhost:8080/api/product/search?keyword=教材&categoryId=1&page=1&size=12"
```

### 3.8 脚手架演示接口（保留）

| 方法/路径 | 说明 |
| --- | --- |
| `GET /api/products?page=&size=` | 分页查询**在售**商品（v0.10 已修复越权泄漏问题，仅返回 status=1） |
| `GET /api/products/{id}` | 查询在售商品详情，非在售按 404 处理 |

### 3.9 推荐接口（v0.11）

#### 猜你喜欢

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/product/recommend` |
| 鉴权 | 否（**公开接口**；携带有效 Token 时自动个性化，未登录退化为热门推荐） |
| 查询参数 | `size`（1~30，默认 8）、`strategy`（见下表，默认 `auto`） |
| 返回 | `RecommendResultVO`：`strategy`、`strategyLabel`、`personalized`、`profileDesc`、`basis[]`（算法过程说明）、`total`、`items[]` |

`items[]` 每条包含商品卡片字段 + **算法可解释字段**：
`score`（综合分）、`cfScore`、`contentScore`、`hotScore`（三路归一化得分）、
`sourceType`（1 协同过滤 / 2 内容匹配 / 3 热门 / 4 混合）、`sourceLabel`、`reason`（一句话理由）、`reasons[]`。

`strategy` 取值（同一套过滤规则，便于离线对比与线上 A/B）：

| 取值 | 含义 | 权重（协同/内容/热门） |
| --- | --- | --- |
| `auto` / `hybrid` | 默认融合（读取 `campus.recommend.*` 配置） | 0.50 / 0.35 / 0.15 |
| `hot` | 纯热门排序（基线） | 0 / 0 / 1 |
| `content` | 纯内容匹配召回 | 0 / 1 / 0 |
| `cf` | 纯协同过滤召回 | 1 / 0 / 0 |
| `hybrid-cf` | 融合·偏协同 | 0.70 / 0.20 / 0.10 |
| `hybrid-content` | 融合·偏内容 | 0.30 / 0.60 / 0.10 |

```bash
# 未登录：热门冷启动
curl "http://localhost:8080/api/product/recommend?size=8"

# 登录后个性化 + 指定策略（离线实验/AB）
curl "http://localhost:8080/api/product/recommend?size=10&strategy=hybrid-content" -H "Authorization: Bearer $TOKEN"
```

```json
{ "code": 200, "message": "success",
  "data": { "strategy": "auto", "strategyLabel": "个性化推荐（自动权重）", "personalized": true,
            "profileDesc": "常看：键盘鼠标、手机；价位 ￥15~129",
            "basis": ["在售候选商品 12 件", "过滤条件：排除自己发布 8 件、已收藏 3 件、已下单 1 件",
                      "行为样本 7 条（浏览 1 / 收藏 3 / 私信 2 / 下单 1）",
                      "协同过滤：用户交互过 4 件商品，命中相似物品对 14 组",
                      "融合权重：协同过滤 0.50 / 内容匹配 0.35 / 热门度 0.15"],
            "total": 2,
            "items": [ { "productId": 13, "title": "珀莱雅精华水 余量九成", "price": 78.00,
                         "score": 0.443, "cfScore": 0.0, "contentScore": 0.92, "hotScore": 0.804,
                         "sourceType": 2, "sourceLabel": "内容匹配",
                         "reason": "你常看「护肤」分类",
                         "reasons": ["内容匹配：分类 / 价格偏好命中", "最近 3 天新发布"] } ] } }
```

> 排序规则：三路得分各自 min-max 归一化后按权重加权求和；
> 仅浏览过未收藏/未下单的商品得分乘 0.55；同一分类最多 3 条（多样性）；结果按 `score` 降序。
> 错误码：`400` size 或 strategy 非法（`size 必须为 1~30 之间的整数`）。

#### 相似商品（详情页相关推荐）

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/product/similar/{id}` |
| 鉴权 | 否 |
| 查询参数 | `size`（1~30，默认 6） |
| 返回 | 同 `RecommendResultVO`，`strategy=similar`；`items[]` 的 `sourceLabel` 为「协同过滤」或「同类目热门」 |
| 说明 | 基于物品共现相似度 `co(i,j)/sqrt(pop(i)×pop(j))`；相似物品不足时用**同父分类**（兄弟分类）热门商品补齐；不推荐当前商品与已下架/售出商品 |
| 错误码 | `3001` 商品不存在 |

---

## 四、分类模块

### 4.1 分类列表

| 项 | 值 |
| --- | --- |
| 方法/路径 | `GET /api/category/list` |
| 鉴权 | 否 |
| 查询参数 | `onlyTop`（可选，true 只返回一级分类） |
| 返回 | `[{ id, parentId, name, icon, sortOrder }]`（`parentId=0` 为一级分类） |
| 别名 | `GET /api/categories?parentId=`（v0.03 脚手架接口，仍可用） |

### 4.2 新增分类（管理员）

| 项 | 值 |
| --- | --- |
| 方法/路径 | `POST /api/category/add` |
| 鉴权 | **管理员**（普通学生返回 403） |
| 请求体 | `name`(2~50，**必填**)、`parentId`(0=一级)、`icon`、`sortOrder`、`status`(1/0) |
| 错误码 | 同级重名 7001；上级不存在 7003；名称非法 400 |

### 4.3 修改分类（管理员）

| 项 | 值 |
| --- | --- |
| 方法/路径 | `PUT /api/category/update` |
| 鉴权 | **管理员** |
| 请求体 | `id`(**必填**)、`name`(可选，不传保持原名)、`parentId`、`icon`、`sortOrder`、`status` |
| 错误码 | 分类不存在 7002；同级重名 7001；上级不存在 7003；上级设为自己 7004 |

---

## 五、收藏模块

| 方法/路径 | 鉴权 | 参数 | 说明 |
| --- | --- | --- | --- |
| `POST /api/favorite/operate` | 是 | `productId`(必填)、`type`(1 收藏默认 / 2 取消) | 返回 `{ productId, favorited, favoriteCount }` |
| `GET /api/favorite/list` | 是 | `page`、`size` | 我的收藏分页，**只返回上架商品** |
| `GET /api/favorite/hasFavorite` | 是 | `productId` | 返回 `{ productId, favorited, favoriteCount }` |

```bash
curl -X POST http://localhost:8080/api/favorite/operate -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"productId":8}'
# {"code":200,"message":"收藏成功","data":{"productId":8,"favorited":true,"favoriteCount":1}}
```

| 错误码 | 场景 |
| --- | --- |
| 3001 | 商品不存在 |
| 4001 | 重复收藏（数据库 `uk_user_product` 唯一索引兜底） |
| 4002 | 收藏自己发布的商品 |
| 4003 | 取消未收藏的商品 |

> 收藏列表项的字段：`favoriteId、favoriteTime、productId、title、price、originalPrice、coverImage、categoryName、conditionLevel、campus、productStatus、sellerId、sellerNickname、viewCount、productCreateTime`。

---

## 六、消息模块（私信）

| 方法/路径 | 鉴权 | 参数 | 说明 |
| --- | --- | --- | --- |
| `POST /api/message/send` | 是 | `toUserId`(必填)、`content`(必填 ≤1000)、`productId`(可选) | 发送私信，返回消息对象 |
| `GET /api/message/conversationList` | 是 | - | 会话列表：`peerId、peerNickname、peerAvatar、peerCampus、lastMessage、lastMessageTime、lastFromMe、productId、productTitle、unreadCount` |
| `GET /api/message/history` | 是 | `peerId`(必填)、`page`、`size` | 与某人的聊天记录，**时间倒序（最新在前）** |
| `PUT /api/message/read` | 是 | `peerId` 或 `messageIds[]` | 标记已读，返回实际更新条数；只能标记"我收到的"消息 |
| `GET /api/message/peer` | 是 | `peerId` | 聊天对象公开信息：`userId、nickname、avatar、campus、creditScore、self` |

```bash
curl -X POST http://localhost:8080/api/message/send -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"toUserId":1,"content":"你好，这件商品还在吗？","productId":8}'
```

| 错误码 | 场景 |
| --- | --- |
| 400 | 内容为空、超长；`read` 未传 peerId/messageIds；分页越界；缺 `peerId` |
| 5001 | 不能给自己发送消息 |
| 5002 | 接收人不存在 |
| 3001 | 关联的商品不存在 |

---

## 七、订单模块

### 7.1 创建订单

| 项 | 值 |
| --- | --- |
| 方法/路径 | `POST /api/order/create` |
| 鉴权 | **是**（买家 = 当前登录用户） |
| 请求体 | `productId`(**必填**)、`deliveryType`(1 面交默认 / 2 快递)、`tradePlace`、`buyerRemark` |
| 返回 | `OrderDetailVO` |

```bash
curl -X POST http://localhost:8080/api/order/create -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId":8,"tradePlace":"东校区图书馆门口","buyerRemark":"明天下午三点"}'
```

| 错误码 | 场景 |
| --- | --- |
| 3001 | 商品不存在 |
| 6001 | 商品已下架或已被预订（含被别人抢先下单，防一物多卖） |
| 6002 | 不能购买自己发布的商品 |

### 7.2 修改订单状态

| 项 | 值 |
| --- | --- |
| 方法/路径 | `PUT /api/order/status` |
| 鉴权 | **是**（仅买卖双方） |
| 请求体 | `orderId`(必填)、`status`(**3 已完成 / 4 已取消**)、`cancelReason`(取消时可选) |
| 副作用 | 完成 → 商品「已售出」+ 双方信用分 +1；取消 → 商品回到「在售」 |

| 错误码 | 场景 |
| --- | --- |
| 400 | `status` 不是 3/4（待交易 0 是初始状态，不需要设置） |
| 6003 / 6004 / 6005 | 订单不存在 / 无权操作 / 状态不允许 |

### 7.3 订单列表与详情

| 方法/路径 | 鉴权 | 参数 | 说明 |
| --- | --- | --- | --- |
| `GET /api/order/buyList` | 是 | `status`(可选)、`page`、`size` | 我买到的订单 |
| `GET /api/order/sellList` | 是 | `status`(可选)、`page`、`size` | 我卖出的订单 |
| `GET /api/order/{id}` | 是 | - | 订单详情，**仅买卖双方可查看**（其他人 6004） |

订单列表项字段：`id、orderNo、productId、productTitle、productImage、amount、status、statusLabel、buyerId、buyerNickname、sellerId、sellerNickname、counterpartNickname、myRole(buyer/seller)、createTime、finishTime、cancelTime`；
详情另含：`deliveryType、tradePlace、buyerRemark、cancelReason、payTime、deliverTime、updateTime、productStatus、productStatusLabel、buyerAvatar、buyerCampus、buyerCreditScore、sellerAvatar、sellerCampus、sellerCreditScore、operable`。

### 7.4 订单状态说明

| 状态码 | 名称 | 说明 |
| --- | --- | --- |
| 0 | 待交易 | 下单后的初始状态 |
| 3 | 已完成 | 商品标记已售出，双方信用分 +1 |
| 4 | 已取消 | 商品回到在售 |
| 1 / 2 / 5 | 待交付 / 待收货 / 已退款 | **预留状态**（后续"支付与物流"里程碑使用，当前不会产生） |

---

## 八、其他接口

| 方法/路径 | 鉴权 | 说明 |
| --- | --- | --- |
| `GET /api/health` | 否 | 服务存活检查，返回应用名、版本、环境、JDK 版本、服务端时间 |
| `GET /api/health/db` | 否 | 数据库连通性检查，返回用户/分类/商品数量（开发诊断用） |
| `POST /api/auth/login`、`POST /api/auth/logout` | 否 | 脚手架预留路径，等价于 `/api/user/login`、`/api/user/logout` |

---

## 九、接口清单速查（共 39 个）

| # | 方法 | 路径 | 鉴权 |
| --- | --- | --- | --- |
| 1 | POST | `/api/user/register` | 否 |
| 2 | POST | `/api/user/login` | 否 |
| 3 | POST | `/api/user/logout` | 是 |
| 4 | GET | `/api/user/info` | 是 |
| 5 | GET | `/api/user/profile` | 是 |
| 6 | PUT | `/api/user/update` | 是 |
| 7 | POST | `/api/user/avatar` | 是 |
| 8 | POST | `/api/auth/login` | 否 |
| 9 | POST | `/api/auth/logout` | 否 |
| 10 | POST | `/api/product/publish` | 是 |
| 11 | POST | `/api/product/upload` | 是 |
| 12 | GET | `/api/product/list` | 否 |
| 13 | GET | `/api/product/search` | 否 |
| 14 | GET | `/api/product/mine` | 是 |
| 15 | GET | `/api/product/{id}` | 否（待审核商品仅本人/管理员） |
| 16 | PUT | `/api/product/status` | 是 |
| 17 | GET | `/api/products` | 否（脚手架演示） |
| 18 | GET | `/api/products/{id}` | 否（脚手架演示） |
| 19 | GET | `/api/product/recommend` | 否（**v0.11 猜你喜欢**，带 Token 则个性化） |
| 20 | GET | `/api/product/similar/{id}` | 否（**v0.11 相似商品**） |
| 21 | GET | `/api/category/list` | 否 |
| 22 | GET | `/api/categories` | 否（脚手架演示） |
| 23 | POST | `/api/category/add` | **管理员** |
| 24 | PUT | `/api/category/update` | **管理员** |
| 25 | POST | `/api/favorite/operate` | 是 |
| 26 | GET | `/api/favorite/list` | 是 |
| 27 | GET | `/api/favorite/hasFavorite` | 是 |
| 28 | POST | `/api/message/send` | 是 |
| 29 | GET | `/api/message/conversationList` | 是 |
| 30 | GET | `/api/message/history` | 是 |
| 31 | PUT | `/api/message/read` | 是 |
| 32 | GET | `/api/message/peer` | 是 |
| 33 | POST | `/api/order/create` | 是 |
| 34 | PUT | `/api/order/status` | 是 |
| 35 | GET | `/api/order/buyList` | 是 |
| 36 | GET | `/api/order/sellList` | 是 |
| 37 | GET | `/api/order/{id}` | 是（仅买卖双方） |
| 38 | GET | `/api/health` | 否 |
| 39 | GET | `/api/health/db` | 否 |

> 前端调用说明：所有请求经 `frontend/src/api/*.js` 封装，`request.js` 统一注入 Token、
> 统一处理 `code != 200` 的错误提示，并在 401 时清理登录态跳转登录页。
