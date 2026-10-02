# 校园二手交易平台 功能测试用例（v0.10）

| 项目 | 说明 |
| --- | --- |
| 被测系统 | 校园二手交易平台（Spring Boot 3 + Vue 3 + MySQL 8） |
| 测试版本 | v0.10（覆盖 v0.03~v0.09 全部功能） |
| 测试类型 | 功能测试（正常/异常/边界/越权）+ 冒烟测试 + 安全专项 |
| 测试环境 | Windows 11 + OpenJDK 17.0.2 + MySQL 8.4.4 + Node 24 + Chrome/Edge |
| 测试接口 | 后端 37 个 REST 接口（http://localhost:8080/api） |
| 测试方式 | 自动化接口测试脚本 + 浏览器手工验证 |
| 用例总数 | **127**（冒烟 14 + 功能 86 + 越权专项 27） |
| 执行结果 | 冒烟 **14/14**、功能 **86/86**、越权专项 **27/27**，全部通过 |

## 一、冒烟测试（完整业务闭环）

验证主流程能否从零跑通：注册 → 登录 → 发布商品 → 收藏 → 私聊 → 下单 → 完成。

| 序号 | 测试步骤 | 输入/操作 | 预期结果 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- | :---: |
| S1 | 注册新用户 qabfc32f | `POST /api/user/register  {username: qa******, password: qa123456, nickname: 冒烟用户***}` | 接口返回成功 | code=200 注册成功 | ✅ 通过 |
| S2 | 新账号登录获取 Token | `POST /api/user/login  {username: 新账号, password: qa123456}` | 接口返回成功 | Token 长度=196 | ✅ 通过 |
| S3 | 卖家发布待售商品 | `POST /api/product/publish（admin）{title:【冒烟】管理员商品, categoryId:8, price:88, conditionLevel:2}` | 接口返回成功 | 商品ID=30 | ✅ 通过 |
| S4 | 新用户发布商品（含图片） | `POST /api/product/publish（新账号）{title:【冒烟】xx 的闲置, imageUrls:[/demo-images/default.png]}` | 接口返回成功 | 商品ID=31 | ✅ 通过 |
| S5 | 商品详情可访问且卖家正确 | `GET /api/product/{新商品ID}` | 接口返回成功 | 卖家=冒烟用户32f | ✅ 通过 |
| S6 | 收藏卖家商品 | `POST /api/favorite/operate  {productId: 卖家的商品ID}` | 接口返回成功 | code=200 收藏成功 | ✅ 通过 |
| S7 | 收藏列表可见 | `GET /api/favorite/list?page=1&size=10` | 接口返回成功 | 收藏数=1 | ✅ 通过 |
| S8 | 私聊卖家 | `POST /api/message/send  {toUserId:1, content:你好，这件商品还在吗？, productId: 卖家商品ID}` | 接口返回成功 | code=200 发送成功 | ✅ 通过 |
| S9 | 会话列表出现会话 | `GET /api/message/conversationList` | 接口返回成功 | 会话数=1 | ✅ 通过 |
| S10 | 标记会话已读 | `PUT /api/message/read  {peerId:1}` | 接口返回成功 | code=200 已标记 0 条消息为已读 | ✅ 通过 |
| S11 | 下单购买 | `POST /api/order/create  {productId: 卖家商品ID, tradePlace:主校区南门, buyerRemark:冒烟下单}` | 接口返回成功 | 订单ID=13 | ✅ 通过 |
| S12 | 订单详情（买家视角） | `GET /api/order/{订单ID}（买家 Token）` | 接口返回成功 | 状态=待交易 商品=交易中 | ✅ 通过 |
| S13 | 卖家确认完成 | `PUT /api/order/status（卖家 Token）{orderId: 订单ID, status: 3}` | 接口返回成功 | code=200 交易已完成 | ✅ 通过 |
| S14 | 订单出现在「我买到的」 | `GET /api/order/buyList?page=1&size=50（买家 Token）` | 接口返回成功 | 订单总数=1 | ✅ 通过 |

> 结论：冒烟测试 14/14 通过，完整业务闭环可正常跑通。

## 二、功能测试用例

