#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
订单状态机·界面验证（v0.18）

验证"按钮真的能用"，而不只是接口能用：
  订单 4（买家 stu_demo / 卖家 admin，初始「待交易」）
  卖家：标记可交付(→1) → 确认已交付(→2)
  买家：确认收货(→3)   → 商品变「已售出」→ 不再有操作按钮

每一步都断言页面上的**状态文案**与**按钮集合**，并落截图（docs/story-evidence/）。

前置：演示数据处于基线（order 4 status=0）。
用法：python tests/e2e/test-order-state-ui.py
退出码：0 全通过；1 有失败。
"""
import argparse
import json
import os
import pathlib
import re
import sys
import tempfile

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8081'
ORDER_URL = '/orders/4'
SELLER = ('admin', '123456')      # order 4 的卖家
BUYER = ('stu_demo', '123456')    # order 4 的买家
SHOT_DIR = pathlib.Path(__file__).resolve().parents[2] / 'docs' / 'story-evidence'

results = []


def check(ok, name, detail=''):
    results.append({'name': name, 'ok': ok, 'detail': detail})
    print(f'  {"✓" if ok else "✗"} {name}' + (f'  {detail}' if detail else ''))


def snap(page, name, note=''):
    try:
        SHOT_DIR.mkdir(parents=True, exist_ok=True)
        p = SHOT_DIR / f'{name}.png'
        page.screenshot(path=str(p), full_page=True)
        print(f'    📷 {name}.png  {p.stat().st_size // 1024} KB' + (f'  {note}' if note else ''))
    except Exception as e:
        print(f'    ⚠️ 截图失败 {name}: {str(e)[:60]}')


def login(page, base, cred):
    page.goto(f'{base}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(cred[0])
    page.get_by_placeholder('请输入密码').fill(cred[1])
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'/home'), timeout=30000)
    page.wait_for_timeout(1200)


def open_order(page, base, cred):
    login(page, base, cred)
    page.goto(base + ORDER_URL, wait_until='domcontentloaded')
    page.wait_for_load_state('networkidle', timeout=15000)
    page.wait_for_timeout(1000)


def buttons(page):
    return [t.strip() for t in page.locator('.actions button').all_inner_texts()]


def click_action(page, label):
    """点操作按钮 → 处理确认框（confirm 直接确定；prompt 填原因后确定）"""
    page.locator('.actions').get_by_role('button', name=re.compile(label)).first.click()
    page.wait_for_timeout(700)
    # ElMessageBox: 确定按钮文案为「确认」/「确认取消」，prompt 另有一个输入框
    for name in ['确认', '确认取消', '确定']:
        try:
            box = page.locator('.el-message-box')
            if box.count() == 0:
                break
            txt = box.last.locator('input, textarea')
            if txt.count() > 0:
                try:
                    txt.first.fill('界面验证')
                except Exception:
                    pass
            box.last.get_by_role('button', name=re.compile(name)).first.click(timeout=3000)
            break
        except Exception:
            continue
    page.wait_for_timeout(2200)


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default=BASE)
    args = ap.parse_args()
    BASE = args.base

    with sync_playwright() as pw:
        b = pw.chromium.launch(channel='msedge', headless=True)

        print('一、卖家视角：待交易 → 待交付 → 待收货')
        ctx = b.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
        p = ctx.new_page()
        p.set_default_timeout(20000)
        open_order(p, BASE, SELLER)

        body = p.inner_text('body')
        check('待交易' in body, '初始状态为「待交易」')
        btns = buttons(p)
        check('标记可交付' in btns, '卖家能看到「标记可交付」', str(btns))
        check('确认交易完成' in btns and '取消订单' in btns, '同时保留一步完成与取消', str(btns))
        snap(p, 'US-ORDER-05-验证-待交易卖家三按钮', '标记可交付/确认交易完成/取消订单')

        click_action(p, '标记可交付')
        body = p.inner_text('body')
        check('待交付' in body, '★ 标记可交付后状态变为「待交付」')
        btns = buttons(p)
        check('确认已交付' in btns, '待交付阶段出现「确认已交付」', str(btns))
        check('申请退款' in btns, '待交付阶段可「申请退款」', str(btns))
        snap(p, 'US-ORDER-06-验证-待交付卖家', '确认已交付/申请退款')

        click_action(p, '确认已交付')
        body = p.inner_text('body')
        check('待收货' in body, '★ 确认已交付后状态变为「待收货」')
        ctx.close()

        print('\n二、买家视角：待收货 → 已完成')
        ctx2 = b.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
        p2 = ctx2.new_page()
        p2.set_default_timeout(20000)
        open_order(p2, BASE, BUYER)
        btns2 = buttons(p2)
        check('确认收货' in btns2, '买家在待收货阶段看到「确认收货」', str(btns2))
        check('确认已交付' not in btns2, '★ 买家看不到卖家专属动作（越权防护）', str(btns2))
        snap(p2, 'US-ORDER-07-验证-待收货买家', '确认收货/申请退款')

        click_action(p2, '确认收货')
        body2 = p2.inner_text('body')
        check('已完成' in body2, '★ 确认收货后订单「已完成」')
        check('该订单已完成' in body2, '终态提示出现（商品已售出、信用分 +1）')
# 注意："← 返回订单列表"是导航按钮，不是状态操作按钮 —— 断言只看状态机相关的那几个
        STATE_BTNS = ['标记可交付', '确认已交付', '确认收货', '申请退款', '确认交易完成', '取消订单']
        leftover = [b for b in buttons(p2) if b in STATE_BTNS]
        check(leftover == [], '★ 终态没有状态操作按钮', str(buttons(p2)))
        snap(p2, 'US-ORDER-08-验证-已完成终态', '无按钮 + 终态提示')
        ctx2.close()
        b.close()

    ok = sum(1 for x in results if x['ok'])
    print(f'\n=== 通过 {ok} / {len(results)} ===')
    out = os.path.join(tempfile.gettempdir(), 'dsh-sqlval', 'order_state_ui_result.json')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8') as f:
        json.dump({'base': BASE, 'results': results}, f, ensure_ascii=False, indent=2)
    print(f'结果已写入 {out}')
    bad = [x['name'] for x in results if not x['ok']]
    for n in bad:
        print('  ✗ ' + n)
    return 0 if not bad else 1


if __name__ == '__main__':
    sys.exit(main())
