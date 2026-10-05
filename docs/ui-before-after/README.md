# 改版前后对比（v0.15 基线 → v0.16 分支）

每张图是同一页面、同一视口的**并排对比**：左为 v0.15 基线，右为当前实现。
变化幅度按整图像素差异计算，**它衡量的是"变化"而不是"变好"** —— 令牌层把底色换成冷纸白
会让几乎每个像素都不同，因此百分比普遍偏高，不能当作质量分数。

| 页面 | 像素变化幅度 | 该页体现的改动 |
| --- | --- | --- |
| `login-desktop.png` | 90.6% | 冷纸白底 + 墨黑动作色（对比度不达标 65% → 0%）+ 字段错误可被读屏关联 |
| `admin-products-desktop.png` | 73.4% | 表格骨架屏 + 错误态 + 空态 + 筛选下拉可访问名称 |
| `chat-desktop.png` | 72.7% | 已读回执渲染 + 商品条去重 + 错误态 + 焦点可见性 |
| `home-mobile.png` | 72.0% | 窄屏导航收进 ☰ 抽屉（溢出 704/326/70px → 0）+ 分类色带两列折行 |
| `home-desktop.png` | 71.7% | 按选定稿 A 重构：8 段分类色带、三列目录卡（索书号/校区·成色）、右栏统计与推荐、三态 |
| `orders-bought-desktop.png` | 65.3% | 订单表纸面化（细线/等宽数字）+ 令牌与错误态 |
| `messages-desktop.png` | 63.5% | 错误态（静默刷新不打断页面）+ 会话条目键盘可达 |
| `search-desktop.png` | 62.7% | 结果卡与首页同一组件（索书号/校区）+ 骨架屏 + 错误态 |
| `detail-desktop.png` | 56.0% | h1 + 图片 alt + 缩略图键盘可达 + 价格 40px 主导 + 令牌化间距 |
| `favorites-desktop.png` | 46.1% | 骨架屏（8 项 4 列）+ 错误态 + 卡片键盘可达 |
| `admin-dashboard-desktop.png` | 38.3% | 后台令牌接入 + ECharts 主题色 + 环形图去掉与外置标签的重叠 |
| `publish-desktop.png` | 30.4% | 发布表单分段 + 上传失败留痕 + 控件可访问名称 |

全部 34 组对比（17 个页面 × 桌面/移动）与量化数据在 `.impeccable/review/compare-r26/`
（该目录不进 git）。

## 生成方式（可复现）

1. `python tools/capture-ui.py --label after-r26 --base http://127.0.0.1:8081`
   （17 个页面 × 桌面/移动 = 34 张，附 manifest.json 记录 URL / 视口 / 文件哈希）
2. 与基线 `.impeccable/review/before/` 逐像素比对，缩放 50% 后左右并排并标注变化幅度。

两套截图都带 manifest.json 可供复核；采集脚本是仓库里的 `tools/capture-ui.py`。
