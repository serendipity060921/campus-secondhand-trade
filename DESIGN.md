---
name: 校园二手交易平台
description: 一套"索书号与卡片目录"式的视觉系统 —— 像查图书馆目录卡那样翻校园闲置，轻量、高效、纸面感
colors:
  paper-0: "#ffffff"
  paper-50: "#f5f6f3"
  paper-100: "#e9ebe5"
  paper-200: "#dcdfd8"
  ink-900: "#1a1d19"
  ink-700: "#3d4239"
  ink-600: "#5c6159"
  ink-400: "#8a8f86"
  rule: "#c9ccc4"
  cat-textbook-bar: "#7a5a2e"
  cat-textbook-tint: "#f1eae0"
  cat-digital-bar: "#1f5a6b"
  cat-digital-tint: "#e4eef0"
  cat-life-bar: "#35603f"
  cat-life-tint: "#e6efe8"
  cat-sport-bar: "#6b6a2e"
  cat-sport-tint: "#eff0df"
  cat-apparel-bar: "#7a4260"
  cat-apparel-tint: "#f2e7ec"
  cat-beauty-bar: "#8c3b5a"
  cat-beauty-tint: "#f5e6eb"
  cat-instrument-bar: "#3a4a7a"
  cat-instrument-tint: "#e7eaf3"
  cat-other-bar: "#5a5f58"
  cat-other-tint: "#eceeea"
typography:
  display:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Hiragino Sans GB', system-ui, sans-serif"
    fontSize: "26px"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "normal"
  headline:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: 1.35
  title:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: 1.5
  body:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.5
  code:
    fontFamily: "Consolas, 'Cascadia Mono', 'SF Mono', 'Courier New', monospace"
    fontSize: "12px"
    fontWeight: 400
    letterSpacing: "0.06em"
rounded:
  sm: "2px"
  md: "4px"
  lg: "6px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
  xxl: "48px"
  xxxl: "64px"
components:
  catalogue-card:
    backgroundColor: "{colors.paper-0}"
    textColor: "{colors.ink-900}"
    rounded: "{rounded.sm}"
    padding: "12px"
  catalogue-card-hover:
    backgroundColor: "{colors.paper-0}"
    rounded: "{rounded.sm}"
  class-band-segment:
    backgroundColor: "{colors.cat-textbook-tint}"
    textColor: "{colors.ink-900}"
    height: "60px"
    width: "192px"
  search-field:
    backgroundColor: "{colors.paper-0}"
    textColor: "{colors.ink-900}"
    rounded: "{rounded.sm}"
    height: "36px"
    width: "260px"
  button-primary:
    backgroundColor: "{colors.ink-900}"
    textColor: "{colors.paper-0}"
    rounded: "{rounded.sm}"
    padding: "8px 14px"
  shelf-code-chip:
    backgroundColor: "{colors.cat-textbook-tint}"
    textColor: "{colors.ink-700}"
    rounded: "{rounded.sm}"
    padding: "2px 6px"
---

# Design System: 校园二手交易平台

## Overview

**Creative North Star: "索书号与卡片目录"**

这套界面借的是图书馆目录的形式与秩序：一件闲置就是一张目录卡，一栏分类就是一道书标，
每张卡都带一个可检索的分类号（如 `G4·006`）。它不装点商品，而是让"有什么、多少钱、
在哪个校区"在一眼之内读完 —— 像抽出一张目录卡那么快。

定位是**轻量高效**：发一件 30 秒、看中就私聊。所以界面密度偏高、装饰为零；
主色只有墨黑，颜色全部让位给分类标记。使用场景是电脑为主（宿舍、图书馆），
因此桌面宽屏优先，窄屏收为抽屉而不牺牲功能。

**已确认的视觉反例**（用户明确排除，不得回退）：不要暗色主题；不要高饱和撞色；
不要"AI 味"的通用模板感（暖米色 + 衬线大标题 + 赤陶色按钮那一类）。

**Key Characteristics:**
- 纸面而非卡片堆叠：冷调纸白作底（`#f5f6f3`），白面作卡，0.5px 墨线分隔
- 无阴影、无渐变、无玻璃拟态：层级只靠底色深浅与细线
- 分类色以两种形态出现：色带上的 4px 实色条与其浅底色；不进入按钮与标题
- 等宽字体承担分类号与数字，正文承担中文叙述
- 交互克制：80–140ms 过渡，唯一的动作色是墨黑

## Colors

调色板是"纸 + 墨 + 书标"三层：纸提供底，墨承担文字与动作，八个书标色只做分类编码。

