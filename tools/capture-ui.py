# -*- coding: utf-8 -*-
"""全站页面基线截图工具（改版前后对比用）

用途：
  · 改版前采集一组（--label before），改版后再采一组（--label after），
    两组用同一脚本、同一视口、同一登录态，保证对比公平。
  · 每张图附带 provenance（URL、视口、时间、文件哈希），可直接作为论文
    "UI 优化前后对比"一节的证据。

用法：
  python tools/capture-ui.py --base http://127.0.0.1:8081 --label before
  python tools/capture-ui.py --base http://127.0.0.1:8081 --label after
"""
import argparse
import hashlib
import json
import pathlib
import re
import time

from playwright.sync_api import sync_playwright

# 学生视角页面（需要登录 stu_test01）
STUDENT_PAGES = [
    ('home', '/home', '首页 · 商品列表 + 猜你喜欢'),
    ('search', '/search', '搜索与筛选'),
    ('detail', '/product/6', '商品详情'),
    ('publish', '/product/publish', '发布商品'),
    ('my-products', '/product/mine', '我的商品'),
    ('favorites', '/favorites', '我的收藏'),
    ('messages', '/messages', '会话列表'),
    ('chat', '/chat/7', '聊天窗口'),
    ('orders-bought', '/orders/bought', '我买到的'),
    ('orders-sold', '/orders/sold', '我卖出的'),
    ('profile', '/profile', '个人中心'),
]

# 未登录页面
PUBLIC_PAGES = [
    ('login', '/login', '登录'),
    ('register', '/register', '注册'),
]

# 管理员视角页面
ADMIN_PAGES = [
    ('admin-dashboard', '/admin/dashboard', '管理后台 · 数据看板'),
    ('admin-products', '/admin/products', '管理后台 · 商品审核'),
    ('admin-users', '/admin/users', '管理后台 · 用户管理'),
    ('admin-reports', '/admin/reports', '管理后台 · 举报处理'),
]

VIEWPORTS = [('desktop', 1440, 1000), ('mobile', 390, 844)]


def login(page, base, username, password):
    page.goto(f'{base}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(username)
    page.get_by_placeholder('请输入密码').fill(password)
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'/home'), timeout=30000)
    page.wait_for_timeout(1500)


def capture(page, base, out_dir, label, name, path, desc, vp_name, width, height, shots):
    page.set_viewport_size({'width': width, 'height': height})
    url = base + path
    page.goto(url, wait_until='domcontentloaded')
    # 等网络与列表渲染稳定（骨架/动画结束后再截，避免截到半加载态）
    try:
        page.wait_for_load_state('networkidle', timeout=12000)
    except Exception:
        pass
    page.wait_for_timeout(1200)
    file = out_dir / f'{label}-{name}-{vp_name}.png'
    page.screenshot(path=str(file), full_page=True)
    raw = file.read_bytes()
    shots.append({
        'page': name,
        'desc': desc,
        'url': url,
        'viewport': f'{width}x{height}',
        'file': file.as_posix(),
        'bytes': len(raw),
        'sha256': hashlib.sha256(raw).hexdigest()[:16],
        'capturedAt': time.strftime('%Y-%m-%d %H:%M:%S'),
    })
    print(f'   [{label}] {name:16s} {vp_name:7s} {len(raw)//1024:5d} KB  {path}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://127.0.0.1:8081')
    ap.add_argument('--label', default='before')
    ap.add_argument('--out', default=r'D:\campus-secondhand-trade\.impeccable\review')
    ap.add_argument('--student', default='stu_test01:abc12345')
    ap.add_argument('--admin', default='admin:123456')
    args = ap.parse_args()

    out_dir = pathlib.Path(args.out) / args.label
    out_dir.mkdir(parents=True, exist_ok=True)
    stu_u, stu_p = args.student.split(':')
    adm_u, adm_p = args.admin.split(':')
    shots = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)

        # 未登录
        ctx = browser.new_context(locale='zh-CN')
        page = ctx.new_page()
        page.set_default_timeout(30000)
        print('未登录页面：')
        for name, path, desc in PUBLIC_PAGES:
            for vp, w, h in VIEWPORTS:
                capture(page, args.base, out_dir, args.label, name, path, desc, vp, w, h, shots)
        ctx.close()

        # 学生
        ctx = browser.new_context(locale='zh-CN')
        page = ctx.new_page()
        page.set_default_timeout(30000)
        login(page, args.base, stu_u, stu_p)
        print('学生页面：')
        for name, path, desc in STUDENT_PAGES:
            for vp, w, h in VIEWPORTS:
                capture(page, args.base, out_dir, args.label, name, path, desc, vp, w, h, shots)
        ctx.close()

        # 管理员
        ctx = browser.new_context(locale='zh-CN')
        page = ctx.new_page()
        page.set_default_timeout(30000)
        login(page, args.base, adm_u, adm_p)
        print('管理员页面：')
        for name, path, desc in ADMIN_PAGES:
            for vp, w, h in VIEWPORTS:
                capture(page, args.base, out_dir, args.label, name, path, desc, vp, w, h, shots)
        ctx.close()
        browser.close()

    manifest = out_dir / 'manifest.json'
    manifest.write_text(json.dumps({
        'label': args.label,
        'base': args.base,
        'viewports': [f'{w}x{h}' for _, w, h in VIEWPORTS],
        'count': len(shots),
        'shots': shots,
    }, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'\n共 {len(shots)} 张 → {out_dir}')
    print(f'清单（含 URL/视口/哈希，可作论文证据）：{manifest}')


if __name__ == '__main__':
    main()
