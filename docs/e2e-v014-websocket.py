# -*- coding: utf-8 -*-
"""v0.14 WebSocket 实时私信 —— 双浏览器端到端验证

核心验证点（这是"实时"最有说服力的证据）：
  1. 两个浏览器上下文分别登录两个账号，各自打开与对方的聊天窗口；
  2. A 发消息 → B 的页面**不刷新**就能看到（服务端推送，而不是轮询）；
  3. B 回复 → A 同样实时收到，且 A 侧消息显示"已读"（已读回执）；
  4. 顶部导航"消息"角标随未读变化；不在聊天页时收到新消息会弹出提醒；
  5. 聊天窗口/会话列表显示对方在线状态。
"""
import os
import pathlib
import re
import time

from playwright.sync_api import sync_playwright

# 入口可用环境变量覆盖：E2E_BASE=http://127.0.0.1:8081 即可验证 Nginx 部署形态
BASE = os.environ.get('E2E_BASE', 'http://127.0.0.1:5173')
EV = pathlib.Path(r'D:\campus-secondhand-trade\docs\test-evidence')
EV.mkdir(parents=True, exist_ok=True)
STU_ID, DEMO_ID = 5, 7          # stu_test01 / stu_demo


def login(page, username, password):
    page.goto(f'{BASE}/login')
    page.get_by_placeholder('请输入用户名').fill(username)
    page.get_by_placeholder('请输入密码').fill(password)
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile('/home'))
    page.wait_for_timeout(1600)      # 等 WebSocket 建立


def bubble_texts(page):
    """聊天区所有气泡文本"""
    return page.locator('.bubble').all_inner_texts()


with sync_playwright() as pw:
    browser = pw.chromium.launch(channel='msedge', headless=True)
    ctx_a = browser.new_context(viewport={'width': 1280, 'height': 860}, locale='zh-CN')
    ctx_b = browser.new_context(viewport={'width': 1280, 'height': 860}, locale='zh-CN')
    a, b = ctx_a.new_page(), ctx_b.new_page()
    a.set_default_timeout(25000)
    b.set_default_timeout(25000)
    errors = []
    a.on('pageerror', lambda e: errors.append(f'A:{e}'))
    b.on('pageerror', lambda e: errors.append(f'B:{e}'))

    print('1) 两个账号分别登录并打开聊天窗口')
    login(a, 'stu_test01', 'abc12345')
    login(b, 'stu_demo', '123456')
    a.goto(f'{BASE}/chat/{DEMO_ID}')
    b.goto(f'{BASE}/chat/{STU_ID}')
    a.wait_for_timeout(2500)
    b.wait_for_timeout(2500)
    print(f'   A 打开与 stu_demo 的聊天，历史 {len(bubble_texts(a))} 条气泡')
    print(f'   B 打开与 stu_test01 的聊天，历史 {len(bubble_texts(b))} 条气泡')

    print('\n2) 在线状态展示')
    online_a = a.locator('.online-tag').first.inner_text().strip()
    online_b = b.locator('.online-tag').first.inner_text().strip()
    print(f'   A 看到对方：{online_a}　B 看到对方：{online_b}')

    print('\n3) A 发消息 → B 不刷新即可看到（服务端推送）')
    msg_a = f'实时测试：A 在 {time.strftime("%H:%M:%S")} 发的消息'
    before_b = len(bubble_texts(b))
    a.get_by_placeholder(re.compile('输入|消息')).first.fill(msg_a)
    a.keyboard.press('Enter')
    got = False
    for _ in range(20):                      # 最多等 4 秒
        b.wait_for_timeout(200)
        if any(msg_a in t for t in bubble_texts(b)):
            got = True
            break
    after_b = len(bubble_texts(b))
    print(f'   B 页面未刷新收到消息：{got}（气泡 {before_b} → {after_b}）')
    a.screenshot(path=str(EV / 'v014-1-实时私信A侧.png'), full_page=True)

    print('\n4) B 回复 → A 实时收到，且 A 侧消息变为已读')
    msg_b = f'实时测试：B 的回复 {time.strftime("%H:%M:%S")}'
    b.get_by_placeholder(re.compile('输入|消息')).first.fill(msg_b)
    b.keyboard.press('Enter')
    got_reply = False
    for _ in range(20):
        a.wait_for_timeout(200)
        if any(msg_b in t for t in bubble_texts(a)):
            got_reply = True
            break
    a.wait_for_timeout(1200)                 # 等已读回执
    read_marks = a.locator('.read-status, .read, .is-read').all_inner_texts()
    print(f'   A 实时收到回复：{got_reply}')
    a.screenshot(path=str(EV / 'v014-2-实时私信双向.png'), full_page=True)
    b.screenshot(path=str(EV / 'v014-3-实时私信B侧.png'), full_page=True)

    print('\n5) 不在聊天页时的未读角标与弹窗提醒')
    b.goto(f'{BASE}/home')
    b.wait_for_timeout(1800)
    badge_before = b.locator('.msg-badge').count()
    msg_c = f'实时测试：B 离开聊天页后 A 发来的消息 {time.strftime("%H:%M:%S")}'
    a.get_by_placeholder(re.compile('输入|消息')).first.fill(msg_c)
    a.keyboard.press('Enter')
    b.wait_for_timeout(2500)
    badge_after = b.locator('.msg-badge').count()
    notice = b.locator('.el-notification').count()
    badge_text = b.locator('.msg-badge').first.inner_text().strip() if badge_after else '无'
    print(f'   B 顶栏消息角标：{badge_before} → {badge_after}（值：{badge_text}），弹窗提醒数：{notice}')
    b.screenshot(path=str(EV / 'v014-4-未读角标与提醒.png'), full_page=True)

    print('\n6) 会话列表在线状态')
    b.goto(f'{BASE}/messages')
    b.wait_for_timeout(2200)
    online_rows = b.locator('.online.on').count()
    print(f'   会话列表中显示"在线"的行数：{online_rows}')
    b.screenshot(path=str(EV / 'v014-5-会话列表在线状态.png'), full_page=True)

    print('\n7) 刷新页面后连接自动恢复')
    a.reload()
    a.wait_for_timeout(3000)
    online_after_reload = a.locator('.online-tag').first.inner_text().strip()
    print(f'   A 刷新后对方状态：{online_after_reload}')

    print(f'\n   页面 JS 错误：{errors if errors else "无"}')
    browser.close()

print('\n截图已保存到', EV)
