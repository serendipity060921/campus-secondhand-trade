#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
演示彩排：按明天 8 分钟的演示路径自动走一遍，逐步截图。

★ 只打测试环境（http://127.0.0.1:8092 → campus_trade_test），不碰学生的演示库。
   截图输出：docs/story-evidence/演示彩排/NN-说明.png

用法：python tools/demo-rehearsal.py
"""
import pathlib
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8092'
OUT = pathlib.Path(r'D:\campus-secondhand-trade\docs\story-evidence\演示彩排')
BUYER = ('stu_demo', '123456')
ADMIN = ('admin', '123456')

step = 0
shots = []


def snap(page, title, note=''):
    global step
    step += 1
    OUT.mkdir(parents=True, exist_ok=True)
    p = OUT / f'{step:02d}-{title}.png'
    page.screenshot(path=str(p), full_page=False)
    shots.append((step, title, note, p.name))
    print(f'  {step:02d} 📷 {title}' + (f'  （{note}）' if note else ''))


def login(page, cred):
    page.goto(f'{BASE}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(cred[0])
    page.get_by_placeholder('请输入密码').fill(cred[1])
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'/home'), timeout=30000)
    page.wait_for_timeout(1200)


def goto(page, path):
    page.goto(f'{BASE}{path}', wait_until='domcontentloaded')
    try:
        page.wait_for_load_state('networkidle', timeout=12000)
    except Exception:
        pass
    page.wait_for_timeout(800)


def main():
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel='msedge', headless=True)
        # 买家窗口
        c1 = b.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 900})
        buyer = c1.new_page()
        buyer.set_default_timeout(20000)

        # ---------- 未登录部分 ----------
        goto(buyer, '/home')
        snap(buyer, '首页（8段分类色带+卡片编号）', '讲：分类按在售量着色，卡片带分类编号')

        goto(buyer, '/search?keyword=教材')
        n = buyer.inner_text('body')
        m = re.search(r'共\s*(\d+)\s*件', n)
        snap(buyer, '搜「教材」', f'共 {m.group(1) if m else "?"} 件（含分类命中的商品）')

        # 找一件别人的、在售的商品
        goto(buyer, '/home')
        cards = buyer.locator('.product-card a, .product-card')
        pid = None
        for i in range(cards.count()):
            href = cards.nth(i).get_attribute('href') or ''
            mm = re.search(r'/product/(\d+)', href)
            if mm:
                pid = mm.group(1)
                break
        if not pid:
            links = buyer.locator('a[href^="/product/"]')
            if links.count():
                pid = re.search(r'/product/(\d+)', links.first.get_attribute('href')).group(1)
        print(f'  使用商品 #{pid} 做演示')
        goto(buyer, f'/product/{pid}')
        snap(buyer, '商品详情（相关推荐+评价区）', '讲：详情、相关推荐、卖家卡片')

        # 点卖家头像 → 卖家主页
        if buyer.locator('.seller-avatar').count():
            buyer.locator('.seller-avatar').first.click()
            buyer.wait_for_timeout(2200)
            snap(buyer, '卖家主页（信用分/已售出/收到的评价）', '加分点：点卖家头像看历史与口碑')
            goto(buyer, f'/product/{pid}')

        # ---------- 买家登录后下单 ----------
        login(buyer, BUYER)
        goto(buyer, f'/product/{pid}')
        buy = buyer.locator('button:visible').filter(has_text='立即购买')
        if not buy.count():
            snap(buyer, '跳过下单（商品不可购买）', '商品可能已被自己占有或非在售')
        else:
            buy.first.click()
            buyer.wait_for_timeout(900)
            for txt in ('确定', '确认'):
                dlg = buyer.locator('.el-message-box__btns button:visible').filter(has_text=txt)
                if dlg.count():
                    dlg.first.click()
                    break
            buyer.wait_for_timeout(2200)
            snap(buyer, '买家下单成功', '商品变「交易中」；下单即锁定，防一物多卖')

        # ---------- 卖家窗口 ----------
        c2 = b.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 900})
        seller = c2.new_page()
        seller.set_default_timeout(20000)
        login(seller, ADMIN)
        seller.wait_for_timeout(2500)
        snap(seller, '卖家顶栏订单徽标', '顶栏「订单」出现红色待办数字')
        seller.locator('.el-menu-item').filter(has_text='订单').first.click()
        seller.wait_for_timeout(2500)
        snap(seller, '卖家「我卖出的」列表', '待交易行有「标记可交付」按钮')

        row = seller.locator('.el-table__row').first
        if row.count():
            detail = row.locator('button').filter(has_text='详情')
            if detail.count():
                detail.first.click()
                seller.wait_for_timeout(2200)
            act = seller.locator('button:visible').filter(has_text='标记可交付')
            if act.count():
                act.first.click()
                seller.wait_for_timeout(700)
                for txt in ('确定', '确认'):
                    dlg = seller.locator('.el-message-box__btns button:visible').filter(has_text=txt)
                    if dlg.count():
                        dlg.first.click()
                        break
                seller.wait_for_timeout(2200)
                snap(seller, '卖家：标记可交付', '状态 0 → 1 待交付')
            act2 = seller.locator('button:visible').filter(has_text='确认已交付')
            if act2.count():
                act2.first.click()
                seller.wait_for_timeout(700)
                for txt in ('确定', '确认'):
                    dlg = seller.locator('.el-message-box__btns button:visible').filter(has_text=txt)
                    if dlg.count():
                        dlg.first.click()
                        break
                seller.wait_for_timeout(2200)
                snap(seller, '卖家：确认已交付', '状态 1 → 2 待收货；卖家不能替买家确认')

        # ---------- 买家确认收货 ----------
        goto(buyer, '/orders/bought')
        snap(buyer, '买家「我买到的」', '待收货行出现「确认收货」')
        recv = buyer.locator('button:visible').filter(has_text='确认收货')
        if recv.count():
            recv.first.click()
            buyer.wait_for_timeout(700)
            for txt in ('确定', '确认'):
                dlg = buyer.locator('.el-message-box__btns button:visible').filter(has_text=txt)
                if dlg.count():
                    dlg.first.click()
                    break
            buyer.wait_for_timeout(2500)
            snap(buyer, '买家确认收货 → 已完成', '商品已售出；双方信用分 +1')

            # 评价
            goto(buyer, '/orders/bought')
            ev = buyer.locator('button:visible').filter(has_text='去评价')
            if ev.count():
                ev.first.click()
                buyer.wait_for_timeout(2200)
                dlg = buyer.locator('.el-dialog').last
                snap(buyer, '评价弹窗（星级/标签/匿名）', '勾选匿名；每单每人一次')
                try:
                    dlg.locator('.el-rate__item').nth(4).click()
                    dlg.locator('textarea').first.fill('彩排：书很新，沟通顺利')
                    dlg.get_by_role('button', name=re.compile('提交评价')).first.click()
                    buyer.wait_for_timeout(2600)
                except Exception as e:
                    print(f'     ⚠️ 评价提交异常: {str(e)[:60]}')
                snap(buyer, '评价提交后', '成功提示或弹窗关闭')

        # ---------- 后台 ----------
        goto(seller, '/admin/products')
        snap(seller, '后台商品审核', '有 1 件待审核：通过/驳回 + 审核意见')
        goto(seller, '/admin/users')
        snap(seller, '后台用户管理', '启用/禁用 + 审计日志')
        d = seller.locator('a[href*="dashboard"], .el-menu-item').filter(has_text='看板')
        if d.count():
            d.first.click()
            seller.wait_for_timeout(2500)
            snap(seller, '后台数据看板', '用户/商品/订单/举报趋势')

        # ---------- 私信 ----------
        goto(buyer, '/messages')
        snap(buyer, '买家消息列表', 'WebSocket 实时 + 已读回执')

        b.close()

    # 生成图册
    md = ['# 演示彩排 · 操作图册（测试环境自动跑）', '',
          f'> 环境：`{BASE}` → `campus_trade_test`（**未触碰演示库**）。共 {len(shots)} 步。',
          '> 用途：上台前照着这组图熟悉操作顺序。', '',
          '| # | 步骤 | 说明 | 截图 |', '| :-: | --- | --- | --- |']
    for i, title, note, name in shots:
        md.append(f'| {i:02d} | {title} | {note} | `{name}` |')
    md += ['', '## 逐步截图', '']
    for i, title, note, name in shots:
        md += [f'### {i:02d}　{title}', '', f'> {note}' if note else '', '', f'![{title}](story-evidence/演示彩排/{name})', '']
    p = pathlib.Path(r'D:\campus-secondhand-trade\docs\演示彩排-操作图册.md')
    p.write_text('\n'.join(md), encoding='utf-8')
    print(f'\n  ✓ 图册已生成：{p.name}（{len(shots)} 步 / {len(shots)} 张图）')


if __name__ == '__main__':
    main()