### Primary
- **Ink Black 墨黑** (`#1a1d19`): 正文、标题、价格、主按钮底色。它同时是唯一的动作色 ——
  全站没有第二个强调色（见下"One Action Colour Rule"）。
- **Cool Paper 冷纸白** (`#f5f6f3`): 页面底色。**刻意不是暖米色**：它是略偏冷的纸，
  与"AI 味"模板里常见的暖米色明确区分。

### Secondary
- **Eight Shelf Hues 八书标色** (`#7a5a2e` 教材书籍 / `#1f5a6b` 数码电子 / `#35603f` 生活用品 /
  `#6b6a2e` 运动户外 / `#7a4260` 服饰鞋包 / `#8c3b5a` 美妆护肤 / `#3a4a7a` 乐器文具 / `#5a5f58` 其他闲置):
  只用于分类编码 —— 色带上的 4px 实色条、分类号文字、以及各自 8–9% 的浅底色。
  八色明度接近、饱和度中等，并排时不产生"撞色"。

### Neutral
- **Ink 700** (`#3d4239`): 次级标题与分类号文字。
- **Ink 600** (`#5c6159`): 弱化正文（校区、成色、统计行）。对纸底对比度 5.6:1，正文可用。
- **Ink 400** (`#8a8f86`): 仅装饰性文字与已售出填充，**不得用于正文**。
- **Rule 墨线** (`#c9ccc4`): 0.5px 分隔线。
- **Paper 100 / 200** (`#e9ebe5` / `#dcdfd8`): 浅底块与图片占位底。

### Named Rules
**The One Action Colour Rule.** 动作色只有墨黑一个。分类色永不用于按钮、链接、标题或大面积背景 ——
一旦它们开始承担"可点击"，分类编码就失效了。

**The Cool Paper Rule.** 底色是冷纸白 `#f5f6f3`，不是暖米色。任何"加一点暖调会更温柔"的改动都违反此规则。

## Typography

**Body Font:** `'PingFang SC', 'Microsoft YaHei', 'Hiragino Sans GB', system-ui, sans-serif`
**Label/Mono Font:** `Consolas, 'Cascadia Mono', 'SF Mono', 'Courier New', monospace`

**Character:** 中文用系统黑体，克制、无性格负担；分类号与数字交给等宽字体，
让"目录"的秩序感来自字形本身而不是装饰。不引入 Web 字体（中文子集体积不可接受，
且会让首屏在校园网下变慢）。

### Hierarchy
- **Display** (600, 26px, 1.3): 详情页主标题与价格（价格 40px）。
- **Headline** (600, 22px, 1.35): 页面主标题。
- **Title** (600, 15px, 1.5): 区块标题、右栏小标题。
- **Body** (400, 14px, 1.5): 卡片标题、正文；卡片标题固定两行高度，保证网格对齐。
- **Label** (400, 12px, 1.5): 校区、成色、统计、计数。
- **Code** (400, 12px, 0.06em 字距): 分类号 `G4·006`、件数、等宽数字。

### Named Rules
**The Tabular Number Rule.** 所有价格与计数使用等宽数字（`font-variant-numeric: tabular-nums`），
避免列表滚动时数字宽度跳动。

## Layout

桌面优先的三段式：**顶栏 64px → 分类色带 60px → 主区（目录卡 3 列 + 右栏 358px）**。
页面内容宽 1536px 基准，左右留白 40px；卡片间距 24px。

分类信道的形态随宽度改变，但"分类是页面主色场"这一条不变：

| 断点 | 分类信道 | 目录卡 |
| --- | --- | --- |
| ≥1101px | 横向色带，8 段等宽（192px/段） | 3 列 + 右栏（统计与推荐） |
| ≤1100px | 收进 ☰ 抽屉（抽屉内 6 项导航 + 搜索） | 单列/双列，无右栏 |

**断点为何是 1100px：** 顶栏固有宽度 1094px（260px 搜索框 + 6 项不折叠导航），
低于 1100px 必须收起导航，否则整站横向溢出（实测 390/768/1024 曾分别溢出 704/326/70px）。

### Named Rules
**The Single Root Rule.** 路由级组件必须只有一个根节点 —— 布局用 `<transition mode="out-in">`
包 `<router-view>`，多根会让站内跳转整页渲染不出来（已实际踩过）。

## Elevation & Depth

**完全扁平。** 无阴影、无模糊、无 backdrop-filter；层级只由三种手段表达：
底色的深浅（纸白 / 白面 / 浅底块）、0.5px 墨线、以及字重。

### Named Rules
**The Flat-By-Default Rule.** 静止状态没有阴影。需要强调时用底色或墨线，不用投影。

**The Hairline Rule.** 分隔用 0.5px 墨线 `#c9ccc4`；不得用灰色填充块冒充分隔。

