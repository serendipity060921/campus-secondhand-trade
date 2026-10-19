#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用户故事截图证据采集（v0.18）

职责：按**用户故事 + 验收标准**取证。文件名以 `US-模块-序号-N-说明.png` 开头，
便于在报告里逐条嵌入。

与 capture-ui.py 的分工：
  · capture-ui.py —— 按"页面 × 视口"批量截全站，用于视觉走查；
  · 本脚本 —— 按"这条截图为哪条故事的哪个验收标准作证"组织，用于需求追溯。

设计原则
  1. 可重复执行：同名文件覆盖，不累积垃圾；
  2. 失败不中断：单个场景失败只记录，其余继续；
  3. 默认不改数据：唯一写数据的是评价场景（US-EVAL），由调用方清理；
  4. 弹窗一律**整页截图** —— 弹窗挂在 body 下，元素截图截不到（踩过）；
  5. 登录写法照抄 capture-ui.py（按钮文案是「登 录」，中间有空格）。

用法
    python tools/capture-story-evidence.py                 # 全部
    python tools/capture-story-evidence.py --only EVAL     # 只跑含 EVAL 的场景
    python tools/capture-story-evidence.py --with-limits   # 额外采限流提示（会占满登录限流窗口）
"""
import argparse
import json
import pathlib
import re
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs' / 'story-evidence'

STU = ('stu_demo', '123456')         # id=7  订单4买家（待交易），有收藏与会话
SELLER = ('stu_test01', 'abc12345')  # id=5  订单1买家（已完成），有在售商品
ADMIN = ('admin', '123456')          # id=1  订单1卖家（已完成）

ONLY = ''
results = []


def want(tag):
    """场景是否要跑。三向匹配，避免把方向写反（'"'"'EVAL-02'"'"' in '"'"'EVAL'"'"' 恒为假）。"""
    if not ONLY:
        return True
    return ONLY in tag or tag in ONLY


def log(ok, name, detail=''):
    mark = 'OK' if ok else 'XX'
    print(f'  [{mark}] {name}' + (f'  {detail}' if detail else ''))
    results.append({'name': name, 'ok': ok, 'detail': detail})


def shot(page, name, selector=None, note=''):
    """截一张证据图。给 selector 时优先元素截图，失败退回整页。"""
    path = OUT / f'{name}.png'
    try:
        page.wait_for_load_state('networkidle', timeout=15000)
    except Exception:
        pass
    page.wait_for_timeout(600)
    try:
        if selector:
            loc = page.locator(selector).first
            loc.scroll_into_view_if_needed(timeout=5000)
            page.wait_for_timeout(300)
            loc.screenshot(path=str(path))
        else:
            page.screenshot(path=str(path), full_page=True)
        log(True, name, f'{path.stat().st_size // 1024} KB' + (f'  {note}' if note else ''))
    except Exception:
        try:
            page.screenshot(path=str(path), full_page=True)
            log(True, name, f'{path.stat().st_size // 1024} KB（元素未命中，退回整页）')
        except Exception as e2:
            log(False, name, f'截图失败: {str(e2)[:80]}')


def login(page, base, cred):
    page.goto(f'{base}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(cred[0])
    page.get_by_placeholder('请输入密码').fill(cred[1])
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'/home'), timeout=30000)
    page.wait_for_timeout(1500)


def goto(page, base, path):
    page.goto(f'{base}{path}', wait_until='domcontentloaded')
    try:
        page.wait_for_load_state('networkidle', timeout=15000)
    except Exception:
        pass
    page.wait_for_timeout(700)


def click_button(page, names, timeout=6000):
    for n in names:
        try:
            page.get_by_role('button', name=re.compile(n)).first.click(timeout=timeout)
            return True
        except Exception:
            continue
    return False


def wait_dialog(page, timeout=8000):
    """等弹窗挂到 DOM 上。

    这里刻意用 state='attached' 而不是 'visible'：Element Plus 的弹窗先挂载、
    再播放入场过渡，抓 'visible' 会卡在"已存在但还没有可见区域"的瞬间而超时
    （之前就是因此误判成"弹窗没出现"）。挂载后再等 900ms 让动画结束。
    """
    try:
        page.wait_for_selector('.el-dialog, .el-drawer', state='attached', timeout=timeout)
        page.wait_for_timeout(900)
        return True
    except Exception:
        return False


def dialog_button(page, names):
    """只在弹窗范围内找按钮，避免误点列表里的同名按钮。"""
    for n in names:
        try:
            page.locator('.el-dialog').last.get_by_role(
                'button', name=re.compile(n)).first.click(timeout=5000)
            return True
        except Exception:
            continue
    return False


# ------------------------- 场景 -------------------------

def run_public(base, browser):
    ctx = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
    page = ctx.new_page()
    page.set_default_timeout(20000)
    print('未登录场景：')

    if want('GOODS-02'):
        goto(page, base, '/home')
        shot(page, 'US-GOODS-02-1-卡片分类与编号', '.catalogue-card, .card, .product-card')

    if want('SEARCH-02'):
        goto(page, base, '/search?categoryId=2')
        shot(page, 'US-SEARCH-02-1-分类筛选')

    if want('RECO-02'):
        goto(page, base, '/product/8')
        shot(page, 'US-RECO-02-1-详情页相似商品')

    if want('QUALITY-02'):
        def slow(route):
            page.wait_for_timeout(1500)
            route.continue_()
        page.route('**/api/product/**', slow)
        page.goto(f'{base}/home', wait_until='domcontentloaded')
        page.wait_for_timeout(500)
        shot(page, 'US-QUALITY-02-1-加载态（骨架屏）', note='接口人为延迟 1.5s 抓拍')
        page.unroute('**/api/product/**')

        page.route('**/api/product/list**',
                   lambda r: r.fulfill(status=500, body='{"code":500,"message":"模拟故障"}'))
        goto(page, base, '/home')
        shot(page, 'US-QUALITY-02-3-错误态（可重试）', note='列表接口人为返回 500')
        page.unroute('**/api/product/list**')

    ctx.close()


def run_student(base, browser):
    print('学生（stu_demo，id=7）场景：')
    ctx = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
    page = ctx.new_page()
    page.set_default_timeout(20000)
    login(page, base, STU)

    if want('USER-03'):
        goto(page, base, '/profile/edit')
        shot(page, 'US-USER-03-1-个人资料编辑页')

    if want('FAV-02'):
        goto(page, base, '/favorites')
        shot(page, 'US-FAV-02-1-我的收藏列表')

    if want('CHAT-03'):
        goto(page, base, '/messages')
        shot(page, 'US-CHAT-03-1-会话列表与未读数')

    if want('CHAT-02'):
        goto(page, base, '/messages')
        try:
            page.locator('.conversation-item, .conv-item, .el-scrollbar__view > div').first.click(timeout=6000)
            page.wait_for_timeout(1600)
        except Exception:
            pass
        shot(page, 'US-CHAT-02-1-消息已读回执')

    if want('ORDER-04'):
        goto(page, base, '/orders/bought')
        if click_button(page, ['取消']):
            wait_dialog(page)
            shot(page, 'US-ORDER-04-1-取消订单确认弹窗', note='只截图，未真的取消')
            page.keyboard.press('Escape')
            page.wait_for_timeout(500)
        else:
            log(False, 'US-ORDER-04-1-取消订单确认弹窗', '页面上没有「取消」按钮')

    if want('RECO-03'):
        goto(page, base, '/product/8')
        if click_button(page, ['举报']):
            wait_dialog(page)
            shot(page, 'US-RECO-03-1-举报弹窗', note='只截图，未提交')
            page.keyboard.press('Escape')
            page.wait_for_timeout(500)
        else:
            log(False, 'US-RECO-03-1-举报弹窗', '页面上没有「举报」按钮')

    ctx.close()


def run_seller(base, browser):
    print('卖家（stu_test01，id=5，订单1买家）场景：')
    ctx = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
    page = ctx.new_page()
    page.set_default_timeout(20000)
    login(page, base, SELLER)

    if want('GOODS-03'):
        goto(page, base, '/product/mine')
        shot(page, 'US-GOODS-03-1-我的商品（上架下架入口）')

    if want('EVAL-02'):
        goto(page, base, '/product/8')
        shot(page, 'US-EVAL-02-1-详情页评价区（评价前空态）')

    if want('EVAL-01'):
        # v0.18：评价入口改到订单详情页（列表 fixed-right 列里的按钮点击不触发处理器）
        goto(page, base, '/orders/1')
        shot(page, 'US-EVAL-01-1-订单详情评价入口')

        opened = click_button(page, ['评价这笔交易'])
        if opened and wait_dialog(page):
            shot(page, 'US-EVAL-01-2-评价弹窗', note='星级/标签/内容/匿名（整页截）')
            if dialog_button(page, ['提交评价']):
                page.wait_for_timeout(2600)
                # 提交成功后弹窗关闭；再点一次入口，应由 precheck 拦住并提示「已评价过」
                click_button(page, ['评价这笔交易'])
                page.wait_for_timeout(2000)
                shot(page, 'US-EVAL-01-3-重复评价被拦下')
            else:
                log(False, 'US-EVAL-01-3-重复评价被拦下', '弹窗里没找到「提交评价」')
        elif opened:
            log(False, 'US-EVAL-01-2-评价弹窗', '点了「评价」但弹窗没出现')
        else:
            log(False, 'US-EVAL-01-2-评价弹窗', '没有「评价」按钮（可能已评价过，先清数据）')

    ctx.close()


def run_admin(base, browser):
    print('管理员场景：')
    ctx = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
    page = ctx.new_page()
    page.set_default_timeout(20000)
    login(page, base, ADMIN)

    if want('ADMIN-01'):
        goto(page, base, '/admin/products')
        shot(page, 'US-ADMIN-01-1-商品审核页')

    if want('ADMIN-02'):
        goto(page, base, '/admin/products')
        shot(page, 'US-ADMIN-02-1-强制下架入口', '.el-table, table')

    if want('ADMIN-03'):
        goto(page, base, '/admin/users')
        shot(page, 'US-ADMIN-03-1-用户管理（启用禁用）')

    if want('RECO-03'):
        goto(page, base, '/admin/reports')
        shot(page, 'US-RECO-03-2-举报在后台可见')

    if want('EVAL-02'):
        goto(page, base, '/orders/1')
        if click_button(page, ['评价这笔交易']) and wait_dialog(page):
            # ★ 匿名开关必须**断言真的切到 on** 再提交：
            #   第一版直接 click 了一下就提交，结果库里 anonymous=0，
            #   截图里显示的是真名（匿名没生效，证据就成了假的）。
            turned = False
            for sel in ['.el-dialog .el-switch', '.el-dialog .el-switch__core']:
                try:
                    page.locator(sel).last.click(timeout=4000)
                    page.wait_for_timeout(400)
                    cls = page.locator('.el-dialog .el-switch').last.get_attribute('class') or ''
                    if 'is-checked' in cls:
                        turned = True
                        break
                except Exception:
                    continue
            log(turned, '匿名开关已切到 on', '' if turned else '未能切到 on（本次评价可能不是匿名）')
            if dialog_button(page, ['提交评价']):
                page.wait_for_timeout(2600)
                log(True, 'US-EVAL-02-3-卖家匿名评价已提交', '稍后由商品页截图体现匿名')
            else:
                log(False, 'US-EVAL-02-3-卖家匿名评价已提交', '弹窗里没找到「提交评价」')
        else:
            log(False, 'US-EVAL-02-3-卖家匿名评价已提交', '没打开评价弹窗（可能已评价过）')

    ctx.close()


def run_anon_product(base, browser):
    """匿名评价提交后，回商品页确认列表里显示匿名。"""
    print('匿名评价的展示场景：')
    ctx = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
    page = ctx.new_page()
    page.set_default_timeout(20000)
    goto(page, base, '/product/8')
    shot(page, 'US-EVAL-02-2-详情页评价区（含匿名评价）')
    ctx.close()


def run_limits(base, browser):
    """限流提示（US-QUALITY-06 界面侧）。会占满登录限流窗口，默认不跑。"""
    print('限流场景：')
    ctx = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
    page = ctx.new_page()
    page.set_default_timeout(20000)
    page.goto(f'{base}/login', wait_until='domcontentloaded')
    hit = False
    i = 0
    for i in range(26):
        try:
            page.get_by_placeholder('请输入用户名').fill(f'ratelimit_probe_{i}')
            page.get_by_placeholder('请输入密码').fill('wrongpass')
            page.get_by_role('button', name=re.compile(r'登\s*录')).click()
            page.wait_for_timeout(450)
            body = page.inner_text('body')
            if '频繁' in body or '稍后' in body:
                hit = True
                break
        except Exception:
            page.wait_for_timeout(400)
    shot(page, 'US-QUALITY-06-1-登录限流提示',
         note=(f'第 {i + 1} 次尝试触发' if hit else '未触发（阈值可能更大）'))
    ctx.close()


def main():
    global ONLY
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://127.0.0.1:8081')
    ap.add_argument('--only', default='', help='只跑含该子串的场景，如 EVAL / ADMIN')
    ap.add_argument('--with-limits', action='store_true',
                    help='额外采限流提示（会占满登录限流窗口 60 秒，默认关闭）')
    args = ap.parse_args()

    ONLY = args.only.strip().upper()
    OUT.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)
        run_public(args.base, browser)
        run_student(args.base, browser)
        run_seller(args.base, browser)
        run_admin(args.base, browser)
        if want('EVAL-02'):
            run_anon_product(args.base, browser)
        if args.with_limits:
            run_limits(args.base, browser)
        browser.close()

    ok = sum(1 for r in results if r['ok'])
    print(f'\n截图完成：{ok} / {len(results)}')
    (OUT / 'capture-manifest.json').write_text(
        json.dumps({'base': args.base, 'only': ONLY, 'results': results}, ensure_ascii=False, indent=2),
        encoding='utf-8')
    bad = [r['name'] for r in results if not r['ok']]
    for n in bad:
        print('  XX ' + n)
    return 0 if not bad else 1


if __name__ == '__main__':
    sys.exit(main())