说明：`预期结果` 中的 code 为后端统一响应体里的业务状态码（HTTP 状态码统一为 200）。

### 2.1 用户/认证（19 条）

| 用例编号 | 测试场景 | 输入/前置条件 | 预期结果 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- | :---: |
| TC-U01 | 正常注册新用户 | username=qae0611e | code=200 | code=200 注册成功 | ✅ 通过 |
| TC-U02 | 重复用户名注册 | username=qae0611e（再次提交） | code=2001 | code=2001 用户名已被注册，请更换后重试 | ✅ 通过 |
| TC-U03 | 注册参数全为空 | username/password/nickname 均为空 | code=400 | code=400 密码长度必须为 6~20 个字符；昵称长度必须为 2~20 个字符；用户名不 | ✅ 通过 |
| TC-U04 | 密码强度不足 | password=abcdef（无数字） | code=400 | code=400 密码必须同时包含字母和数字 | ✅ 通过 |
| TC-U05 | 正常登录返回 Token | username=qae0611e | code=200 | code=200 登录成功 | ✅ 通过 |
| TC-U06 | 密码错误 | password=wrong123 | code=2003 | code=2003 密码错误，请重新输入 | ✅ 通过 |
| TC-U07 | 账号不存在 | username=not_exist_999 | code=2002 | code=2002 账号不存在，请检查用户名 | ✅ 通过 |
| TC-U08 | 未登录访问个人中心 | GET /api/user/info 无 Token | code=401 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-U09 | 有效 Token 访问个人中心 | GET /api/user/info + Token | code=200 | code=200 success | ✅ 通过 |
| TC-U10 | 伪造 Token | token=abc.def.ghi | code=401 | code=401 Token 无效，请重新登录 | ✅ 通过 |
| TC-U11 | 使用过期 Token | exp 为 1 小时前的 JWT | code=401 | code=401 登录已过期，请重新登录 | ✅ 通过 |
| TC-U12 | 查询完整资料 | GET /api/user/profile | code=200 | code=200 success | ✅ 通过 |
| TC-U13 | 修改个人资料 | nickname/campus/gender/email | code=200 | code=200 资料已更新 | ✅ 通过 |
| TC-U14 | 手机号被他人占用 | phone=13800000099（stu_demo 已用） | code=2006 | code=2006 该手机号已被其他账号绑定 | ✅ 通过 |
| TC-U15 | 手机号格式错误 | phone=123 | code=400 | code=400 手机号格式不正确 | ✅ 通过 |
| TC-U16 | 上传头像（png） | multipart file=up1.png | code=200 | code=200 头像已更新 | ✅ 通过 |
| TC-U17 | 上传非图片文件 | multipart file=bad.txt | code=3006 | code=3006 图片格式不支持，仅支持 jpg / jpeg / png / gif / w | ✅ 通过 |
| TC-U18 | 上传超大图片（6MB） | multipart file=big.png | code=3007 | code=3007 图片大小超出限制（单张最大 5MB） | ✅ 通过 |
| TC-U19 | 未登录上传头像 | POST /api/user/avatar 无 Token | code=401 | code=401 未登录，请先登录 | ✅ 通过 |

### 2.2 商品（19 条）

