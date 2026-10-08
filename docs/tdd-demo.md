# TDD 实践记录（红 → 绿 → 重构）

> 校园二手交易平台 · 黎钟 · 2027 届软件工程
> 背景：指导老师反馈"测试得用 TDD 的方式"。本文档记录一次**完整、可复现**的 TDD 循环，
> 并说明它在既有代码库上的真实用法与两个额外发现。

## 一、为什么在既有项目上做 TDD

本项目的单元测试原本是"实现完成后补写"（如分类索引用例）。TDD 强调**测试先行**：
先写测试把"期望行为"固定下来 → 运行得到失败（红）→ 写实现让它通过（绿）→ 重构。
在**已上线功能**上做 TDD，还有一个更实际的用法：把页面里散落的隐式规则**固化为测试**（characterization），
再把实现与页面统一到同一份规则上，从而消除"同一套规则写两遍、改一处忘一处"的隐患。

**本次目标**：`frontend/src/utils/productRules.js` —— 发布商品的表单校验规则（纯函数）。
它直接对应需求 **[US-GOODS-01 发布商品](user-stories.md) 的验收标准 AC2（空值拦截）与 AC3（非法价格拦截）**。

## 二、TDD 循环全过程（命令与真实输出）

### 第 0 步　先写测试（此时实现还不存在）

```
新增  tests/unit/product-rules.test.mjs      ← 先写，覆盖标题/售价/分类/描述/图片/归一化/常量
新增  frontend/src/utils/productRules.js     ← 只写「空实现骨架」（函数返回 null / 空对象）
```

### 第 1 步　红：运行测试，确认它真的会失败

```
$ node tests/unit/product-rules.test.mjs

一、标题校验（US-GOODS-01 AC2：必填与长度）
  ✗ 合法表单整体通过
      期望 true
      实际 false
  ✗ 标题为空 → 报错
      期望 "请填写商品名称"
      实际 undefined
  …
结果：通过 34 条，失败 2 条
✗ 单元验证未通过
（退出码 1）
```

> ★ 红阶段的意义：证明测试**确实能捕捉问题**。若一上来就是绿的，说明测试没测到东西。

### 第 2 步　绿：写实现，让测试通过

```
$ node tests/unit/product-rules.test.mjs
…
结果：通过 36 条，失败 0 条
✓ 单元验证通过
（退出码 0）
```

> 绿阶段出现了 2 条失败（`firstError` 返回的是字段名 `"title"`，而测试要求返回**可展示的提示文案**）——
> 按 TDD 的规则**改实现而不是改测试**：`firstError = errors[firstKey]`，于是全部通过。

### 第 3 步　重构：消除重复，行为不变

把重复出现的价格范围文案抽成单一常量：

```js
const PRICE_RANGE_TEXT = `价格需在 ${PRODUCT_RULES.PRICE_MIN} ~ ${PRODUCT_RULES.PRICE_MAX} 之间`
```

```
$ node tests/unit/product-rules.test.mjs
结果：通过 36 条，失败 0 条      ← 重构没有破坏任何行为 ✓
```

## 三、过程中的两个真实发现（TDD 的价值就在这里）

### 发现 1　我假定的阈值与页面既有规则不一致 ★

首轮测试通过后，准备把规则接入页面时对照 `views/product/Publish.vue`，发现两边对不上：

| 规则项 | 我最初假定的 | 页面既有行为（权威） | 处理 |
| --- | --- | --- | --- |
| 标题长度 | 2 ~ 60 | **2 ~ 100** | 以页面为准 |
| 描述长度 | ≤ 500 | **≤ 2000** | 以页面为准 |
| 售价上限 | 999999.99 | **8 位整数 + 2 位小数** | 以页面为准（改用正则） |
| 商品图片 | **必填** 至少 1 张 | **选填**（不传图自动用占位图） | 以页面为准（必填规则作废） |
| 提示文案 | 自拟 | 页面已有固定文案 | 统一使用页面文案 |

**决策：既有页面的线上行为是权威**，测试与实现都对齐到它。这不是"改测试迁就实现"，
而是**先把既有行为固化为测试（characterization test），再让实现与页面共用同一份规则**。
对齐后测试扩充到 **45 条**，并新增"文案与页面一致"的断言，防止今后文案漂移。

### 发现 2　售价字段本身已有组件级约束（双层防护）★

用真实浏览器检查时发现：售价用的是 `el-input-number` 并带
`:min="0.01" :max="99999999" :precision="2"` —— 也就是说：

