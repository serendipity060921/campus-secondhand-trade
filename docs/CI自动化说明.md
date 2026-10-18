# CI 自动化说明（第5批 ⑰⑱⑲⑳）

> 本批目标：把"本地才能跑"的验证搬进 GitHub Actions —— 安全套件、部署验证、端到端、覆盖率评论。
> 当前状态：**⑳ 已完成并有真实运行证据**；⑰⑱⑲ 待做（需在 CI 里起 MySQL/Redis/Nginx）。

| 项 | 内容 | 状态 |
| --- | --- | :-: |
| ⑰ | CI 跑安全套件（52 条） | ⬜ |
| ⑱ | CI 跑部署验证（28 条 D 用例） | ⬜ |
| ⑲ | CI 跑端到端（31 条 Playwright） | ⬜ |
| ⑳ | PR 覆盖率阈值评论 | ✅ |

---

## ① ⑳ 覆盖率评论：为什么需要、怎么做的

### 问题

CI 里 `./mvnw -B package` 跌破棘轮时只会给一行：

```
Rule violated for bundle campus-trade-backend: instructions covered ratio is 0.79, but expected minimum is 0.83
```

评审的人看不出**差多少、哪个包拖后腿**；覆盖率报告虽然上传成 artifact，
但没人会去下载 XML 翻。

### 做法

新增 `tools/coverage-comment.py`（零第三方依赖，runner 自带 python3 即可）：

把 `jacoco.csv` 转成一段 markdown，**同一份内容两处用**：

1. **始终**写入 `$GITHUB_STEP_SUMMARY` —— 任何一次 CI 运行都能在页面上直接看到覆盖率；
2. **在 PR 上**由 `sticky-pull-request-comment` 贴成**粘性评论**（多次提交只保留一条）。

三个刻意的设计：

| 设计 | 原因 |
| --- | --- |
| 棘轮阈值**从 `backend/pom.xml` 读取**，不硬编码 | 避免"改了棘轮却忘了改脚本"，两处数字必然一致 |
| 摘要步骤用 `if: always()` | 跌破棘轮时 `package` 会失败，**失败时更需要**看到差多少、哪个包拖后腿 |
| `jacoco.csv` 不存在时输出一句可读原因并**不再让 job 多失败一次** | 通常是打包失败导致没生成报告，重复失败只会掩盖真正的错误 |

产出示例（实测，85.69%）：

```
**✅ 通过** ｜ 指令覆盖率 **85.69%**（棘轮 83%，↑ +2.69pp）

| 指标 | 数值 |
| 指令覆盖率 | **85.69%**（14,539 / 16,967） |
| 分支覆盖率 | **73.92%**（1,108 / 1,499） |
| 棘轮阈值（pom） | 83% |

### 未覆盖最多的包（下一步该补哪里）
| service.impl | 83.15% | 1,835 |
| service      | 31.77% | 350   |
| controller   | 88.48% | 96    |
...
```

### 需要写权限

`GITHUB_TOKEN` 默认只读（对来自 fork 的 PR 更是如此），不声明权限会**静默失败**，
所以 backend job 显式声明：

```yaml
permissions:
  contents: read
  pull-requests: write
```

---

## ② ★ 验证：不靠"看起来对"，靠真实运行

本机能访问 GitHub Actions API（公开仓库），因此提交后直接轮询运行结果：

| 证据 | 结果 |
| --- | --- |
| CI run #119（master）/ #120（ui 分支） | **completed / success** |
| 后端 job 的「单元测试 + 打包」步骤 | **success** |
| 后端 job 的「覆盖率摘要（写入 Job Summary）」步骤 | **success**（新步骤真的在 CI 里执行了） |
| 后端 job 的「PR 覆盖率评论（粘性）」步骤 | **skipped**（正确：push 到 master 没有 PR 可评论） |

### ★ 靠这一步抓到一个真 bug：`@v2` 根本不存在

最初写的是 `marocchino/sticky-pull-request-comment@v2`。
查 `tags` 接口才发现：该仓库**只有 v3.0.x，没有 v2**。

后果：一旦有人真的开 PR，这一步会在「获取 action」阶段直接失败 ——
而且**在没有真实 PR 的环境里永远发现不了**（push 到 master 时该步骤是 skipped，
连 action 都不会被拉取，所以语法检查也过）。

已改成钉版本 `@v3.0.5`。这条教训值得单独记下来：

> **被 `if:` 跳过的 `uses:` 步骤，等于没有被校验过。**
> 引用第三方 action 时，至少要查一次它到底有没有那个 tag。

### 尚未验证的部分（如实说明）

- 真正"贴 PR 评论"的动作**没有在真实 PR 上跑过**：本轮是直接 push master。
  `pull_request` 之外的路径都验证到了，评论动作本身只验证了 tag 存在。
- Job Summary 的**渲染内容**无法通过 API 读回（`/jobs` 只返回步骤结论）；
  但脚本在本地对同一份 `jacoco.csv` 的输出已核对过，且步骤结论为 success。

## ③ 复现

```bash
# 本地生成同样的摘要（用最近一次测试产生的 jacoco.csv）
python tools/coverage-comment.py --out coverage-comment.md

# 同时写入 CI 的 Job Summary（$GITHUB_STEP_SUMMARY 只在 Actions 里存在）
python tools/coverage-comment.py --summary
```
