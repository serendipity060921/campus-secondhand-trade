#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键自动演示（可见浏览器，慢速播放，控制台同步打印讲稿）

用法：
    · 双击 tools\演示-自动播放.cmd            ← 最省事
    · 或 python tools/demo-play.py            ← 默认打演示环境 8081
    · python tools/demo-play.py --base http://127.0.0.1:8092   ← 打测试库彩排
    · python tools/demo-play.py --fast        ← 每步停顿减半（练习用）
    · python tools/demo-play.py --headless    ← 不开窗口（自检用）

设计要点：
    1. 用 Playwright **有头模式 + slow_mo**，观众能看清每一步点击
    2. 买家 / 卖家 用**两个独立 context**（各自一份 localStorage），所以能同时登两个账号
    3. 每步在控制台打印「步骤 + 讲什么」，你照着念就行
    4. 最后停住不关，等你看完再回车退出
"""
import argparse
import pathlib
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
from playwright.sync_api import sync_playwright

BUYER = ('stu_demo', '123456')
ADMIN = ('admin', '123456')

# 每步停顿（毫秒）；--fast 时减半
PAUSE = 2600

parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://127.0.0.1:8081')
parser.add_argument('--fast', action='store_true')
parser.add_argument('--headless', action='store_true')
args = parser.parse_args()

BASE = args.base.rstrip('/')
P = PAUSE // 2 if args.fast else PAUSE


def say(step, title, script=''):
    print()
    print('  ' + '=' * 66)
    print(f'  步骤 {step}：{title}')
    if script:
        print(f'  讲：{script}')
    print('  ' + '=' * 66)


def hold(page, ms=None):
    page.wait_for_timeout(ms or P)


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
    page.wait_for_timeout(700)


def confirm_box(page):
    """点掉 Element Plus 的确认弹窗"""
    for txt in ('确定', '确认'):
        btn = page.locator('.el-message-box__btns button:visible').filter(has_text=txt)
        if btn.count():
            btn.first.click()
            return True
    return False


def pick_someone_elses_product(buyer_page):
    """在首页挑一件"别人的、在售"的商品"""
    goto(buyer_page, '/home')
    links = buyer_page.locator('a[href^="/product/"]')
    for i in range(links.count()):
        href = links.nth(i).get_attribute('href') or ''
        m = re.search(r'/product/(\d+)', href)
        if m:
            return m.group(1)
    return None


def main():
    print()
    print('  ╔══════════════════════════════════════════════════════════════╗')
    print('  ║   校园二手交易平台 · 自动演示（慢速播放）                      ║')
    print('  ╚══════════════════════════════════════════════════════════════╝')
    print(f'  目标环境：{BASE}')
    print('  提示：窗口里的操作是真实点击；讲稿会同步打印在这里。')
    print('  按 Ctrl+C 可随时中止。')

    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            channel='msedge',   # 统一用系统 Edge（本机没装 Playwright 自带 Chromium）
            headless=args.headless,
            slow_mo=350,
            args=['--start-maximized'] if not args.headless else [],
        )
        # 两个独立 context = 两份登录态（同源也能同时登两个账号）
        c_buy = browser.new_context(locale='zh-CN', viewport=None if not args.headless else {'width': 1440, 'height': 900})
        buyer = c_buy.new_page()
        buyer.set_default_timeout(25000)

        # 1 首页
        say(1, '首页', '8 段分类色带按在售量着色；卡片带分类编号与成色')
        goto(buyer, '/home')
        hold(buyer)

        # 2 搜索
        say(2, '搜索「教材」', '关键词同时匹配标题/描述/分类名，搜教材能找到教材类商品')
        goto(buyer, '/search?keyword=教材')
        hold(buyer)

        # 3 商品详情
        pid = pick_someone_elses_product(buyer)
        if not pid:
            print('  ⚠️ 没找到在售商品，请先发布一件或换环境')
            return
        say(3, f'商品详情（#{pid}）', '详情、相关推荐、交易评价区、卖家卡片')
        goto(buyer, f'/product/{pid}')
        hold(buyer)

        # 4 卖家主页
        if buyer.locator('.seller-avatar').count():
            say(4, '卖家主页（加分点）', '点卖家头像 → 信用分、在售/交易中/已售出、收到的评价')
            buyer.locator('.seller-avatar').first.click()
            buyer.wait_for_timeout(2200)
            hold(buyer, 3200)
            goto(buyer, f'/product/{pid}')

        # 5 买家下单
        say(5, '买家下单', '下单即锁定商品，防一物多卖；商品变「交易中」')
        login(buyer, BUYER)
        goto(buyer, f'/product/{pid}')
        buy = buyer.locator('button:visible').filter(has_text='立即购买')
        if buy.count():
            buy.first.click()
            buyer.wait_for_timeout(800)
            confirm_box(buyer)
            buyer.wait_for_timeout(2200)
        else:
            print('  ⚠️ 「立即购买」不可用（可能是自己发布的商品），跳过下单')
        hold(buyer)

        # 6 卖家窗口
        c_sell = browser.new_context(locale='zh-CN', viewport=None if not args.headless else {'width': 1440, 'height': 900})
        seller = c_sell.new_page()
        seller.set_default_timeout(25000)
        say(6, '卖家登录 + 顶栏待办徽标', '顶栏「订单」出现红色待办数字，点它直达「我卖出的」')
        login(seller, ADMIN)
        seller.wait_for_timeout(2200)
        hold(seller, 3000)
        seller.locator('.el-menu-item').filter(has_text='订单').first.click()
        seller.wait_for_timeout(2200)
        hold(seller)

        # 7 卖家标记可交付
        row = seller.locator('.el-table__row').first
        if row.count():
            d = row.locator('button').filter(has_text='详情')
            if d.count():
                say(7, '打开订单详情', '订单状态机 6 态；待交易由卖家发起交付')
                d.first.click()
                seller.wait_for_timeout(2200)
                hold(seller)
            act = seller.locator('button:visible').filter(has_text='标记可交付')
            if act.count():
                say(8, '卖家：标记可交付', '状态 0 → 1；买家会看到进度更新')
                act.first.click()
                seller.wait_for_timeout(700)
                confirm_box(seller)
                seller.wait_for_timeout(2200)
                hold(seller)
            act2 = seller.locator('button:visible').filter(has_text='确认已交付')
            if act2.count():
                say(9, '卖家：确认已交付', '状态 1 → 2；卖家不能替买家确认完成（越权 6004）')
                act2.first.click()
                seller.wait_for_timeout(700)
                confirm_box(seller)
                seller.wait_for_timeout(2200)
                hold(seller)

        # 10 买家确认收货
        say(10, '买家确认收货', '此时买家才看到「确认收货」；确认后商品已售出、双方信用分 +1')
        goto(buyer, '/orders/bought')
        hold(buyer)
        recv = buyer.locator('button:visible').filter(has_text='确认收货')
        if recv.count():
            recv.first.click()
            buyer.wait_for_timeout(700)
            confirm_box(buyer)
            buyer.wait_for_timeout(2400)
            hold(buyer, 3200)

        # 11 评价（走"订单详情 → 评价这笔交易"，这个入口最稳）
        say(11, '双向评价', '5 星 + 标签 + 勾选匿名；每单每人一次；匿名只隐藏展示层')
        goto(buyer, '/orders/bought')
        first_order = None
        try:
            rows = buyer.locator('.el-table__row')
            if rows.count():
                # 找"已完成"那一行
                for i in range(rows.count()):
                    if '已完成' in rows.nth(i).inner_text():
                        nxt = rows.nth(i).locator('button').filter(has_text='详情')
                        if nxt.count():
                            nxt.first.click()
                            buyer.wait_for_timeout(2200)
                            first_order = True
                        break
        except Exception as e:
            print(f'  ⚠️ 打开订单详情失败：{str(e)[:60]}')

        if first_order:
            try:
                entry = buyer.locator('button:visible').filter(has_text='评价这笔交易')
                if entry.count():
                    entry.first.click()
                    buyer.wait_for_timeout(2000)
                    dlg = buyer.locator('.el-dialog').last
                    dlg.locator('.el-rate__item').nth(4).click()
                    dlg.locator('textarea').first.fill('演示：书很新，沟通顺利')
                    hold(buyer, 2200)
                    dlg.get_by_role('button', name=re.compile('提交评价')).first.click(timeout=8000)
                    buyer.wait_for_timeout(2600)
                    print('  ✓ 评价已提交')
            except Exception as e:
                print(f'  ⚠️ 评价这一步跳过（可手动演示）：{str(e)[:70]}')
        hold(buyer, 3000)
        # 12 后台
        say(12, '管理后台：商品审核', '机审三态：违禁词直接拒、可疑转人工、通过直接上架')
        goto(seller, '/admin/products')
        hold(seller, 3200)
        say(13, '管理后台：用户管理 / 数据看板', '启用禁用 + 审计日志；看板有用户/商品/订单/举报趋势')
        goto(seller, '/admin/users')
        hold(seller)
        dash = seller.locator('.el-menu-item').filter(has_text=re.compile('看板|Dashboard'))
        if dash.count():
            dash.first.click()
            seller.wait_for_timeout(2400)
            hold(seller, 3200)

        # 14 私信
        say(14, '私信与已读回执', 'WebSocket 实时推送；断线自动降级轮询')
        goto(buyer, '/messages')
        hold(buyer, 3000)

        print()
        print('  ' + '=' * 66)
        print('  演示流程结束。窗口保持打开，你可以继续讲解或手动点击。')
        print('  按回车关闭浏览器；或直接关掉窗口。')
        print('  ' + '=' * 66)
        try:
            input()
        except Exception:
            pass
        browser.close()


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\n  已中止。')
