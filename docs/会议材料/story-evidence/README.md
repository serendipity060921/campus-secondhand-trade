# 用户故事截图证据（story-evidence）

> 用途：为 `docs/user-stories.md` 里的每条用户故事提供**界面级**证据。
> 采集脚本：`tools/capture-story-evidence.py`（可重复执行；默认不改数据）
> 采集方式：Playwright + 本机 Microsoft Edge（`channel='msedge'`），1440×1000

## 一、当前覆盖

| 指标 | 数值 |
| --- | :-: |
| 用户故事总数 | 36 |
| **有界面截图的故事** | **30** |
| 无截图（不适合 / 由其它方式证明） | 6 |
| 截图文件总数 | 53 |

命名约定：`US-模块-序号-N-说明.png`。`capture-manifest.json` 记录每次采集的逐条结果。

## 二、不适合截图的故事（附理由与替代证据）

> 判据：**断言对象有没有"界面"**。若一条验收标准说的是"接口只返回在售商品"、
> "源码里没有裸色值"、"CI 结论是绿的"，截图只能拍到"一个通过的界面"，既不能证明断言，
> 也容易被质疑摆拍。这类一律用**可复跑的文本证据**。

| 故事 | 为什么不适合截图 | 替代证据 |
| --- | --- | --- |
| **US-SEARCH-03** 只看在售（数据隔离） | 断言对象是**接口返回值**；界面看不出过滤在前端还是后端 | 部署验证 D24（匿名请求列表接口，断言无非在售商品）+ 安全套件 + `tools/check-authz-matrix.py` |
| **US-QUALITY-03** 设计令牌（零裸色值） | 断言是**源码静态属性**（有无 `#hex`/`rgb()`） | `python tools/check-tokens.py --max-errors 0` 输出（0 错误）+ CI「设计令牌纪律」job |
| **US-QUALITY-04** 可访问性与对比度 | 对比度比值/alt/键盘可达都是计算或静态检查结论 | `python tools/ui-a11y.py` + `python tools/check-states.py --max-mouse-only 0` |
| **US-QUALITY-07** 缺陷可追溯与质量不退化 | 断言是 **CI 结论**；Actions 页面需登录，本地无法截图 | CI run 号 + job 结论 + `docs/defect-log.md`（12 条闭环）+ JaCoCo 报告首页 |
| **US-EVAL-03** 信用分联动与防刷 | 变化在**数据层**（±1、夹 [0,150]），界面只有一个静态数字，截"现在是 124"无法证明因果 | 后端单测 3 条（断言 LEAST/GREATEST 与 ±1）+ 实测记录 admin 123→124、stu_test01 101→100 |
| **US-ORDER-02 / 03** | **已被 US-ORDER-01 的 3 张覆盖**（买家视图/卖家视图/确认完成），重复截图无信息增量 | `US-ORDER-01-2-我买到的.png`、`-3-卖家订单视图.png`、`-4-卖家确认完成.png` |

### 答辩若必须配图，用这三种替代（比页面截图更有说服力）

1. **终端输出截图**：`check-tokens.py` / `ui-a11y.py` / `check-authz-matrix.py` 的输出，标注「命令 + 退出码 + 结论行」，可现场复跑；
2. **JaCoCo 报告截图**：浏览器打开 `backend/target/site/jacoco/index.html`（86.06% / 665 条测试）；
3. **CI 运行页截图**：登录 GitHub 打开 Actions（我这边读不到页面）。

## 三、这轮采图暴露并修掉的 4 个真问题（都值得写进论文的"工程实践"）

| # | 问题 | 表现 | 根因 | 修法 |
| --- | --- | :-: | --- | --- |
| 1 | **详情页评价入口根本没插入** | 页面上没有「评价这笔交易」按钮 | `Detail.vue` 是**混合换行符**（CRLF+LF），我按 CRLF 精确匹配替换 → 静默失败；改用 PowerShell 又因引号解析失败整段没执行 | 改为按"行"插入（Python 脚本，与换行符无关），并**校验按钮文案真的出现在页面上** |
| 2 | **弹窗误判为"打不开"** | 点击后 `.el-dialog` 数为 0 | `wait_for_selector(state='visible')` 太严 —— Element Plus 弹窗先挂载再播动画，那一刻还没有可见区域 | 改成 `state='attached'` + 等 900ms |
| 3 | **匿名开关没生效**（最严重） | 提交后库里 `anonymous=0`，评价区显示真名 —— 而截图文件名写着"含匿名评价" | 点了一下 `el-switch` 就提交，没确认状态 | 点击后**断言** `class` 含 `is-checked` 才提交；不满足就记一条失败 |
| 4 | 订单列表的「评价」按钮点击不触发处理器 | 无请求、无弹窗、无报错 | 按钮在 `el-table` 的 `fixed="right"` 列里（Element Plus 固定列的渲染陷阱） | 入口改到**订单详情页**；列表按钮改为「去评价」→ 跳详情 |

> 第 3 条最值得记：**"我以为我截到了匿名评价"，其实库里是实名的** ——
> 证据文件名本身就是一句断言，写文件名之前必须核对数据（这次靠 `SELECT anonymous` 才露馅）。
> 这也是我把"截图后回读数据库校验"固定进流程的原因。

## 四、复跑

```bash
# 全量（约 2-3 分钟；会真实提交评价，跑完务必清理数据）
python tools/capture-story-evidence.py

# 只重采某一类
python tools/capture-story-evidence.py --only EVAL
python tools/capture-story-evidence.py --only ADMIN

# 额外采限流提示（会占满登录限流窗口 60 秒）
python tools/capture-story-evidence.py --with-limits

# 单点调试弹窗（登录 → 打开订单详情 → 点入口 → 打印 console/请求/弹窗数）
python tools/dbg-eval-dialog.py --url /orders/1 --user stu_test01 --pass abc12345

# 清理：评价表 + 演示基线（reset 会把信用分一并还原）
mysql -h127.0.0.1 -uroot -p123456 -e "DELETE FROM campus_trade.evaluation;"
python tools/seed-demo-data.py --reset
python tools/seed-demo-data.py --verify
```

> ⚠️ 采图会真实写入评价（每单一次，且**信用分 ±1**）。重复跑同一场景会被 precheck 拦住（提示"已评价过"），
> 所以重采前必须先 `DELETE FROM evaluation`；跑完用 `--reset` 把信用分还原。