## Shapes

圆角极小：`2px`（卡片、按钮、输入框）、`4px`（次级容器）、`6px`（弹出层）。
形态语言是**直角纸卡**，不是胶囊与圆润气泡 —— 圆角一旦变大，纸卡感就没了。

图片一律 4:3（目录卡）或 1:1（右栏缩略图），以 `object-fit: cover` 填充，底色为 Paper 100。

## Components

- **Catalogue Card（目录卡）**: 白面 + 1px 墨线边框、2px 圆角，解剖顺序固定为
  **4:3 图 → 分类号 chip + 分类名 → 标题（两行截断）→ 价格（现价 17px/600 + 划线原价 12px）→ 校区 · 成色**。
  hover 只改边框深浅，不做位移与投影。
- **Class Band Segment（色带分段）**: 浅底色块 + 底部 4px 实色条；段内自上而下为
  分类号（等宽、取实色）→ 分类名（13px/600）→ 件数（12px 弱化）。可点筛选。
- **Shelf Code Chip（分类号标签）**: 等宽 11px、0.06em 字距、取该分类浅底色、文字用 Ink 700。
- **Search Field**: 白面 + 墨线边框 + 2px 圆角，高 36px；append 一个墨黑按钮。
- **Button Primary**: 墨黑底 + 纸白字，2px 圆角，8/14px 内边距；hover 用 Ink 700。
- **States**: 空/错误/加载由共享组件承担 —— `StateEmpty` / `StateError`（持久 + 可重试）/
  `StateForbidden` / `SkeletonList` / `SkeletonTable` / `SkeletonDetail`（骨架与最终版式对齐）。

### Named Rules
**The Shelf-Code Rule.** 每张目录卡必须带分类号。没有分类号的卡片不允许出现。

**The No-Raw-Color Rule.** 组件里不得写裸色值，一律用 `var(--ct-*)`；CI 已锁死 0 容忍。


### 代码落点（本项目的实现位置）

| 设计要素 | 落地文件 |
| --- | --- |
| 令牌三层（原始值 / 语义 / 组件） | `frontend/src/styles/tokens.css` |
| Element Plus 变量映射与焦点环兜底 | `frontend/src/styles/element-overrides.css` |
| 全局基础样式与 `.sr-only` 工具类 | `frontend/src/styles/base.css` |
| 分类书标色带（8 段，主色场） | `frontend/src/components/catalogue/CategoryBand.vue` |
| 目录卡（4:3 图 → 分类号 chip → 标题两行 → 价格 → 校区 · 成色） | `frontend/src/components/catalogue/CatalogueCard.vue` |
| 状态组件（空 / 错误 / 无权限 / 三种骨架） | `frontend/src/components/states/` |
| 推荐位（整宽网格 / 右栏竖排两版式） | `frontend/src/components/RecommendPanel.vue` |
| 索书号派生规则（分类号 + 种次号） | `frontend/src/utils/catalogue.js`（含 28 条单元测试） |
| ECharts 取色（canvas 读不到 CSS 变量） | `frontend/src/utils/design-tokens.js` |

**实测断点**：顶栏在 ≤1100px 收起导航（固有宽度 1094px），色带 ≤1100px 折为 4 列、≤600px 折为 2 列；
目录卡 3 列 → 2 列 → 1 列。390/768/1024/1280/1440 五个宽度下前台页面横向溢出均为 0
（改版前 390/768/1024 分别溢出 704/326/70px；后台两页仍溢出，属已知待办）。

**可访问性基线（实测）**：焦点不可见 0；图片无 alt 0；无 h1 页面 0；无可访问名称的可交互元素 0；
7 个页面逐一 Tab 走查无死角。对比度不达标 0%（3 处装饰性文字）。
表单错误通过 `aria-invalid` + `aria-describedby` + 带 id 的错误元素与字段关联。

## Do's and Don'ts

**Do**
- 用分类色编码分类，用它做检索的锚点
- 用墨线与底色深浅表达层级
- 让"校区 · 成色 · 价格"在一眼之内读完
- 骨架屏与最终版式对齐，避免数据到达时跳动
- 每个交互元素键盘可达（可聚焦 + Enter/Space + 可见焦点环）

**Don't**
- 不要暗色主题、不要高饱和撞色
- 不要暖米色 + 衬线大标题 + 赤陶按钮那类通用模板感
- 不要让分类色进入按钮、链接或标题
- 不要用阴影、渐变、玻璃拟态制造层级
- 不要伪造商品照片：商品图一律用真实上传图或仓库既有占位图
- 不要在路由级组件里写多个根节点