| 防护层 | 位置 | 作用 |
| --- | --- | --- |
| 第一层 | `el-input-number` 组件属性 | 输入 `25.555` → 自动处理为 **25.56**；输入 `0` → 自动夹到 **0.01** |
| 第二层 | `productRules.js` 规则（本模块） | 值为空/超范围时给出统一提示文案 |

实测证据（脚本自动断言）：

```
【4】售价组件约束：el-input-number（:min=0.01 / :max=99999999 / :precision=2）
  ✓ 输入 3 位小数被组件自动处理为 2 位   读回值=25.56
【5】售价下限：输入 0 应被组件夹到最小值 0.01
  ✓ 组件按 :min 夹住下限（双层防护的第一层）   读回值=0.01
【6】规则层的价格提示仍可用（清空售价后提交）
  ✓ 空售价时规则层给出提示（第二层防护）   实际提示=['请输入商品名称', '请选择商品分类', '请输入售价']
```

## 四、把规则接入页面（单一来源）

改动 `frontend/src/views/product/Publish.vue`：

```js
import { PRODUCT_RULES, MESSAGES, validateProductForm } from '@/utils/productRules'

const rules = {
  title: [
    { required: true, message: MESSAGES.titleRequired, trigger: 'blur' },
    { min: PRODUCT_RULES.TITLE_MIN, max: PRODUCT_RULES.TITLE_MAX, message: MESSAGES.titleLength, trigger: 'blur' }
  ],
  categoryId: [{ required: true, message: MESSAGES.categoryRequired, trigger: 'change' }],
  price: [
    { required: true, message: MESSAGES.priceRequired, trigger: 'blur' },
    { validator: (rule, value, callback) => {
        const { errors } = validateProductForm({ title: '占位标题', categoryId: 1, price: value })
        if (errors.price) return callback(new Error(errors.price))
        callback()
      }, trigger: 'blur' }
  ],
  conditionLevel: [{ required: true, message: '请选择成色', trigger: 'change' }],
  description: [{ max: PRODUCT_RULES.DESC_MAX, message: MESSAGES.descTooLong, trigger: 'blur' }]
}
```

**行为保持不变**（同一套阈值与文案），但规则只存在一处 —— 今后改规则只需改 `productRules.js`，
并有 45 条单元测试兜底。

### 接入后的两层验证

**① 构建验证**

```
$ pnpm run build
…
✓ built in 34.83s
（退出码 0）
```

**② 行为验证：AI 驱动真实浏览器逐项断言（自带截图）**

脚本 `tests/manual/check-publish-rules.py`（Playwright + 系统 Edge，无头运行）

```
【1】登录                                    ✓ 登录成功并离开登录页
【2】进入发布页                              ✓ 发布页已加载（/product/publish）
【3】空表单提交 → 应提示「请输入商品名称」   ✓ 实际提示=['请输入商品名称','请选择商品分类','请输入售价']
【4】售价组件约束                            ✓ 3 位小数被处理为 2 位（25.56）
【5】售价下限约束                            ✓ 0 被夹到 0.01
【6】规则层的价格提示                        ✓ 空售价时给出提示

结论：8/8 项通过
```

截图产物：`%TEMP%/dsh-sqlval/wiring-check/*.png`（登录页、发布页、空表单提示、组件归一化、下限约束、规则提示）

## 五、产出清单与回归结果

| 文件 | 说明 |
| --- | --- |
| `frontend/src/utils/productRules.js` | 新增：发布商品校验规则（纯函数，单一来源） |
| `tests/unit/product-rules.test.mjs` | 新增：45 条单元测试（先于实现编写） |
| `tests/manual/check-publish-rules.py` | 新增：真实浏览器行为验证脚本（含截图） |
| `frontend/src/views/product/Publish.vue` | 修改：表单规则改为引用规则模块 |

| 验证项 | 结果 |
| --- | --- |
| 新增单元测试 | **45 / 45 通过** |
| 原有单元测试（回归） | **31 / 31 通过** |
| 项目单元测试总数 | 31 → **76 条** |
| 前端构建 | ✓ built in 34.83s |
| 真实浏览器行为验证 | **8 / 8 通过** |

## 六、复盘与后续

**这次 TDD 真正的收获不是"多写了测试"，而是两个发现**：
① 规则在**页面与测试里各写了一遍**且数值不一致（不一致就迟早出问题）；
② 售价字段的约束其实有**两层**，此前只有一层写在文档里。

**后续可以用同样方式处理的其他纯逻辑**（优先级从高到低）：
1. 搜索页的「筛选条件 ⇄ URL query」双向转换（此前修过 BUG-03 重复请求，值得用测试固化）；
2. 订单状态机（待交易 / 已完成 / 已取消）的合法迁移矩阵；
3. 分页页码计算（当前页 / 总页数 / 省略号）。
