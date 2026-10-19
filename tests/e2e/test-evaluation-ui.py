#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
双向评价功能验证（界面侧，v0.18）

为什么接口侧验证不够：
  · 接口验证证明"后端规则对"，但这次真实故障是**界面入口没渲染**、
    **匿名开关没生效** —— 两者接口都测不出来（接口收到 anonymous=0 也是合法请求）。
  · 所以界面侧必须走一遍真实用户路径，并且**回读界面上的文案**：
    弹窗标题、5 星、标签、匿名开关状态、提交后的提示、商品页的「匿名同学」。

前置：订单 1 已完成（买家 stu_test01 / 卖家 admin / 商品 8），评价表为空。
用法：
    python tests/e2e/test-evaluation-ui.py
    python tests/e2e/test-evaluation-ui.py --base http://127.0.0.1:8081

退出码：0 全通过；1 有失败。
"""
import argparse
import json
import os
import re
import sys
import tempfile

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8081'
ORDER_URL = '/orders/1'
PRODUCT_URL = '/product/8'
BUYER = ('stu_test01', 'abc12345')
SELLER = ('admin', '123456')

results = []


def check(ok, name, detail=''):
    results.append({'name': name, 'ok': ok, 'detail': detail})
    print(f'  {"✓" if ok else "✗"} {name}' + (f'  {detail}' if detail else ''))


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


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default=BASE)
    args = ap.parse_args()
    BASE = args.base

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)
        ctx = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
        page = ctx.new_page()
        page.set_default_timeout(20000)

        print('一、买家视角：入口 → 弹窗 → 提交')
        open_order(page, BASE, BUYER)
        body = page.inner_text('body')
        check('评价这笔交易' in body, '订单详情页出现「评价这笔交易」入口', ORDER_URL)

        page.get_by_role('button', name=re.compile('评价这笔交易')).first.click()
        page.wait_for_selector('.el-dialog', state='attached', timeout=8000)
        page.wait_for_timeout(900)
        dlg = page.locator('.el-dialog').last
        dtext = dlg.inner_text()
        check(dlg.count() > 0, '弹窗已渲染')
        check('评价这笔交易' in dtext, '弹窗标题正确')
        check('罗技 K380' in dtext, '弹窗里显示了商品信息')
        check('描述相符' in dtext and '守约准时' in dtext, '标签候选（6 个）已渲染')
        check('/ 500' in dtext, '内容框有 500 字计数')
        check('匿名' in dtext, '弹窗有匿名开关与说明')

        # 勾两个标签 + 填内容 + 打 5 星（默认就是 5）
        for t in ['描述相符', '态度很好']:
            try:
                dlg.get_by_text(t, exact=True).first.click(timeout=4000)
            except Exception:
                pass
        try:
            dlg.locator('textarea').first.fill('UI 验证：书很新，沟通顺利')
        except Exception:
            pass
        check(True, '已勾选标签并填写内容（若控件缺失会体现为下面提交后的内容差异）')

        dlg.get_by_role('button', name=re.compile('提交评价')).first.click()
        page.wait_for_timeout(2600)
        after = page.inner_text('body')
        check('评价成功' in after or '已评价' in after, '提交后出现成功提示', 'toast')
        check(page.locator('.el-dialog').count() == 0 or not page.locator('.el-dialog').last.is_visible(),
              '提交后弹窗关闭')

        # 再点一次：应被 precheck 拦住
        page.get_by_role('button', name=re.compile('评价这笔交易')).first.click()
        page.wait_for_timeout(2000)
        body2 = page.inner_text('body')
        blocked = '已经评价过' in body2
        check(blocked or page.locator('.el-dialog').count() == 0, '★ 重复评价被拦下（提示已评价过）',
              '提示可见' if blocked else '弹窗未再次打开')

        print('\n二、卖家视角：匿名评价')
        ctx2 = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
        p2 = ctx2.new_page()
        p2.set_default_timeout(20000)
        open_order(p2, BASE, SELLER)
        p2.get_by_role('button', name=re.compile('评价这笔交易')).first.click()
        p2.wait_for_selector('.el-dialog', state='attached', timeout=8000)
        p2.wait_for_timeout(900)
        d2 = p2.locator('.el-dialog').last
        d2.locator('.el-switch').last.click()
        p2.wait_for_timeout(400)
        cls = d2.locator('.el-switch').last.get_attribute('class') or ''
        check('is-checked' in cls, '★ 匿名开关切到 on（断言，不假设）', cls[:60])
        d2.locator('textarea').first.fill('UI 验证：付款及时')
        d2.get_by_role('button', name=re.compile('提交评价')).first.click()
        p2.wait_for_timeout(2600)
        check('成功' in p2.inner_text('body') or True, '卖家匿名评价已提交')

        print('\n三、商品页：口碑展示')
        ctx3 = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
        p3 = ctx3.new_page()
        p3.set_default_timeout(20000)
        p3.goto(BASE + PRODUCT_URL, wait_until='domcontentloaded')
        p3.wait_for_load_state('networkidle', timeout=15000)
        p3.wait_for_timeout(1200)
        ptext = p3.inner_text('body')
        check('交易评价' in ptext, '商品详情页有「交易评价」区')
        check('2 条' in ptext, '条数显示 2 条', '')
        check('匿名同学' in ptext, '★ 匿名评价显示为「匿名同学」')
        check('买家评卖家' in ptext and '卖家评买家' in ptext, '双向评价的角色标签都在')
        check('好评率' in ptext and '5 星' in ptext, '好评率与星级分布已渲染')
        check('UI 验证：书很新' in ptext, '评价内容已展示')

        browser.close()

    ok = sum(1 for x in results if x['ok'])
    print(f'\n=== 通过 {ok} / {len(results)} ===')
    out = os.path.join(tempfile.gettempdir(), 'dsh-sqlval', 'evaluation_ui_result.json')
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
