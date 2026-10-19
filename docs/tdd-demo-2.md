# TDD 实践记录（第二轮）：订单状态机

> 这是本项目**第二次**完整的 TDD 循环。第一次见 [`tdd-demo.md`](tdd-demo.md)（发布表单校验规则）。
>
> 两轮的区别很关键：
> - 第一轮是在**已上线的旧代码**上做（把隐式规则固化为测试），严格说不算真 TDD；
> - **本轮是全新功能**（订单 1/2/5 三个"死状态"接入状态机），测试可以**先写**，是真 TDD。

## 一、为什么选这件事

数据库 `orders.status` 定义了 6 态，但业务此前只实现 `0→3`（完成）/ `0→4`（取消）：
**1 待交付 / 2 待收货 / 5 已退款 是"定义了但永不产生"的死状态**。
`OrderStatus` 里它们的名字甚至就叫 `RESERVED_*`（预留），`isActive()` 也不认它们。

这件事在 [`答辩演示脚本.md`](答辩演示脚本.md) 里被写成"局限"：*订单四态与双向评价按计划留到中期检查后补齐*。
双向评价已完成，订单四态一直空着 —— 正好是一个**边界清晰、行为可观察**的新功能，适合严格 TDD。

## 二、第 0 步：先把"规格"用测试写出来（不写实现）

新增 `OrderStatusFlowTest`，把三条规则写成可执行断言：

| 规则 | 为什么这样定 |
| --- | --- |
| 允许：`0→1`、`0→4`、`1→2`、`1→5`、`2→3`、`2→5` | 线下见面前后的真实节奏 |
| 禁止：`0→2`、`1→3`、`1→4`、`2→4`、`2→1`、`3/4/5→*`、自流转、未知码 | 不能跳步、不能回退、终态不可复活 |
| 谁能推进：1/2 → 卖家；4/5 → 双方 | 交付动作由卖家掌握 |
| 商品联动：1/2 → 交易中；3 → 已售出；4/5 → 回到在售 | 与既有 `changeStatus` 的联动保持一致 |

同时只写一个**空实现骨架**（`OrderStatusFlow` 的每个方法返回 `false` / `""`），
目的是让测试**能编译、能运行、并且真的失败** —— 断言失败才是有效的红，编译失败不算。

## 三、第 1 步　红：41 条用例，24 条失败

```
$ cd backend && ./mvnw -B test -Dtest=OrderStatusFlowTest

[ERROR]   OrderStatusFlowTest$Actor.sellerOnly:105 卖家可以标记可交付 ==> expected: <true> but was: <false>
[ERROR]   OrderStatusFlowTest$Actor.finishAllowedForBoth:118 买家可以确认完成 ==> expected: <true> but was: <false>
[ERROR]   OrderStatusFlowTest$ProductSync.productStatusAfter:169 推进到 1 的商品状态 ==> expected: <TRADING> but was: <>
[ERROR]   OrderStatusFlowTest$Terminal.terminal:114 3 应是终态 ==> expected: <true> but was: <false>
[ERROR]   OrderStatusFlowTest$Labels.labels:191 0→1 应有文案 ==> expected: <false> but was: <true>
[ERROR] Tests run: 41, Failures: 24, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

17 条"禁止流转"的用例一开始就是绿的（因为空实现返回 false，恰好等于"拒绝"）——
这恰好说明**只断言"被拒绝"是不够的**，必须同时有"被允许"的正向用例，否则空实现也能骗过测试。

## 四、第 2 步　绿：41/41

```
$ ./mvnw -B test -Dtest=OrderStatusFlowTest
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

实现要点：一张 `Map<Integer, Set<Integer>> ALLOWED` 流转表 + `actorAllowed` 角色判定 +
`productStatusAfter` 语义映射 + `actionLabel` 文案，全是纯函数，无依赖。

## 五、第 3 步　接入业务：★ 全量测试暴露了我**两个需求理解错误**

把状态机接进 `OrderModuleServiceImpl.changeStatus` 后跑全量：`708 条，1 失败 5 错误`。
两次都不是实现 bug，而是**我写测试时的假设与既有需求冲突** —— 这正是 TDD 最有价值的地方。

### 冲突 1：我以为"确认收货只能买家"

我把它写进了测试（`buyerOnly`）。但全量测试立刻撞了两堵墙：

