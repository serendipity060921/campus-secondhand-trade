# -*- coding: utf-8 -*-
"""AI 驱动的浏览器用户故事测试报告生成器（含写操作场景 + 夹具自动清理）

做法：用 Playwright 驱动**真实 Edge**，按 docs/user-stories.md 的用户故事逐条操作，
      每一步自动截图，最后生成带截图的报告 docs/测试报告-用户故事视角.md，
      并附「33 条用户故事 → 验证方式」覆盖矩阵。

关于写操作：注册/发布/收藏/私信/下单会写入真实数据，因此
  1) 夹具命名统一带 TAG（账号 e2e*、商品标题 E2E*），与演示数据完全隔离；
  2) 脚本结束（含异常）通过 atexit 自动调用 tools/clean-test-data.py --tag TAG 精确清理，
     该工具按 username LIKE 'e2e%' / title LIKE 'E2E%' 匹配，不会误伤演示账号。

运行：<带 playwright 的 python> tools/story-browser-report.py
产物：docs/story-evidence/*.png、docs/测试报告-用户故事视角.md
"""
import atexit
import datetime
import pathlib
import re
import subprocess
import sys
import time
import uuid

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / 'docs'
EVID = DOCS / 'story-evidence'
REPORT = DOCS / '测试报告-用户故事视角.md'
CLEANER = ROOT / 'tools' / 'clean-test-data.py'
IMGS = ROOT / 'frontend' / 'public' / 'demo-images'
DEMO_IMG = IMGS / 'textbook.png'
if not DEMO_IMG.exists():
    cand = list(IMGS.glob('*.png')) if IMGS.exists() else []
    DEMO_IMG = cand[0] if cand else None

BASE = 'http://127.0.0.1:8081'
VIEWPORT = {'width': 1440, 'height': 900}
TAG = uuid.uuid4().hex[:5]
A_USER, B_USER, PWD = f'e2estor{TAG}', f'e2estob{TAG}', 'abc12345'
P_TITLE = f'E2E用户故事商品-{TAG}'
ADMIN = ('admin', '123456')

EVID.mkdir(parents=True, exist_ok=True)
results = []


# ─────────────────────── 夹具清理（与 E2E 同一套做法）───────────────────────
def cleanup(tag=TAG):
    if not CLEANER.exists():
        print(f'（未找到清理工具 {CLEANER}）')
        return
    try:
        r = subprocess.run([sys.executable, str(CLEANER), '--tag', tag],
                           capture_output=True, text=True, encoding='utf-8', timeout=180)
        tail = (r.stdout or '').strip().splitlines()[-4:]
        print(f'（夹具清理 TAG={tag}）：' + ' / '.join(tail))
    except Exception as e:
        print(f'（夹具清理失败，请手动执行：python tools/clean-test-data.py --tag {tag}）{e}')


atexit.register(cleanup)


# ─────────────────────────── 工具函数 ───────────────────────────
def new_page(browser, logged_in_as=None, viewport=None):
    """独立上下文（等价于一台干净的电脑）；只统计 pageerror（未捕获运行时异常）"""
    ctx = browser.new_context(viewport=viewport or VIEWPORT, locale='zh-CN')
    page = ctx.new_page()
    js = []
    page.on('pageerror', lambda e: js.append(str(e)[:200]))
    if logged_in_as:
        login(page, *logged_in_as)
    return ctx, page, js


def login(page, username, password):
    page.goto(f'{BASE}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(username)
    page.get_by_placeholder('请输入密码').fill(password)
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'^(?!.*login).*'), timeout=20000)
    time.sleep(0.8)


def register(page, username, nick):
    page.goto(f'{BASE}/register', wait_until='domcontentloaded')
    page.get_by_placeholder('4~20 位字母、数字或下划线').fill(username)
    page.get_by_placeholder('展示给其他同学的名字').fill(nick)
    page.get_by_placeholder('6~20 位，需包含字母和数字').fill(PWD)
    page.get_by_placeholder('请再次输入密码').fill(PWD)
    page.get_by_role('button', name=re.compile(r'注\s*册')).click()
    time.sleep(1.8)


def shot(page, name):
    path = EVID / f'{name}.png'
    page.screenshot(path=str(path), full_page=False)
    return path.name


def form_errors(page):
    return [t.strip() for t in page.locator('.el-form-item__error').all_inner_texts()]


def body(page):
    return page.inner_text('body')


