# -*- coding: utf-8 -*-
"""判定：收藏页「取消收藏」是否真的生效（区分前端缺陷 vs 脚本定位问题）

做法：登录演示买家 → 打开我的收藏 → 记录标题集合 → 点第一个「取消收藏」→
      重新加载页面 → 再记录集合 → 对比。
  · 集合少了 1 个 → 功能正常（此前报告里"仍存在"是我脚本点错了按钮）
  · 集合不变     → 前端缺陷：取消收藏未生效，需记入缺陷跟踪表

用法：<python> tests/manual/check-favorite-cancel.py
"""
import pathlib
import re
import time

from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8081'
OUT = pathlib.Path.home() / 'AppData' / 'Local' / 'Temp' / 'dsh-sqlval' / 'fav-check'
OUT.mkdir(parents=True, exist_ok=True)
BUYER = ('stu_demo', '123456')

ITEM_TITLE = re.compile(r'^.{4,60}$')


def titles(page):
    """收藏页上出现的商品标题（取各卡片标题文本，去掉按钮文字）"""
    got = []
    for el in page.locator('.product-card, .favorite-item, .el-card').all():
        try:
            t = (el.inner_text() or '').strip().splitlines()[0].strip()
            if t and '取消收藏' not in t and len(t) > 3:
                got.append(t)
        except Exception:
            pass
    # 去重保序
    seen, out = set(), []
    for t in got:
        if t not in seen:
            seen.add(t)
            out.append(t)
    return out


with sync_playwright() as pw:
    b = pw.chromium.launch(channel='msedge', headless=True)
    ctx = b.new_context(viewport={'width': 1440, 'height': 900})
    page = ctx.new_page()

    page.goto(f'{BASE}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(BUYER[0])
    page.get_by_placeholder('请输入密码').fill(BUYER[1])
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'^(?!.*login).*'), timeout=20000)
    time.sleep(1)

    page.goto(f'{BASE}/favorites', wait_until='domcontentloaded')
    time.sleep(2.5)
    before = titles(page)
    page.screenshot(path=str(OUT / '01-取消前.png'))
    print(f'  取消前收藏卡片数={len(before)}')
    for t in before:
        print(f'    · {t[:40]}')

    btns = page.get_by_role('button', name='取消收藏')
    n = btns.count()
    print(f'  页面上「取消收藏」按钮数={n}')
    if n == 0:
        print('  ✗ 没有找到取消收藏按钮 → 无法判定')
        b.close()
        raise SystemExit(2)

    # 点第一个按钮所在卡片：先取该按钮的容器文本，点后据此比对
    card_text = ''
    try:
        card_text = btns.first.locator('xpath=ancestor::*[contains(@class,"card") or contains(@class,"item")][1]').inner_text()
        card_text = card_text.strip().splitlines()[0].strip()
    except Exception:
        pass
    print(f'  首个按钮所属卡片标题≈{card_text[:40]!r}')

    btns.first.click()
    time.sleep(2)
    page.reload(wait_until='domcontentloaded')
    time.sleep(2.5)
    after = titles(page)
    page.screenshot(path=str(OUT / '02-取消后刷新.png'))
    print(f'  取消后（已刷新）收藏卡片数={len(after)}')
    for t in after:
        print(f'    · {t[:40]}')

    removed = [t for t in before if t not in after]
    print('\n' + '=' * 56)
    if len(after) < len(before):
        print(f'  结论：✓ 取消收藏生效（减少 {len(before) - len(after)} 项：{removed[:2]}）')
        print('        此前浏览器报告里"仍存在"应为脚本点到其他卡片所致，非前端缺陷。')
        code = 0
    else:
        print('  结论：✗ 取消收藏未生效（刷新后卡片数不变）→ 记为前端缺陷，需进缺陷跟踪表')
        code = 1
    print(f'  截图：{OUT}')
    b.close()
    raise SystemExit(code)
