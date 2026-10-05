# 改版前后对比（v0.15 基线 → 当前 v0.16 分支）

每张图是同一页面、同一视口的**并排对比**：左为 v0.15 基线，右为当前实现。
变化幅度按整图像素差异计算，**它衡量的是"变化"而不是"变好"** —— 令牌层把底色从白换成冷纸白，
会让几乎每个像素都不同，因此百分比普遍偏高，不能当作质量分数。

| 页面 | 像素变化幅度 | 该页体现的改动 |
| --- | --- | --- |
| `login-desktop.png` | 90.6% | 冷纸白底 + 墨黑动作色（对比度不达标 65% → 0%） |
| `admin-products-desktop.png` | 73.4% | 表格骨架屏 + 错误态 + 空态（后台只调令牌与间距，结构保留） |
| `chat-desktop.png` | 72.5% | 错误态 + 焦点可见性修复 |
| `orders-bought-desktop.png` | 65.3% | 令牌层 + 错误态 |
| `home-mobile.png` | 65.2% | 窄屏导航收进 ☰ 抽屉（390/768/1024 溢出 704/326/70px → 0） |
| `messages-desktop.png` | 63.5% | 错误态（静默刷新不打断页面） |
| `home-desktop.png` | 58.6% | 令牌层 + 错误态 + 响应式断点 |
| `search-desktop.png` | 52.1% | 骨架屏替换转圈遮罩 + 错误态 + 空态主行动 |
| `favorites-desktop.png` | 46.1% | 骨架屏（8 项 4 列）+ 错误态 |
| `admin-dashboard-desktop.png` | 38.6% | 后台令牌接入 + ECharts 主题色 + 环形图标签不再被裁切 |

全部 34 张对比图（含移动端与其余页面）在 `.impeccable/review/compare-r19/`（该目录不进 git），
量化数据在同目录 `compare.json`。

## 生成方式（可复现）

1. 采集当前界面：`python tools/capture-ui.py --label after-r19 --base http://127.0.0.1:8081`
   （17 个页面 × 桌面/移动 = 34 张，附带 manifest.json 记录 URL / 视口 / 文件哈希）
2. 与基线（`.impeccable/review/before/`）逐像素比对并生成并排图。

两套截图都带 manifest.json，可用于复核；脚本见仓库 `tools/capture-ui.py` 与本次生成记录。
