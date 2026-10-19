# 用户故事截图证据（story-evidence）

> 用途：为 `docs/user-stories.md` 里的每条用户故事提供**界面级**证据。
> 采集脚本：`tools/capture-story-evidence.py`（可重复执行；默认不改数据）
> 采集方式：Playwright + 本机 Microsoft Edge（`channel='msedge'`），1440×1000

## 一、当前覆盖

| 指标 | 数值 |
| --- | :-: |
| 用户故事总数 | 36 |
| **有界面截图的故事** | **29** |
| 无截图（不适合 / 由其它方式证明） | 7 |
| 截图文件总数 | 48 |

命名约定：`US-模块-序号-N-说明.png`（`N` 是同一故事下的第几张）。
`capture-manifest.json` 记录每次采集的逐条结果（成功/失败与备注）。

## 二、不适合截图的故事（附理由与替代证据）

> 判断标准：**断言对象有没有"界面"**。如果一条验收标准说的是"接口只返回在售商品"、
> "源码里没有裸色值"、"CI 结论是绿"，那么截图只能拍到"一个通过的界面/一张表格"，
> 既不能证明断言，也容易被质疑摆拍。这类一律用**可复跑的文本证据**。

| 故事 | 为什么不适合截图 | 替代证据（可直接写进论文/答辩） |
| --- | --- | --- |
| **US-SEARCH-03** 只看在售（数据隔离） | 断言对象是**接口返回值**，不是某个页面长什么样；界面看不出"过滤发生在后端还是前端" | `tests/api/test-v015-deploy.py` 的 D24（匿名请求列表接口，断言结果中无非在售商品）+ 安全套件 S 系列 + `tools/check-authz-matrix.py` 输出 |
| **US-QUALITY-03** 设计令牌（零裸色值） | 断言对象是**源码静态属性**（有没有 `#hex`/`rgb()`），没有界面可看；截"页面很统一"无法证明 | `python tools/check-tokens.py --max-errors 0` 的真实输出（错误 0 条/警告 69 条）+ CI 的「设计令牌纪律」job |
| **US-QUALITY-04** 可访问性与对比度 | 对比度比值、alt 文本、键盘可达性都是**计算/静态检查结论**，肉眼与截图都不可判 | `python tools/ui-a11y.py` 输出 + `tools/check-states.py --max-mouse-only 0` |
| **US-QUALITY-07** 缺陷可追溯与质量不退化 | 断言对象是**CI 的运行结论**；而 Actions 日志/页面需登录，无法在本地截图 | CI run 号 + job 结论（本仓库 `ci.yml`）+ `docs/defect-log.md`（12 条缺陷闭环）+ JaCoCo 报告 `backend/target/site/jacoco/index.html`（可作为本地截图替代） |
| **US-EVAL-03** 信用分联动与防刷 | 变化发生在**数据层**（+1/−1、夹在 [0,150]），界面上只有一个静态数字，截"改成 124 了"无法证明因果 | 后端单测 3 条（断言 LEAST/GREATEST 与 ±1）+ 真实接口实测记录：admin 123→124、stu_test01 101→100 |
| **US-ORDER-02** 买卖双方订单视图 | **已被 US-ORDER-01 的 3 张覆盖**（买家视图/卖家视图/我买到的），重复截图没有信息增量 | `US-ORDER-01-2-我买到的.png`、`US-ORDER-01-3-卖家订单视图.png` |
| **US-ORDER-03** 确认交易完成 | 同上，**已被 `US-ORDER-01-4-卖家确认完成.png` 覆盖** | `US-ORDER-01-4-卖家确认完成.png` + E2E 1.10a~b |

### 建议的"截图替代方案"（如果答辩要求必须有图）

1. **终端输出截图**：把 `check-tokens.py` / `ui-a11y.py` / `check-authz-matrix.py` 的输出在工作台截一张图，
   标注"命令 + 退出码 + 结论行"。它比页面截图更能证明这类断言，且完全可复跑。
2. **JaCoCo 报告截图**：`backend/target/site/jacoco/index.html` 用浏览器打开截一张（86.06% / 665 条测试）。
3. **CI 运行页截图**：需要你自己登录 GitHub 打开 Actions 截一张（我这边读不到页面）。

## 三、已知缺口（诚实记录）

| 项 | 状态 |
| --- | --- |
| **评价弹窗（US-EVAL-01）** | ❌ **没有截图，且功能当前不可用** —— 点「评价这笔交易」后弹窗不打开（无请求、无报错、无 toast）。已排除 dist 过期、按钮未命中、DB 中已有评价、chunk 缺绑定等可能，**根因未定位**。原有一张名为"评价弹窗"的图实际拍的是订单列表，已删除（不能让名不副实的图进论文） |
| US-EVAL-02 的"匿名"展示 | ⚠️ 现有 `US-EVAL-02-2` 是评价区的**空态前后对比**；真正的"匿名同学"渲染因弹窗不可用而无法通过界面产生，目前由后端单测 + 接口实测证明 |

## 四、复跑

```bash
# 全量（约 2-3 分钟；会真实提交一次评价，跑完请清理）
python tools/capture-story-evidence.py

# 只重采某一类
python tools/capture-story-evidence.py --only EVAL
python tools/capture-story-evidence.py --only ADMIN

# 额外采限流提示（会占满登录限流窗口 60 秒）
python tools/capture-story-evidence.py --with-limits

# 清理评价数据
mysql -h127.0.0.1 -uroot -p123456 -e "DELETE FROM campus_trade.evaluation;"
python tools/seed-demo-data.py --reset
```