| 用例编号 | 测试场景 | 输入/前置条件 | 预期结果 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- | :---: |
| TC-P01 | 未登录发布商品 | POST /api/product/publish 无 Token | code=401 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-P02 | 正常发布商品 | 完整参数 | code=200 | code=200 发布成功 | ✅ 通过 |
| TC-P03 | 发布参数非法 | title 为空 / price=-5 / conditionLevel=9 | code=400 | code=400 商品名称不能为空；请选择商品分类；商品名称长度必须为 2~100 个字符；售 | ✅ 通过 |
| TC-P04 | 分类不存在 | categoryId=999999 | code=3004 | code=3004 商品分类不存在，请重新选择分类 | ✅ 通过 |
| TC-P05 | 商品列表只返回上架商品 | GET /api/product/list | code=200 | code=200 success | ✅ 通过 |
| TC-P06 | 查看商品详情 | GET /api/product/32 | code=200 | code=200 success | ✅ 通过 |
| TC-P07 | 查看不存在的商品 | GET /api/product/999999 | code=3001 | code=3001 商品不存在或已被删除 | ✅ 通过 |
| TC-P08 | 图片多张上传 | multipart files=up1.png | code=200 | code=200 上传成功 | ✅ 通过 |
| TC-P09 | 上传非法格式 | multipart file=bad.txt | code=3006 | code=3006 图片格式不支持，仅支持 jpg / jpeg / png / gif / w | ✅ 通过 |
| TC-P10 | 查询我的商品 | GET /api/product/mine | code=200 | code=200 success | ✅ 通过 |
| TC-P11 | 下架自己的商品 | productId=32, status=3 | code=200 | code=200 下架成功 | ✅ 通过 |
| TC-P12 | 下架他人发布的商品 | qa2 下架 admin 的商品 32 | code=3002 | code=3002 无权操作他人发布的商品 | ✅ 通过 |
| TC-P13 | 上下架状态值非法 | status=2 | code=400 | code=400 参数不合法：status 只能为 1（上架）或 3（下架） | ✅ 通过 |
| TC-P14 | 按名称模糊搜索 | keyword=教材 | code=200 | code=200 success | ✅ 通过 |
| TC-P15 | 按分类筛选（含子分类） | categoryId=1 | code=200 | code=200 success | ✅ 通过 |
| TC-P16 | 搜索分页参数非法 | size=999 | code=400 | code=400 每页条数不能超过 100 | ✅ 通过 |
| TC-P17 | 脚手架演示接口不泄漏非上架商品（BUG-01 回归） | GET /api/products | code=200 | code=200 success | ✅ 通过 |
| TC-P18 | 匿名查看待审核商品详情（BUG-02 回归） | GET /api/product/35 无 Token | code=3001 | code=3001 商品不存在或已被删除 | ✅ 通过 |
| TC-P19 | 卖家本人可查看自己的待审核商品 | GET /api/product/35 + 卖家 Token | code=200 | code=200 success | ✅ 通过 |

### 2.3 收藏（11 条）

| 用例编号 | 测试场景 | 输入/前置条件 | 预期结果 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- | :---: |
| TC-F01 | 未登录收藏 | POST /api/favorite/operate 无 Token | code=401 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-F02 | 正常收藏他人商品 | productId=32 | code=200 | code=200 收藏成功 | ✅ 通过 |
| TC-F03 | 重复收藏 | productId=32 第二次 | code=4001 | code=4001 已收藏该商品，请勿重复收藏 | ✅ 通过 |
| TC-F04 | 收藏自己发布的商品 | productId=33 | code=4002 | code=4002 不能收藏自己发布的商品 | ✅ 通过 |
| TC-F05 | 收藏不存在的商品 | productId=999999 | code=3001 | code=3001 商品不存在或已被删除 | ✅ 通过 |
| TC-F06 | 查询是否已收藏 | productId=32 | code=200 | code=200 success | ✅ 通过 |
| TC-F07 | 取消收藏 | productId=32, type=2 | code=200 | code=200 已取消收藏 | ✅ 通过 |
| TC-F08 | 取消未收藏的商品 | type=2 第二次 | code=4003 | code=4003 尚未收藏该商品，无法取消 | ✅ 通过 |
| TC-F09 | 我的收藏只含上架商品 | GET /api/favorite/list | code=200 | code=200 success | ✅ 通过 |
| TC-F10 | 不同用户收藏列表互不可见 | 比对 stu_test01 与 qa2 的收藏 | code=200 | code=200 交集=[] | ✅ 通过 |
| TC-F11 | 收藏列表分页非法 | page=0 | code=400 | code=400 参数不合法：page 必须大于 0，size 必须为 1~100 | ✅ 通过 |

### 2.4 消息（12 条）

