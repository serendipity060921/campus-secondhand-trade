# -*- coding: utf-8 -*-
"""抓取接口文档（Swagger UI）截图，用于论文与答辩材料（⑨-1）

为什么要单独抓图：接口文档是"系统实现"章节的常见配图，而答辩现场也不一定方便
临时打开浏览器找路径 —— 提前存一张进 docs/test-evidence/ 更稳妥。

执行：
  python tools/capture-api-doc.py
产物：
  docs/test-evidence/接口文档-swagger-ui.png
"""
import os
import pathlib
import sys

from playwright.sync_api import sync_playwright

BASE = os.environ.get('API_DOC_BASE', 'http://127.0.0.1:8080').rstrip('/')
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs' / 'test-evidence'
OUT.mkdir(parents=True, exist_ok=True)
SHOT = OUT / '接口文档-swagger-ui.png'


def main():
    url = BASE + '/swagger-ui/index.html'
    print(f'  打开 {url}')

    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge')
        page = browser.new_page(viewport={'width': 1440, 'height': 1000})
        page.goto(url, wait_until='domcontentloaded', timeout=30000)
        # 等 Swagger UI 渲染出接口分组与操作块（否则会截到一张白屏）
        page.wait_for_selector('.swagger-ui .opblock', timeout=30000)
        page.wait_for_timeout(1200)

        title = page.title()
        blocks = page.locator('.swagger-ui .opblock').count()
        print(f'  页面标题: {title}')
        print(f'  渲染出的接口操作块: {blocks} 个')

        page.screenshot(path=str(SHOT), full_page=False)
        browser.close()

    size = SHOT.stat().st_size
    print(f'  截图已保存：{SHOT}（{size / 1024:.1f} KB）')
    if blocks <= 0:
        print('  ✗ 没有渲染出接口块，请检查接口文档是否可访问')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