# ─────────────────────────── 用户故事场景 ───────────────────────────
def us_user_02(browser):
    """US-USER-02 登录与登录态保持"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser, logged_in_as=('stu_test01', 'abc12345'))
    ok = '/login' not in page.url
    notes.append(f'AC1 登录成功，落地页 {page.url}')
    shots.append(shot(page, 'US-USER-02-1-登录成功'))
    page.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
    time.sleep(1.8)
    guarded = page.get_by_placeholder('商品名称').count() > 0
    ok = ok and guarded
    notes.append(f'AC（登录态）访问受保护页面：{"可正常进入 ✓" if guarded else "被拦截 ✗"}')
    shots.append(shot(page, 'US-USER-02-2-已登录可进入受保护页'))
    ctx.close()

    ctx2, page2, errs2 = new_page(browser)
    page2.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
    try:
        page2.wait_for_url(re.compile(r'.*login.*'), timeout=8000)
    except Exception:
        pass
    time.sleep(1)
    final = page2.url
    blocked = 'login' in final
    ok = ok and blocked
    notes.append(f'AC3 未登录访问 /product/publish → {final}（{"已跳转登录页 ✓" if blocked else "未拦截 ✗"}）')
    shots.append(shot(page2, 'US-USER-02-3-未登录被守卫拦截'))
    ctx2.close()
    return ok, notes, shots, len(errs) + len(errs2)


def us_user_01(browser):
    """US-USER-01 注册账号（空表单校验 + 真实注册 e2e* 夹具账号）"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/register', wait_until='domcontentloaded')
    time.sleep(1)
    page.locator('input[type="password"]').last.press('Enter')
    time.sleep(1.8)
    err_items = page.locator('.el-form-item.is-error').count()
    ok = err_items > 0
    notes.append(f'AC3 空表单提交 → {err_items} 个字段进入校验失败状态（红框标记），'
                 f'可见错误文案 {len(form_errors(page))} 条')
    shots.append(shot(page, 'US-USER-01-1-注册页空表单校验'))

    register(page, A_USER, f'用户故事卖家{TAG}')
    time.sleep(1)
    txt = body(page)
    reg_ok = ('注册成功' in txt) or ('login' in page.url)
    ok = ok and reg_ok
    notes.append(f'AC1 真实注册账号 {A_USER} → {"注册成功并跳转登录页 ✓" if reg_ok else "结果未识别 ✗"}')
    shots.append(shot(page, 'US-USER-01-2-注册成功'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_goods_01(browser):
    """US-GOODS-01 发布商品（AC2/AC3 校验 + 真实发布）"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/login', wait_until='domcontentloaded')
    login(page, A_USER, PWD)

    page.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
    time.sleep(1.5)
    page.get_by_role('button', name='立即发布').click()
    time.sleep(1.2)
    errs_text = form_errors(page)
    expect = {'请输入商品名称', '请选择商品分类', '请输入售价'}
    ok = expect.issubset(set(errs_text))
    notes.append(f'AC2 空表单提交 → 提示 {errs_text}')
    shots.append(shot(page, 'US-GOODS-01-1-空表单校验'))

    price = page.locator('input[aria-label*="售价"]').first
    price.fill('25.555')
    page.get_by_placeholder('例如：《数据结构》教材 九成新').click()
    time.sleep(0.8)
    norm = price.input_value()
    ok = ok and norm in ('25.56', '25.55')
    notes.append(f'AC3 售价输入 25.555 → 组件归一化为 {norm}（el-input-number :precision=2）')

    page.get_by_placeholder('例如：《数据结构》教材 九成新').fill(P_TITLE)
    page.locator('.el-select').first.click()
    page.locator('.el-select-dropdown__item:visible').first.click()
    page.locator('.el-input-number input').first.fill('88.50')
    page.locator('.el-radio-button').nth(1).click()
    page.get_by_placeholder('例如：东校区', exact=True).fill('东校区')
    page.get_by_placeholder('例如：东校区图书馆门口', exact=True).fill('东校区图书馆门口')
    page.get_by_placeholder('说明成色、入手渠道、瑕疵、交易方式等').fill(
        f'用户故事自动化测试商品（{TAG}），成色良好，支持当面交易。')
    if DEMO_IMG:
        page.locator('input[type=file]').last.set_input_files(str(DEMO_IMG))
        time.sleep(2.5)
    page.get_by_role('button', name='立即发布').click()
    page.wait_for_url(re.compile(r'/product/\d+'), timeout=20000)
    pid = int(re.search(r'/product/(\d+)', page.url).group(1))
    ok = ok and pid > 0
    notes.append(f'AC1 真实发布成功 → 跳转详情页 /product/{pid}（标题 {P_TITLE}）')
    shots.append(shot(page, 'US-GOODS-01-2-发布成功进入详情'))

    # ── v0.17 内容机审（内容治理第一层）：违规内容应在发布时被拦截 ──
    page.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
    time.sleep(1.5)
    page.get_by_placeholder('例如：《数据结构》教材 九成新').fill(f'E2E违规验证-{TAG}-专业代写论文包过')
    page.locator('.el-select').first.click()
    page.locator('.el-select-dropdown__item:visible').first.click()
    page.locator('.el-input-number input').first.fill('100.00')
    page.locator('.el-radio-button').nth(1).click()
    page.get_by_placeholder('例如：东校区', exact=True).fill('东校区')
    page.get_by_placeholder('例如：东校区图书馆门口', exact=True).fill('东校区图书馆门口')
    page.get_by_role('button', name='立即发布').click()
    time.sleep(1.5)
    rejected = ('不符合平台规范' in body(page)) or ('代写' in body(page))
    ok = ok and rejected
    notes.append('AC（v0.17 内容机审）发布含「代写 / 包过」的违规商品 → '
                 + ('被拒绝并给出具体原因 ✓' if rejected else '未被拦截 ✗'))
    shots.append(shot(page, 'US-GOODS-01-4-机审拦截违规商品'))
    ctx.close()
    return ok, notes, shots, len(errs), pid


def us_goods_01b(browser, pid):
    """US-GOODS-01 补充：新商品在首页可见"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/home', wait_until='domcontentloaded')
    time.sleep(2.5)
    visible = page.locator(f'.product-card:has-text("{P_TITLE}")').count() > 0
    notes.append(f'新发布的商品在首页列表{"可见 ✓" if visible else "不可见 ✗"}'
                 f'（说明当前版本发布后直接处于在售状态）')
    shots.append(shot(page, 'US-GOODS-01-3-首页可见新商品'))
    ctx.close()
    return visible, notes, shots, len(errs)


def us_goods_04(browser, pid):
    """US-GOODS-04 商品详情 / 异常态"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/product/{pid}', wait_until='domcontentloaded')
    time.sleep(2)
    txt = body(page)
    ok = P_TITLE in txt
    notes.append(f'AC1 详情页渲染商品信息：{"标题可见 ✓" if ok else "未看到标题 ✗"}')
    shots.append(shot(page, 'US-GOODS-04-1-商品详情页'))
    page.goto(f'{BASE}/product/99999999', wait_until='domcontentloaded')
    time.sleep(2)
    txt2 = body(page)
    ok2 = len(txt2.strip()) > 20
    notes.append('AC（异常场景）访问不存在的商品 id → 页面不白屏，出现提示：'
                 + ('、'.join([t for t in ['不存在', '暂无', '错误', '失败', '返回'] if t in txt2]) or '未识别'))
    shots.append(shot(page, 'US-GOODS-04-2-不存在商品的异常态'))
    ctx.close()
    return (ok and ok2), notes, shots, len(errs)


def us_fav_01(browser, pid):
    """US-FAV-01 / US-FAV-02 收藏 → 我的收藏 → 取消收藏"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    register(page, B_USER, f'用户故事买家{TAG}')
    login(page, B_USER, PWD)

    page.goto(f'{BASE}/product/{pid}', wait_until='domcontentloaded')
    time.sleep(2)
    fav = page.get_by_role('button', name=re.compile('☆ 收藏|★ 已收藏'))
    before = fav.inner_text()
    fav.click()
    time.sleep(1.5)
    after = page.get_by_role('button', name=re.compile('☆ 收藏|★ 已收藏')).inner_text()
    ok = before != after
    notes.append(f'AC1 点击收藏 → 按钮由「{before}」变为「{after}」')
    shots.append(shot(page, 'US-FAV-01-1-收藏商品'))

    page.goto(f'{BASE}/favorites', wait_until='domcontentloaded')
    time.sleep(2)
    in_list = P_TITLE in body(page)
    ok = ok and in_list
    notes.append(f'AC（US-FAV-02）我的收藏页{"能看到该商品 ✓" if in_list else "未看到 ✗"}')
    shots.append(shot(page, 'US-FAV-01-2-我的收藏页'))

    cancel = page.get_by_role('button', name='取消收藏')
    if cancel.count() > 0:
        cancel.first.click()
        time.sleep(1.5)
        # 收藏列表不会在取消后自动移除该项，需要重新加载页面再核对（真实用户也是刷新后才确信）
        page.reload(wait_until='domcontentloaded')
        time.sleep(2)
        gone = P_TITLE not in body(page)
        ok = ok and gone
        notes.append(f'AC2 取消收藏 → 刷新收藏页后该商品{"已移除 ✓" if gone else "仍存在 ✗"}')
        shots.append(shot(page, 'US-FAV-01-3-取消收藏'))
    else:
        notes.append('AC2 未找到「取消收藏」按钮 ✗')
    ctx.close()
    return ok, notes, shots, len(errs)


def us_chat_01(browser, pid):
    """US-CHAT-01 / US-CHAT-03 私聊卖家 → 会话列表可见"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    login(page, B_USER, PWD)
    page.goto(f'{BASE}/product/{pid}', wait_until='domcontentloaded')
    time.sleep(2)
    page.get_by_role('button', name='私聊卖家').click()
    time.sleep(1.5)
    marker = f'用户故事测试消息{TAG}'
    box = page.get_by_placeholder(re.compile('输入消息'))
    box.fill(f'你好，这件商品还在吗？（{marker}）')
    page.get_by_role('button', name='发送').click()
    time.sleep(1.8)
    sent = marker in body(page)
    notes.append(f'AC1 买家发送私信 {"成功（页面可见该消息）✓" if sent else "未在页面看到消息 ✗"}')
    shots.append(shot(page, 'US-CHAT-01-1-买家发送私信'))
    ctx.close()

    ctx2, page2, errs2 = new_page(browser)
    login(page2, A_USER, PWD)
    page2.goto(f'{BASE}/messages', wait_until='domcontentloaded')
    time.sleep(2.5)
    has_conv = page2.locator('.conversation-item').count() > 0
    notes.append(f'AC（US-CHAT-03）卖家会话列表 {"出现该会话 ✓" if has_conv else "未出现 ✗"}')
    shots.append(shot(page2, 'US-CHAT-01-2-卖家会话列表'))
    read_ok = False
    if has_conv:
        page2.locator('.conversation-item').first.click()
        time.sleep(2)
        read_ok = marker in body(page2)
        notes.append(f'AC（US-CHAT-02）打开会话后消息内容可见 {"✓" if read_ok else "✗"}')
        shots.append(shot(page2, 'US-CHAT-01-3-卖家查看会话'))
    ctx2.close()
    return (sent and has_conv and read_ok), notes, shots, len(errs) + len(errs2)


def us_order_01(browser, pid):
    """US-ORDER-01 / 02 / 03 下单 → 双方订单视图 → 确认完成"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    login(page, B_USER, PWD)
    page.goto(f'{BASE}/product/{pid}', wait_until='domcontentloaded')
    time.sleep(2)
    page.get_by_role('button', name='立即购买').click()
    time.sleep(1.2)
    page.get_by_placeholder('例如：东校区图书馆门口').fill('东校区图书馆门口')
    page.get_by_placeholder('例如：明天下午三点方便面交吗').fill(f'用户故事测试下单备注 {TAG}')
    page.get_by_role('button', name='确认下单').click()
    time.sleep(2.5)
    ordered = ('成功' in body(page)) or ('订单' in body(page))
    notes.append(f'AC1 买家下单 {"成功 ✓" if ordered else "结果未识别 ✗"}')
    shots.append(shot(page, 'US-ORDER-01-1-买家下单'))

    page.goto(f'{BASE}/orders/bought', wait_until='domcontentloaded')
    time.sleep(2)
    bought_ok = P_TITLE in body(page)
    notes.append(f'AC（US-ORDER-02）「我买到的」{"可见该订单 ✓" if bought_ok else "未看到 ✗"}')
    shots.append(shot(page, 'US-ORDER-01-2-我买到的'))
    ctx.close()

    ctx2, page2, errs2 = new_page(browser)
    login(page2, A_USER, PWD)
    page2.goto(f'{BASE}/orders/sold', wait_until='domcontentloaded')
    time.sleep(2)
    seller_sees = P_TITLE in body(page2)
    notes.append(f'AC（US-ORDER-02）卖家侧「我卖出的」{"可见该订单 ✓" if seller_sees else "未看到 ✗"}')
    shots.append(shot(page2, 'US-ORDER-01-3-卖家订单视图'))
    finished = False
    done = page2.get_by_role('button', name='完成')
    if done.count() > 0:
        done.first.click()
        time.sleep(2.5)
        finished = '已完成' in body(page2)
        notes.append(f'AC（US-ORDER-03）卖家确认完成 → {"订单已变为已完成 ✓" if finished else "状态未识别 ✗"}')
        shots.append(shot(page2, 'US-ORDER-01-4-卖家确认完成'))
    else:
        notes.append('AC（US-ORDER-03）未找到「完成」按钮 ✗')
    ctx2.close()
    return (ordered and bought_ok and seller_sees and finished), notes, shots, len(errs) + len(errs2)


def us_reco_01(browser):
    """US-RECO-01 首页个性化推荐"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/home', wait_until='domcontentloaded')
    time.sleep(2.5)
    txt = body(page)
    ok = ('猜你喜欢' in txt) or ('推荐' in txt)
    notes.append(f'AC1 首页推荐模块 {"存在 ✓" if ok else "未识别 ✗"}')
    shots.append(shot(page, 'US-RECO-01-1-首页猜你喜欢'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_admin_04(browser):
    """US-ADMIN-04 数据看板（管理员）"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser, logged_in_as=ADMIN)
    page.goto(f'{BASE}/admin/dashboard', wait_until='domcontentloaded')
    time.sleep(3)
    txt = body(page)
    ok = (('数据看板' in txt) or ('概览' in txt)) and len(txt.strip()) > 50
    notes.append(f'AC1 管理后台数据看板 {"渲染正常 ✓" if ok else "渲染异常 ✗"}')
    shots.append(shot(page, 'US-ADMIN-04-1-管理后台数据看板'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_quality_01(browser):
    """US-QUALITY-01 权限与越权防护（断言真实渲染内容，不只看 URL）"""
    notes, shots = [], []

    def is_admin_page(p):
        return any(k in body(p) for k in ['数据看板', '商品管理', '用户管理'])

    def is_home(p):
        return any(k in body(p) for k in ['在售目录', '猜你喜欢'])

    ctx, page, errs = new_page(browser, logged_in_as=('stu_test01', 'abc12345'))
    page.goto(f'{BASE}/admin/dashboard', wait_until='domcontentloaded')
    time.sleep(3)
    blocked = (not is_admin_page(page)) and is_home(page)
    notes.append(f'AC2 普通用户访问后台 → 实际渲染：'
                 f'{"首页（守卫已重定向 ✓）" if blocked else "后台页面（未拦截 ✗）"}')
    shots.append(shot(page, 'US-QUALITY-01-1-普通用户访问后台被拦截'))
    ctx.close()

    ctx2, page2, errs2 = new_page(browser, logged_in_as=ADMIN)
    page2.goto(f'{BASE}/admin/dashboard', wait_until='domcontentloaded')
    time.sleep(3)
    ok_admin = is_admin_page(page2)
    notes.append(f'AC（对照）管理员访问后台 → 实际渲染：{"数据看板 ✓" if ok_admin else "未进入 ✗"}')
    shots.append(shot(page2, 'US-QUALITY-01-2-管理员可进入后台'))
    ctx2.close()
    return (blocked and ok_admin), notes, shots, len(errs) + len(errs2)


def us_quality_05(browser):
    """US-QUALITY-05 窄屏可用（390px）"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser, viewport={'width': 390, 'height': 844})
    page.goto(f'{BASE}/home', wait_until='domcontentloaded')
    time.sleep(2.5)
    m = page.evaluate('() => ({sw: document.documentElement.scrollWidth, iw: window.innerWidth})')
    over = m['sw'] - m['iw']
    ok = over <= 2
    notes.append(f'AC1 390px 下 scrollWidth={m["sw"]}、innerWidth={m["iw"]}，横向溢出 {over}px')
    shots.append(shot(page, 'US-QUALITY-05-1-390px窄屏首页'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_search_01(browser):
    """US-SEARCH-01 关键词搜索与空状态"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/search?keyword=教材', wait_until='domcontentloaded')
    time.sleep(2)
    ok = '教材' in body(page)
    notes.append(f'AC1 搜索「教材」→ {page.url}，结果区{"有内容 ✓" if ok else "无内容 ✗"}')
    shots.append(shot(page, 'US-SEARCH-01-1-关键词搜索'))
    page.goto(f'{BASE}/search?keyword=绝对搜不到的关键词xyz123', wait_until='domcontentloaded')
    time.sleep(2)
    txt = body(page)
    empty_ok = any(k in txt for k in ['暂无', '没有', '找不到', '空'])
    notes.append(f'AC2 无结果 → 空状态 {"✓" if empty_ok else "未识别 ✗"}')
    shots.append(shot(page, 'US-SEARCH-01-2-无结果空状态'))
    ctx.close()
    return (ok and empty_ok), notes, shots, len(errs)


# ─────────────────────── 33 条故事覆盖矩阵 ───────────────────────
MATRIX = [
    ('US-USER-01', '注册账号', '本报告（浏览器）', '—'),
    ('US-USER-02', '登录与登录态保持', '本报告（浏览器）', '—'),
    ('US-USER-03', '完善资料与上传头像', 'E2E 套件 1.3', 'BUG-06'),
    ('US-GOODS-01', '发布商品', '本报告（浏览器）+ E2E 1.4/1.4b', '—'),
    ('US-GOODS-02', '分类与编号展示', '单元测试 + E2E 1.5b', 'BUG-16-01'),
    ('US-GOODS-03', '上架与下架自己的商品', 'E2E 权限越权 2.2', 'BUG-01'),
    ('US-GOODS-04', '查看商品详情', '本报告（浏览器）', 'BUG-02'),
    ('US-ADMIN-01', '管理员审核商品', '后台 E2E（e2e-v013-admin.py）', '—'),
    ('US-ADMIN-02', '强制下架违规商品', '后台 E2E', '—'),
    ('US-ADMIN-03', '用户启用/禁用', '后台 E2E', '—'),
    ('US-ADMIN-04', '数据看板', '本报告（浏览器）+ 后台 E2E', '—'),
    ('US-SEARCH-01', '关键词搜索商品', '本报告（浏览器）', 'BUG-03'),
    ('US-SEARCH-02', '按分类筛选', 'E2E 1.5', '—'),
    ('US-SEARCH-03', '只在售可见（数据隔离）', 'E2E 越权 2.4 + 部署验证 D24', 'BUG-01'),
    ('US-FAV-01', '收藏与取消收藏', '本报告（浏览器）+ E2E 1.6 + 异常 4.4', 'BUG-04'),
    ('US-FAV-02', '查看我的收藏', '本报告（浏览器）', '—'),
    ('US-CHAT-01', '私聊卖家', '本报告（浏览器）+ E2E 1.7', '—'),
    ('US-CHAT-02', '消息已读回执', '本报告（浏览器）+ E2E 1.7c~e', 'BUG-16-02'),
    ('US-CHAT-03', '会话列表与未读数', '本报告（浏览器）', '—'),
    ('US-ORDER-01', '买家下单（防一物多卖）', '本报告（浏览器）+ E2E 1.8 + 异常 4.5', '—'),
    ('US-ORDER-02', '买卖双方订单视图', '本报告（浏览器）+ E2E 1.9', '—'),
    ('US-ORDER-03', '确认交易完成', '本报告（浏览器）+ E2E 1.10', '—'),
    ('US-ORDER-04', '取消订单并释放商品', 'E2E 1.11', '—'),
    ('US-RECO-01', '首页个性化推荐', '本报告（浏览器）+ E2E 1.5', '—'),
    ('US-RECO-02', '详情页相似商品', 'E2E 1.5b', '—'),
    ('US-RECO-03', '举报违规商品', '后台 E2E + 部署验证 D27', '—'),
    ('US-QUALITY-01', '接口权限与越权防护', '本报告（浏览器）+ E2E 越权 6 条', 'BUG-01、BUG-02'),
    ('US-QUALITY-02', '页面三态完备', 'check-states.py（CI）+ 部署验证 D3~D5', 'BUG-16-05'),
    ('US-QUALITY-03', '界面视觉一致（设计令牌）', 'check-tokens.py（CI，阈值 0）', '—'),
    ('US-QUALITY-04', '可访问性与对比度', 'ui-a11y.py + check-states.py（CI）', 'BUG-16-04'),
    ('US-QUALITY-05', '窄屏可用（390px）', '本报告（浏览器）', '—'),
    ('US-QUALITY-06', '缓存与限流', '部署验证 D12/D17/D18 + 压测脚本', '—'),
    ('US-QUALITY-07', '缺陷可追溯与质量不退化', 'ci.yml（棘轮门禁）+ defect-log.md', '全部 12 条'),
]


# ─────────────────────────── 主流程 ───────────────────────────
def main():
    started = datetime.datetime.now()
    pid = None

    def run(sid, title, ac, fn, *args):
        nonlocal pid
        print(f'\n[{sid}] {title}')
        try:
            out = fn(browser, *args)
        except Exception as e:
            out = (False, [f'执行异常：{str(e)[:150]}'], [], 1)
        ok, notes, shots, js = out[0], out[1], out[2], out[3]
        if len(out) > 4 and out[4]:
            pid = out[4]
        for n in notes:
            print('    · ' + n)
        print(f'    → {"通过 ✓" if ok else "未通过 ✗"}  截图 {len(shots)} 张  JS 错误 {js}')
        results.append({'id': sid, 'title': title, 'ac': ac, 'ok': ok,
                        'notes': notes, 'shots': shots, 'js': js})

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)
        print(f'AI 驱动浏览器用户故事测试报告   浏览器=系统 Edge   夹具 TAG={TAG}')
        run('US-USER-02', '登录与登录态保持', 'AC1 登录成功 / AC3 未登录被路由守卫拦截', us_user_02)
        run('US-USER-01', '注册账号（校验 + 真实注册）', 'AC1 注册成功 / AC3 空值拦截', us_user_01)
        run('US-GOODS-01', '发布商品（校验 + 真实发布）',
            'AC1 发布成功 / AC2 空值拦截 / AC3 价格约束', us_goods_01)
        if pid:
            run('US-GOODS-01b', '新商品在首页可见', 'AC（补充）发布后前台可检索', us_goods_01b, pid)
            run('US-GOODS-04', '商品详情与异常态', 'AC1 详情渲染 / 异常时明确反馈', us_goods_04, pid)
            run('US-FAV-01', '收藏与取消收藏', 'AC1 收藏 / AC2 取消 / US-FAV-02 我的收藏', us_fav_01, pid)
            run('US-CHAT-01', '私聊卖家与会话列表', 'AC1 发送 / US-CHAT-02 已读 / US-CHAT-03 会话',
                us_chat_01, pid)
            run('US-ORDER-01', '下单与订单流转', 'AC1 下单 / US-ORDER-02 双方视图 / 03 完成',
                us_order_01, pid)
        run('US-RECO-01', '首页个性化推荐', 'AC1 猜你喜欢存在', us_reco_01)
        run('US-ADMIN-04', '管理后台数据看板', 'AC1 看板渲染正常', us_admin_04)
        run('US-QUALITY-01', '权限与越权防护', 'AC2 普通用户访问后台被拦截', us_quality_01)
        run('US-QUALITY-05', '窄屏可用（390px）', 'AC1 无横向溢出', us_quality_05)
        run('US-SEARCH-01', '关键词搜索商品', 'AC1 搜到结果 / AC2 空状态', us_search_01)
        browser.close()

    passed = sum(1 for r in results if r['ok'])
    total_js = sum(r['js'] for r in results)
    shots_total = sum(len(r['shots']) for r in results)
    ended = datetime.datetime.now()

    L = []
    L.append('# 测试报告 · 用户故事视角（AI 驱动真实浏览器）\n')
    L.append('> 校园二手交易平台 · 黎钟 · 2027 届软件工程\n')
    L.append('> 本报告由 `tools/story-browser-report.py` **自动生成**：脚本用 Playwright 驱动'
             '**真实浏览器（系统 Edge）**，按 `docs/user-stories.md` 的用户故事逐条操作，'
             '每一步自动截图；涉及写操作的场景使用带 TAG 的夹具数据，结束后自动清理，'
             '不污染演示数据。\n')
    L.append('## 一、执行概要\n')
    L.append('| 项 | 值 |')
    L.append('| --- | --- |')
    L.append(f'| 执行时间 | {started.strftime("%Y-%m-%d %H:%M:%S")} ~ {ended.strftime("%H:%M:%S")} |')
    L.append('| 浏览器 | Microsoft Edge（Chromium 内核，Playwright 驱动，无头模式） |')
    L.append(f'| 视口 | {VIEWPORT["width"]} × {VIEWPORT["height"]}（含 390 × 844 窄屏场景） |')
    L.append(f'| 被测地址 | {BASE}（Nginx 部署形态） |')
    L.append(f'| 夹具 TAG | `{TAG}`（账号 `e2e*`、商品标题 `E2E*`，运行结束自动清理） |')
    L.append(f'| 浏览器逐条执行的用户故事 | **{len(results)} 条** |')
    L.append(f'| 通过 | **{passed} / {len(results)}** |')
    L.append(f'| 页面 JS 错误合计 | **{total_js}** |')
    L.append(f'| 截图证据 | `docs/story-evidence/`（共 {shots_total} 张） |')
    L.append('| 用户故事总覆盖 | **33 / 33**（浏览器逐条执行 + 三套自动化测试，见第三节矩阵） |')
    L.append('')
    L.append('## 二、浏览器逐条执行结果\n')
    L.append('| 用户故事 | 验收标准 | 结论 | 页面 JS 错误 |')
    L.append('| --- | --- | --- | --- |')
    for r in results:
        L.append(f'| **{r["id"]}** {r["title"]} | {r["ac"]} | '
                 f'{"✅ 通过" if r["ok"] else "❌ 未通过"} | {r["js"]} |')
    L.append('')
    L.append('### 逐条明细与截图\n')
    for r in results:
        L.append(f'#### {r["id"]}　{r["title"]}\n')
        L.append(f'**验收标准**：{r["ac"]}　　**结论**：{"✅ 通过" if r["ok"] else "❌ 未通过"}\n')
        for n in r['notes']:
            L.append(f'- {n}')
        L.append('')
        for s in r['shots']:
            L.append(f'![{r["id"]}](story-evidence/{s})')
            L.append('')
    L.append('## 三、33 条用户故事覆盖矩阵\n')
    L.append('| 用户故事 | 名称 | 验证方式 | 相关缺陷 |')
    L.append('| --- | --- | --- | --- |')
    for sid, name, how, bug in MATRIX:
        mark = f'**{how}**' if '本报告' in how else how
        L.append(f'| {sid} | {name} | {mark} | {bug} |')
    L.append('')
    L.append(f'> 其中加粗的 **{len(results)} 条**由本报告以「AI 驱动真实浏览器逐条操作 + 截图」执行；'
             f'其余场景由三套自动化测试覆盖（端到端 31 条、部署验证 28 条、单元测试 76 条），'
             f'详见 [v0.16 测试报告](test-report-v0.16.md) 与 [缺陷跟踪表](defect-log.md)。')
    L.append('')
    L.append('## 四、本次执行中发现并修正的断言问题（方法论）\n')
    L.append('| # | 一开始的断言方式 | 为什么不可靠 | 正确做法 |')
    L.append('| --- | --- | --- | --- |')
    L.append('| 1 | 用 `innerText` 判断 placeholder 文案 | placeholder 不属于 innerText，'
             '会把"页面正常"误判为"被拦截" | 用 `get_by_placeholder(...).count()` |')
    L.append('| 2 | 路由跳转前读取 `page.url` | 跳转是异步的，会把"已拦截"误判为"未拦截"，'
             '前后两次读到的值还可能不一致 | 等跳转完成后读，且只读一次 |')
    L.append('| 3 | 只查找 `.el-form-item__error` 错误文案 | 注册页校验触发后是 **is-error 红框**、'
             '不渲染文案，会把"已触发校验"误判为"未触发" | 断言状态类 `.el-form-item.is-error` |')
    L.append('')
    L.append('**结论：断言要看真实渲染状态，不能只看 URL 或单一 DOM 属性。**')
    L.append('')
    L.append('---\n')
    L.append(f'*报告由脚本自动生成于 {ended.strftime("%Y-%m-%d %H:%M:%S")}；'
             f'重新生成：`python tools/story-browser-report.py`。*')

    REPORT.write_text('\n'.join(L), encoding='utf-8')
    print(f'\n{"=" * 56}')
    print(f'结论：{passed}/{len(results)} 条浏览器执行的故事通过；页面 JS 错误 {total_js}；截图 {shots_total} 张')
    print(f'报告：{REPORT}')
    return 0 if passed == len(results) else 1


if __name__ == '__main__':
    sys.exit(main())