| 用例编号 | 测试场景 | 输入/前置条件 | 预期结果 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- | :---: |
| TC-M01 | 未登录发送私信 | POST /api/message/send 无 Token | code=401 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-M02 | 给自己发消息 | toUserId=18（自己） | code=5001 | code=5001 不能给自己发送消息 | ✅ 通过 |
| TC-M03 | 接收人不存在 | toUserId=999999 | code=5002 | code=5002 接收人不存在 | ✅ 通过 |
| TC-M04 | 消息内容为空 | content="" | code=400 | code=400 消息内容不能为空 | ✅ 通过 |
| TC-M05 | 正常发送私信 | toUserId=1 + productId | code=200 | code=200 发送成功 | ✅ 通过 |
| TC-M06 | 会话列表 | GET /api/message/conversationList | code=200 | code=200 success | ✅ 通过 |
| TC-M07 | 聊天记录分页 | peerId=1, page=1, size=10 | code=200 | code=200 success | ✅ 通过 |
| TC-M08 | 标记会话已读 | peerId=1 | code=200 | code=200 已标记 0 条消息为已读 | ✅ 通过 |
| TC-M09 | 标记"他人收到的消息"为已读 | messageIds=[19] | code=200 | code=200 已标记 0 条消息为已读 | ✅ 通过 |
| TC-M10 | 查询与自己无关的会话 | peerId=999999 | code=200 | code=200 success | ✅ 通过 |
| TC-M11 | 聊天记录分页非法 | size=999 | code=400 | code=400 参数不合法：page 必须大于 0，size 必须为 1~100 | ✅ 通过 |
| TC-M12 | 缺少必填参数 peerId | GET /api/message/history | code=400 | code=400 缺少必填参数：peerId | ✅ 通过 |

### 2.5 订单（16 条）

| 用例编号 | 测试场景 | 输入/前置条件 | 预期结果 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- | :---: |
| TC-O01 | 未登录下单 | POST /api/order/create 无 Token | code=401 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-O02 | 下单商品不存在 | productId=999999 | code=3001 | code=3001 商品不存在或已被删除 | ✅ 通过 |
| TC-O03 | 购买自己发布的商品 | productId=33 | code=6002 | code=6002 不能购买自己发布的商品 | ✅ 通过 |
| TC-O05 | 正常下单 | productId=32 | code=200 | code=200 下单成功，请与卖家约定面交时间地点 | ✅ 通过 |
| TC-O06 | 重复下单同一商品 | 第二人下单已锁定商品 | code=6001 | code=6001 商品已下架或已被预订，无法下单 | ✅ 通过 |
| TC-O07 | 我买到的订单列表 | GET /api/order/buyList | code=200 | code=200 success | ✅ 通过 |
| TC-O08 | 我卖出的订单列表 | GET /api/order/sellList（admin） | code=200 | code=200 success | ✅ 通过 |
| TC-O09 | 订单详情（买家） | GET /api/order/14 | code=200 | code=200 success | ✅ 通过 |
| TC-O10 | 第三方查看订单详情 | stu 查看 qa2 的订单 14 | code=6004 | code=6004 无权查看或操作该订单 | ✅ 通过 |
| TC-O11 | 第三方修改订单状态 | stu 完成 qa2 的订单 | code=6004 | code=6004 无权查看或操作该订单 | ✅ 通过 |
| TC-O12 | 状态值非法 | status=0 | code=400 | code=400 参数不合法：status 只能为 3（已完成）或 4（已取消）；待交易(0) | ✅ 通过 |
| TC-O13 | 订单不存在 | orderId=999999 | code=6003 | code=6003 订单不存在 | ✅ 通过 |
| TC-O14 | 买家确认完成 | orderId=14, status=3 | code=200 | code=200 交易已完成 | ✅ 通过 |
| TC-O15 | 已完成订单再次操作 | status=4 | code=6005 | code=6005 订单当前状态不允许该操作 | ✅ 通过 |
| TC-O16 | 取消订单后商品回到在售 | orderId=15 取消后查询商品状态 | code=200 | code=200 商品状态=1（1=在售） | ✅ 通过 |
| TC-O17 | 订单列表分页非法 | page=0 | code=400 | code=400 参数不合法：page 必须大于 0，size 必须为 1~100 | ✅ 通过 |

### 2.6 辅助/分类（9 条）

