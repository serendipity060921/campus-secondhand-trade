# 首页目录 · 设计稿（comp）与选定结果

同一套设计语言（索书号与卡片目录）的三种构图，2026-10-05 由用户选定 **A**。

| 稿 | 构图 | 结果 |
| --- | --- | --- |
| `comp-a-home-list.png` | 横向分类色带 + 三列目录卡 + 右栏（统计与推荐） | **已选定**（sidecar 内 `approved: true`） |
| `comp-b-home-list.png` | 左侧竖向书脊 + 四列密卡，无右栏 | 落选 |
| `comp-c-home-list.png` | 左侧标签轨 + 一张焦点大卡 + 紧凑目录表 | 落选 |

打开 `decision.html` 可并排查看三张。

## 这些图是怎么来的（重要）

本项目没有可用的图像生成工具。因此这三张稿子是**用项目真实设计令牌与真实内容渲染 HTML/CSS，
再用 Playwright + Edge 截成 1536×1024 的 PNG**：

- 配色：`frontend/src/styles/tokens.css` 的真实取值（paper `#f5f6f3`、ink `#1a1d19`、rule `#c9ccc4`，
  以及 8 个分类色 `#7a5a2e/#1f5a6b/#35603f/#6b6a2e/#7a4260/#8c3b5a/#3a4a7a/#5a5f58` 与其浅底色）
- 内容：演示库真实商品（标题/价格/校区/成色），图片用 `frontend/public/demo-images/` 里仓库既有的占位图，
  **没有伪造商品照片**
- 分类号：`frontend/src/utils/catalogue.js` 的真实映射（G4/TN/TS/G8/TS9/TQ/J6/Z9）

好处是稿子与最终实现同一套色值/字号/间距，比示意性插画更接近真实结果；
每个 `.png.json` sidecar 里保存了完整提示词、来源 HTML 与生成方式，便于复核。

## 引擎侧位置

Impeccable 的 comps 门禁读取 `.impeccable/mocks/`（该目录不进 git），
因此这里保留一份副本用于论文与版本历史；如需在新机器上恢复门禁状态，
把本目录的三个 png 与 sidecar 复制回 `.impeccable/mocks/` 即可。


## 商品图素材的决策（2026-10-05）

用户明确决定：**沿用仓库既有商品图作为素材，不生成新图**。

原因：plate 门禁要求"以裁切图为参考**生成**新素材"，而本项目的 product truth 明确禁止
伪造商品照片。8 个栅格区的素材取自仓库既有的 `frontend/public/demo-images/*.png`（800×800，
是区域尺寸的 2.35×，满足 ≥1.5× 下限），plate 由该源图重新裁切/缩放得到。

引擎的"comp 裁切"判定属于**误报**：它无法区分"从源图新裁切"与"裁 comp"，
而 comp 本身就是用同一批源图渲染的，像素必然接近。按引擎拒绝说明中的指引
（"当读数与用户对该 comp 的表述冲突时询问用户"），此决策由用户本人做出并在此留档。