- 既有单测 `OrderModuleServiceImplTest.sellerCanFinish`，标题写着**"卖家也能确认完成（买卖双方权限对等）"**；
- 用户故事 `US-ORDER-03` AC1：*"作为**卖家**，我希望在完成线下交付后确认交易完成"*。

**既有需求是权威** —— 我改的是规则，不是需求：

```java
// 确认完成：与 US-ORDER-03 的既有需求一致（线下交付后买卖双方都可确认完成）
case FINISHED:
    return true;
```

### 冲突 2：我把 `0→3` 禁掉了

我的状态机只允许 `0→1→2→3` 这条"分阶段"路径，于是 5 条完成订单的测试全部报错。
但 `US-ORDER-03` AC1 的另一半就写着：*"Given 订单处于**待交易** / When 卖家确认完成 / Then 订单变为已完成"* ——
**线下当面交易本来就该能一步完成**。

修法：`PENDING → {DELIVERING, FINISHED, CANCELLED}`，两条路径都合法，并补上按钮文案"确认完成"。

> 这两个冲突值得单独记住：**先写测试时，红的是"我对需求的理解"，不是"代码实现"。**
> 如果先写实现再补测试，测试会被写成"实现现在的样子"，这两个理解偏差就永远不会被发现。

## 六、第 4 步　重构：service 只管编排

重构前后 `changeStatus` 的分工：

| 重构前 | 重构后 |
| --- | --- |
| 目标状态白名单硬编码在 service（`if (!toFinish && !toCancel) throw`） | `OrderStatusFlow` 流转表 |
| "只有待交易能流转"硬编码 | `canTransit(from, target)` |
| 谁能不能操作：没有校验（谁都行） | `actorAllowed(target, isSeller)` |
| 商品状态：`if/else` 两分支 | `productStatusAfter(target)` 三语义 |

`OrderStatus` 里的 `RESERVED_DELIVERING/RECEIVING/REFUNDED` 也正式改名为
`DELIVERING/RECEIVING/REFUNDED`，`isActive()` 从"只认 0/3/4"改为认 0~5 —— 死状态有了名字。

## 七、第 5 步　真打接口验证（不只是单测）

订单 4（买家 `stu_demo` / 卖家 `admin`，初始"待交易"），逐条点：

| 操作 | 期望 | 实测 |
| --- | --- | --- |
| 买家推进到 1 待交付 | 6004 越权（交付由卖家发起） | **6004** ✅ |
| 卖家推进到 1 待交付 | 200 | **200，状态=待交付** ✅ |
| 卖家推进到 2 待收货 | 200 | **200，状态=待收货** ✅ |
| 买家确认完成 3 | 200，商品变已售出 | **200，状态=已完成，商品=已售出** ✅ |
| 已完成后再退款 5 | 6005 状态非法（终态不可复活） | **6005** ✅ |

跑完恢复演示数据（评价表清空 + `seed-demo-data.py --reset`），不变量全绿。

## 八、质量数据

```
后端单测：665 → 708 条（新增 43 条：状态机 41 + 越权/推进 2）
全量：708/708 通过
覆盖率：86.06% → 85.94%（↓0.12）
  原因：新增 OrderStatusFlow（含 targetsOf 等未被直接断言的辅助方法），
        分母涨得比分子多；棘轮阈值 0.83 未变、仍通过。
  如实记在这里，不粉饰。
接口实测：5 步全部符合预期；前端按钮尚未接（1/2/5 目前只能 API 驱动）
```

## 九、这一轮和"事后补测试"到底差在哪

| 维度 | 本轮（TDD） | 项目里其余部分（事后补测） |
| --- | --- | --- |
| 测试先于实现 | ✅ 测试文件先写、**真的红了 24 条** | ❌ git 历史里 35/38 对是"实现先" |
| 红的价值 | ✅ 两次红都暴露了**我对需求的理解错误** | ❌ 测试恒绿，无法区分"断言有效"和"断言写空" |
| 设计影响 | ✅ 状态机被逼成纯函数（因为要能被测试直接调用） | ❌ 逻辑散在 service 的 if/else 里 |
| 局限 | 只覆盖后端；前端按钮未接；退款未做真实接口验证 | —— |

**结论**：这一轮可以作为"我们在用 TDD"的证据，但**不能说整个项目是 TDD** ——
其余模块仍是"实现先行 + 事后补测"，这一点在答辩时应如实说明。