| 用例编号 | 测试场景 | 输入/前置条件 | 预期结果 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- | :---: |
| TC-A01 | 分类列表查询 | GET /api/category/list | code=200 | code=200 success | ✅ 通过 |
| TC-A02 | 未登录新增分类 | POST /api/category/add 无 Token | code=401 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-A03 | 普通学生新增分类 | POST /api/category/add + 学生 Token | code=403 | code=403 没有操作权限 | ✅ 通过 |
| TC-A04 | 管理员新增分类 | name=QA分类778b | code=200 | code=200 分类新增成功 | ✅ 通过 |
| TC-A05 | 同级分类重名 | name=QA分类778b 重复 | code=7001 | code=7001 同一上级分类下已存在同名分类 | ✅ 通过 |
| TC-A06 | 上级分类不存在 | parentId=999999 | code=7003 | code=7003 上级分类不存在 | ✅ 通过 |
| TC-A07 | 修改分类 | id=44 改名+排序 | code=200 | code=200 分类修改成功 | ✅ 通过 |
| TC-A08 | 修改不存在的分类 | id=999999 | code=7002 | code=7002 分类不存在 | ✅ 通过 |
| TC-A09 | 上级设为自己 | id=44, parentId=44 | code=7004 | code=7004 不能把分类的上级设置为自己 | ✅ 通过 |

## 三、越权与安全专项

| 用例编号 | 检查项 | 预期结果 | 实际结果 | 结论 |
| --- | --- | --- | --- | :---: |
| TC-SEC-401-1 | GET /user/info | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-2 | GET /user/profile | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-3 | PUT /user/update | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-4 | POST /product/publish | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-5 | GET /product/mine | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-6 | PUT /product/status | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-7 | POST /favorite/operate | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-8 | GET /favorite/list | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-9 | GET /favorite/hasFavorite?productId=1 | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-10 | POST /message/send | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-11 | GET /message/conversationList | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-12 | GET /message/history?peerId=1 | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-13 | PUT /message/read | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-14 | GET /message/peer?peerId=1 | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-15 | POST /order/create | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-16 | PUT /order/status | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-17 | GET /order/buyList | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-18 | GET /order/sellList | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-19 | GET /order/1 | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-20 | POST /category/add | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-401-21 | PUT /category/update | 401 未登录 | code=401 未登录，请先登录 | ✅ 通过 |
| TC-SEC-403-22 | POST /category/add | 403 无权限 | code=403 没有操作权限 | ✅ 通过 |
| TC-SEC-403-23 | PUT /category/update | 403 无权限 | code=403 没有操作权限 | ✅ 通过 |
| TC-SEC-ID-24 | PUT /user/update 伪造 id=1 想改管理员资料 | 只改自己，admin 昵称不变 | code=200 admin: 系统管理员->系统管理员 | ✅ 通过 |
| TC-SEC-IMG-25 | POST /product/upload 向他人商品 32 传图 | 3002 无权限 | code=3002 无权操作他人发布的商品 | ✅ 通过 |
| TC-SEC-PENDING-26 | 待审核商品 35 可见性 | 匿名 3001 / 卖家 200 / 管理员 200 | 匿名=3001 卖家=200 管理员=200 | ✅ 通过 |
| TC-SEC-FAKE-27 | 伪造 Token 访问 4 个受保护接口 | 全部 401 | 4/4 返回 401 | ✅ 通过 |

> 3.1 覆盖了全部需要登录的 21 个接口（未携带 Token 必须返回 401）；
> 3.2 覆盖了 2 个管理员接口（普通学生 Token 必须返回 403）。

## 四、测试结论

| 指标 | 数值 |
| --- | --- |
| 用例总数 | 127 |
| 通过 | 127 |
| 失败 | 0 |
| 通过率 | 100.0% |

测试过程中发现并修复的缺陷见 [test-report.md](./test-report.md)（BUG-01 接口越权泄漏、BUG-02 待审核商品可见性、BUG-03 前端重复请求）。

---

> 测试账号：管理员 `admin/123456`、学生 `stu_test01/abc12345`、演示账号 `stu_demo/123456`；
> 本轮测试自动创建并已在测试后清理的临时账号：`qabfc32f`、`qacf301e` 等（qa 前缀）。

> 复现方式：`python docs/test-suite-v0.10.py`（需要后端 8080 与 MySQL 3306 已启动）