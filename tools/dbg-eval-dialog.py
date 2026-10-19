# -*- coding: utf-8 -*-
"""
评价弹窗调试脚本（v0.18）

用法：
    python tools/_dbg_eval.py                 # 订单详情页（stu_test01，订单1买家）
    python tools/_dbg_eval.py --url /orders/1 --user stu_test01 --pass abc12345

它做四件事：
  1. 打开指定页面并登录；
  2. 打印页面上所有按钮文案（确认入口在哪）；
  3. 点击「评价这笔交易」，抓 console / pageerror / 未处理 rejection / 网络请求；
  4. 打印 .el-dialog 数量与 body 是否出现弹窗文案。
"""
import argparse
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8081'

ap = argparse.ArgumentParser()
ap.add_argument('--url', default='/orders/1')
ap.add_argument('--user', default='stu_test01')
ap.add_argument('--pass', dest='pwd', default='abc12345')
ap.add_argument('--button', default='评价这笔交易')
args = ap.parse_args()

with sync_playwright() as pw:
    b = pw.chromium.launch(channel='msedge', headless=True)
    ctx = b.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
    p = ctx.new_page()
    p.set_default_timeout(20000)

    logs, reqs = [], []
    p.on('console', lambda m: logs.append(f'[{m.type}] {m.text}'[:300]))
    p.on('pageerror', lambda e: logs.append(f'[pageerror] {e}'[:300]))
    p.on('request', lambda r: reqs.append(r.method + ' ' + r.url.split('/api/')[-1])
         if '/api/' in r.url else None)

    p.add_init_script("""
      window.__errs = [];
      window.addEventListener('unhandledrejection', e => window.__errs.push(
        'UNHANDLED: ' + (e.reason && (e.reason.stack || e.reason.message) || String(e.reason))));
    """)

    p.goto(f'{BASE}/login', wait_until='domcontentloaded')
    p.get_by_placeholder('请输入用户名').fill(args.user)
    p.get_by_placeholder('请输入密码').fill(args.pwd)
    p.get_by_role('button', name=re.compile(r'登\s*录')).click()
    p.wait_for_url(re.compile(r'/home'), timeout=30000)
    p.wait_for_timeout(1200)

    p.goto(f'{BASE}{args.url}', wait_until='domcontentloaded')
    p.wait_for_load_state('networkidle', timeout=15000)
    p.wait_for_timeout(1000)

    print('页面按钮:', [t.strip() for t in p.locator('button').all_inner_texts()])
    print('body 含入口文案:', args.button in p.inner_text('body'))

    logs.clear()
    reqs.clear()
    try:
        p.get_by_role('button', name=re.compile(args.button)).first.click(timeout=8000)
        print(f'已点击「{args.button}」')
    except Exception as e:
        print('点击失败:', str(e)[:200])
    p.wait_for_timeout(2600)

    print('--- 点击后的 api 请求 ---')
    for r in reqs:
        print('   ', r)
    print('--- .el-dialog 数:', p.locator('.el-dialog').count())
    print('--- body 含「评价这笔交易」标题:', '评价这笔交易' in p.inner_text('body'))
    print('--- 未处理 rejection:', p.evaluate('() => window.__errs'))
    print('--- console ---')
    for line in logs:
        print('   ', line)
    b.close()
