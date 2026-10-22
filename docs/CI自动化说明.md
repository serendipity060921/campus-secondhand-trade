# CI 自动化说明（第5批 ⑰⑱⑲⑳）

> 本批目标：把"只有本地才能跑"的验证搬进 GitHub Actions。
> 当前状态：**⑳（覆盖率评论）与 ⑰（安全套件）已完成并有真实运行证据**；⑱⑲ 待做。

| 项 | 内容 | 状态 | 真实运行证据 |
| --- | --- | :-: | --- |
| ⑰ | CI 跑安全套件（52 条） | ✅ | run **#132** 四个 job 全绿 |
| ⑱ | CI 跑部署验证（28 条 D 用例） | ⬜ | — |
| ⑲ | CI 跑端到端（31 条 Playwright） | ⬜ | — |
| ⑳ | PR 覆盖率阈值评论 | ✅ | run #119/#120/#121 |

---

## 一、⑰ 安全套件：架构与关键配置

### 1.1 四个 job 的分工

```
design-guard  令牌纪律 / 状态可达性 / 后端卫生 / 限流覆盖（秒级，先失败先反馈）
frontend      构建 + 纯函数覆盖率 + 依赖审计
backend       640 条单测 + 覆盖率棘轮 + 覆盖率摘要/PR 评论 + 【上传 jar 产物】
    ↓ needs: backend
security      service 容器 MySQL+Redis → 灌库 → 起后端 → 52 条安全用例
```

`security` 依赖 `backend` 是刻意的：**复用同一份二进制**（下载 artifact），
不重新跑一遍单测 —— 省 CI 时间，也避免"测的和我部署的不是同一个包"。

### 1.2 ★ service 容器的两个致命细节

```yaml
services:
  mysql:
    image: mysql:8.4
    ports: ['3306:3306']          # ← 必须映射
    options: >-
      --health-cmd "mysqladmin ping -h 127.0.0.1 -uroot -p123456"
      --health-interval 10s --health-retries 12
```

| 细节 | 写错会怎样 |
| --- | --- |
| 本 job **没有** `container:` 键 → 必须用 `localhost` + `ports` 映射 | 写成服务名 `mysql` → 连接超时（那是"容器 job"的用法） |
| **必须**配 `--health-cmd` | 不配 → 步骤在 MySQL initdb 完成前就开跑，典型"本机能跑 CI 挂" |

### 1.3 ★★ 建库三步是实测出来的，不是猜的

```bash
mysql -h 127.0.0.1 -uroot -p*** < db/schema/01-schema.sql                    # 建库建表 + 基础字典
mysql -h 127.0.0.1 -uroot -p*** campus_trade < db/schema/04-moderation.sql    # moderation_log
python3 tools/seed-demo-data.py --reset                                      # 演示数据（先清表再载入基线）
```

三条理由，每条都在本地付过代价：

1. `01-schema.sql` 里的 `CREATE DATABASE` / `USE` 是**硬编码 `campus_trade`**，库名不能换；
   它自带的只是"建表 + 1 个 admin + 32 个分类"，**不是完整演示数据**。
2. `04-moderation.sql` 的内容**不在 01 里**（`moderation_log` 表），不单独跑就会缺表。
3. 演示数据必须走 `seed-demo-data.py --reset`（先清表再载入基线）；
   直接 `mysql < demo-baseline.sql` 会撞 `Duplicate entry '1' for key 'user.PRIMARY'` ✓ 实测过。

### 1.4 为什么用 prod profile

安全套件里有**限流用例**（登录/发布/私信等）。`dev` profile 的
`rate-limit-skip-local: true` 会让这些用例失去意义 —— 限流被跳过了，还测什么？
所以 CI 用 prod profile，让限流真实生效，与"真实部署形态"一致。

---

## 二、★ CI 调试实录：四次失败，全部"只在 CI 环境才暴露"

run #128 失败 → #131 又失败 → #132 全绿。
因为 **Actions 日志需要登录才能读**，我拿不到日志，只能靠"读代码 + 反推"定位。

| 轮次 | 失败步骤 | 真因 | 修法 |
| --- | --- | --- | --- |
| #128 | 初始化数据库 | `seed-demo-data.py` 把 mysql 客户端路径**写死**成 `D:\major\tool\...\mysql.exe`，ubuntu 上不存在 → `FileNotFoundError` | 三级回退：`MYSQL_BIN` 环境变量 → 本机绿色版路径 → `PATH`（`shutil.which`）；顺带把"自动备份"改成非致命（runner 未必装 `mysqldump`） |
| #131 | 跑安全套件 | 套件默认地址取自 `WEB_BASE`，缺省值 **8081**（Nginx 反代）；CI 里没有 Nginx，而我只配了 MySQL/Redis，**漏配 `WEB_BASE`** → 所有请求打到不存在的端口 | job env 显式设置 `WEB_BASE=http://127.0.0.1:8080` |
| #132 | — | — | **四个 job 全绿** |

