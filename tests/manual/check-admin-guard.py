# -*- coding: utf-8 -*-
"""查证：不同角色访问 /admin/dashboard 时，前端路由守卫是否拦截"""
import pathlib
import re
import time

from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8081'
OUT = pathlib.Path.home() / 'AppData' / 'Local' / 'Temp' / 'dsh-sqlval' / 'guard-check'
OUT.mkdir(parents=True, exist_ok=True)

READ_ROLE = """() => {
  const pick = (k) => { try { return JSON.parse(localStorage.getItem(k) || '{}'); } catch (e) { return {}; } };
  const a = pick('userInfo'), b = pick('user');
  return a.role ?? b.role ?? null;
}"""

with sync_playwright() as pw:
    browser = pw.chromium.launch(channel='msedge', headless=True)
    for user, pwd, tag in [('stu_test01', 'abc12345', 'seller'),
                           ('stu_demo', '123456', 'buyer'),
                           ('admin', '123456', 'admin')]:
        ctx = browser.new_context(viewport={'width': 1440, 'height': 900})
        page = ctx.new_page()
        page.goto(BASE + '/login', wait_until='domcontentloaded')
        page.get_by_placeholder('请输入用户名').fill(user)
        page.get_by_placeholder('请输入密码').fill(pwd)
        page.get_by_role('button', name=re.compile('登')).first.click()
        page.wait_for_url(re.compile(r'^(?!.*login).*'), timeout=20000)
        time.sleep(1)
        role = page.evaluate(READ_ROLE)
        print(f'  [{tag}] {user}：localStorage 里的 role = {role!r}')
        page.goto(BASE + '/admin/dashboard', wait_until='domcontentloaded')
        time.sleep(5)
        print(f'        访问 /admin/dashboard 等 5 秒后 → {page.url}')
        page.screenshot(path=str(OUT / f'{tag}-admin.png'))
        ctx.close()
    browser.close()
print(f'  截图目录：{OUT}')
