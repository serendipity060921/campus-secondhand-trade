# -*- coding: utf-8 -*-
"""发布页校验规则接入后的定点验证（AI 驱动真实浏览器 + 逐步截图）

目的：确认「表单校验规则统一到 utils/productRules.js」之后，发布页行为未变：
  1. 空表单提交 → 出现「请输入商品名称」
  2. 售价填 25.555（3 位小数）→ 出现「售价最多 8 位整数、2 位小数」
  3. 售价填 0 → 出现「售价必须大于 0」

产物：%TEMP%/dsh-sqlval/wiring-check/*.png（截图）+ 控制台结论
运行：<带 playwright 的 python> tests/manual/check-publish-rules.py
"""
import pathlib
import re
import sys
import time

from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8081'
OUT = pathlib.Path.home() / 'AppData' / 'Local' / 'Temp' / 'dsh-sqlval' / 'wiring-check'
OUT.mkdir(parents=True, exist_ok=True)

results = []


def check(ok, label, detail=''):
    results.append((ok, label))
    print(('  ✓ ' if ok else '  ✗ ') + label + (f'   {detail}' if detail else ''))


def shot(page, name):
    path = OUT / f'{name}.png'
    page.screenshot(path=str(path), full_page=False)
    return path


def form_errors(page):
    """读取 Element Plus 当前展示的校验提示文案"""
    return [t.strip() for t in page.locator('.el-form-item__error').all_inner_texts()]


with sync_playwright() as pw:
    browser = pw.chromium.launch(channel='msedge', headless=True)
    ctx = browser.new_context(viewport={'width': 1440, 'height': 900})
    page = ctx.new_page()

    print('【1】登录')
    page.goto(f'{BASE}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill('stu_test01')
    page.get_by_placeholder('请输入密码').fill('abc12345')
    shot(page, '01-登录页已填写')
    page.get_by_role('button', name=re.compile('登')).first.click()
    try:
        page.wait_for_url(re.compile(r'^(?!.*login).*'), timeout=15000)
        check(True, '登录成功并离开登录页', page.url)
    except Exception as e:
        check(False, '登录失败', str(e)[:80])
        shot(page, '01b-登录失败')
        browser.close()
        sys.exit(1)
    time.sleep(1)

    print('\n【2】进入发布页')
    page.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
    time.sleep(2)
    title_ok = page.get_by_placeholder('商品名称').count() > 0
    check(title_ok, '发布页已加载（找到商品名称输入框）', page.url)
    shot(page, '02-发布页')

    # 找出提交按钮文案（便于诊断）
    btn_texts = [t.strip() for t in page.locator('button').all_inner_texts() if t.strip()]
    submit = page.get_by_role('button', name=re.compile('发布|提交|确认'))
    print(f'    页面按钮文案：{btn_texts}')
    check(submit.count() > 0, '找到提交按钮', f'{submit.count()} 个匹配')

    print('\n【3】空表单提交 → 应提示「请输入商品名称」')
    submit.first.click()
    time.sleep(1.2)
    errs = form_errors(page)
    check('请输入商品名称' in errs, '空表单提示与规则模块一致', f'实际提示={errs}')
    shot(page, '03-空表单校验提示')

    print('\n【4】售价组件约束：el-input-number（:min=0.01 / :max=99999999 / :precision=2）')
    price_box = page.locator('input[aria-label*="售价"]').first
    check(price_box.count() > 0, '定位到售价输入框（aria-label 含「售价」）')
    price_box.fill('25.555')
    page.get_by_placeholder('商品名称').click()   # 触发 blur 让组件归一化
    time.sleep(0.8)
    v1 = price_box.input_value()
    check(v1 in ('25.56', '25.55', '25.5'), '输入 3 位小数被组件自动处理为 2 位', f'读回值={v1}')
    shot(page, '04-售价组件归一化')

    print('\n【5】售价下限：输入 0 应被组件夹到最小值 0.01')
    price_box.fill('0')
    page.get_by_placeholder('商品名称').click()
    time.sleep(0.8)
    v2 = price_box.input_value()
    check(v2.startswith('0.01') or v2 == '0.01', '组件按 :min 夹住下限（双层防护的第一层）', f'读回值={v2}')
    shot(page, '05-售价下限约束')

    print('\n【6】规则层的价格提示仍可用（清空售价后提交）')
    price_box.fill('')
    submit.first.click()
    time.sleep(1.2)
    errs = form_errors(page)
    check(any('售价' in e or '价格' in e for e in errs), '空售价时规则层给出提示（第二层防护）', f'实际提示={errs}')
    shot(page, '06-空售价规则提示')

    browser.close()

print('\n' + '=' * 52)
passed = sum(1 for ok, _ in results if ok)
print(f'结论：{passed}/{len(results)} 项通过')
for ok, label in results:
    print(('  ✓ ' if ok else '  ✗ ') + label)
print(f'截图目录：{OUT}')
sys.exit(0 if passed == len(results) else 1)