**这三次迭代本身就是结论**：容器、灌库、起服务、套件四关，
本地无论跑多少次都发现不了后两类问题 —— 它们依赖"CI 的运行形态"
（Linux 路径、无 Nginx、全新库）。

### ★ 第四层：job 级 `working-directory` 是隐式上下文（exit 127）

**现象**：CI 连续 6 轮失败，但失败的**只有**「安全套件」和「部署验证」两个 job，
「后端编译 / 前端构建 / 设计令牌」一直是绿的。两个红 job 都崩在同一步：

```
构建可执行 jar（跳过测试与覆盖率棘轮）   → Process completed with exit code 127
```

**诊断线索**：日志读不到，但注解里能读到 **exit code 127**，而 127 = command not found。

**根因**：这两个 job **没有** job 级 `defaults.run.working-directory`，而「后端编译」job 有。
我在把"复用 backend 的 jar artifact"改成"各自构建"时，写的是：

```yaml
- name: 构建可执行 jar（跳过测试与覆盖率棘轮）
  run: ./mvnw -B -DskipTests -Djacoco.skip=true package   # ← 在仓库根找 mvnw，不存在
```

于是从那次提交起，每一轮都红在同一步。

**修法**：只给这一步加 `working-directory: backend`（不设 job 级 —— 后续步骤要用仓库根的
`tests/api/...` 与 `backend/target/...` 相对路径）。

```yaml
- name: 构建可执行 jar（跳过测试与覆盖率棘轮）
  working-directory: backend
  run: ./mvnw -B -DskipTests -Djacoco.skip=true package
```

修完 run #192：**5 个 job 全绿**（前端构建 / 后端编译 / 设计令牌纪律 / 安全套件 52 条 / 部署验证 D1–D28）。

**教训**：同一段 `run:` 命令在不同 job 里语义可能不同 —— job 级 `working-directory`
是隐式上下文；把"复用产物"改成"各自构建"时，最容易漏的就是这个假设。
也说明前面给失败步骤补"退出码 + 日志注解"的投入是值得的：
这次就是靠注解里的 `127` 才定位到的。


### 不能读日志时怎么定位？

1. 先看**步骤级结论**（Actions API 的 `/jobs` 能拿到每个 step 的 success/failure）；
2. 再看该步骤里**哪一条命令**最依赖环境（路径、端口、缺省值）；
3. 把这条命令在本地**用同等形态**复现（我起了 18080 的第二实例，prod profile + 同款环境变量 → 52/52）；
4. 逐条排除"只在 CI 才有"的差异（无 Nginx、Linux 路径、全新库）。

### 本地彩排的价值与边界

彩排复现了 52/52 ✅，但仍漏掉 `WEB_BASE` —— 因为本地有 Nginx，
我显式写了 `WEB_BASE=http://127.0.0.1:18080`，而 **CI 里忘了写**。
教训：**本地彩排要连"环境差异"一起彩排**，而不只是"命令能跑通"。

---

## 三、⑳ 覆盖率评论：从 XML 变成人话

`tools/coverage-comment.py` 把 `jacoco.csv` 转成 markdown，**一份内容两处用**：

1. **始终**写 `$GITHUB_STEP_SUMMARY` —— 任何一次 CI 运行都能在页面上看到覆盖率；
2. 在 PR 上由 `sticky-pull-request-comment` 贴成**粘性评论**（多次提交只保留一条）。

三个刻意设计：

| 设计 | 原因 |
| --- | --- |
| 棘轮阈值**从 `backend/pom.xml` 读取** | 避免"改了棘轮忘了改脚本"，两处数字必然一致 |
| 摘要步骤 `if: always()` | 跌破棘轮时 `package` 会失败，**失败时更需要**看到差多少、哪个包拖后腿 |
| `jacoco.csv` 缺失时给可读原因、不再让 job 多失败一次 | 通常是打包失败导致没生成报告，重复失败只会掩盖真错误 |

### ★ 靠查 tag 抓到一个真 bug

最初写 `marocchino/sticky-pull-request-comment@v2` → 查 tags 接口发现
**该仓库只有 v3.0.x，没有 v2** ✗。已改钉 `@v3.0.5`。

> **被 `if:` 跳过的 `uses:` 步骤，等于没有被校验过。**
> push 到 master 时该步骤是 skipped，连 action 都不会被拉取，语法检查也过；
> 只有真开 PR 才会炸 —— 而那时已经晚了。

### 尚未验证的部分（如实说明）

- 贴评论的动作**没在真实 PR 上跑过**（本轮直接 push master）：只验证了 tag 存在 + 其余路径全绿。
- Job Summary 的**渲染内容**无法通过 API 读回（`/jobs` 只给步骤结论）；
  脚本对同一份 `jacoco.csv` 的本地输出已核对，且步骤结论为 success。

---

## 四、复现

```bash
# 本地生成覆盖率摘要
python tools/coverage-comment.py --out coverage-comment.md

# 本地跑安全套件（需服务已起在 8080）
WEB_BASE=http://127.0.0.1:8080 python tests/api/test-security.py

# CI 里由 .github/workflows/ci.yml 的 security job 自动完成
```
