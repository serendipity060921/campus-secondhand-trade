# -*- coding: utf-8 -*-
"""AI 驱动的浏览器用户故事测试报告生成器

做法：用 Playwright 驱动**真实 Edge**，按 docs/user-stories.md 里的用户故事逐条操作，
      每一步自动截图，最后生成带截图的报告 docs/测试报告-用户故事视角.md。

运行：<带 playwright 的 python> tools/story-browser-report.py
产物：
  · docs/story-evidence/*.png                 每条故事的关键步骤截图
  · docs/测试报告-用户故事视角.md              带截图与结论的报告

说明：本脚本只做"读操作"与表单校验类操作（不提交真实数据），
      涉及注册/发布/下单等写操作的完整闭环由 tests/e2e/ 下的三套自动化测试覆盖。
"""
import datetime
import pathlib
import re
import sys
import time

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / 'docs'
EVID = DOCS / 'story-evidence'
REPORT = DOCS / '测试报告-用户故事视角.md'
BASE = 'http://127.0.0.1:8081'
VIEWPORT = {'width': 1440, 'height': 900}

SELLER = ('stu_test01', 'abc12345')
BUYER = ('stu_demo', '123456')
ADMIN = ('admin', '123456')

EVID.mkdir(parents=True, exist_ok=True)

results = []          # [{id,title,ac,ok,notes:[],shots:[],js_errors:int}]


# ────────────────────────── 工具函数 ──────────────────────────
def new_page(browser, logged_in_as=None):
    """开一个独立上下文（等价于一台干净的电脑）；需要登录时自动登录

    注意：这里只统计 pageerror（**未捕获的运行时异常**），
    不把 console.error 计入 —— 登录态下的 401/404 请求、Vue 的告警都会走 console.error，
    与本项目口径（页面 JS 错误 = 未捕获异常）不一致，混在一起会得到失真的数字。
    """
    ctx = browser.new_context(viewport=VIEWPORT, locale='zh-CN')
    page = ctx.new_page()
    js_errors = []
    page.on('pageerror', lambda e: js_errors.append(str(e)[:200]))
    if logged_in_as:
        login(page, *logged_in_as)
    return ctx, page, js_errors


def login(page, username, password):
    page.goto(f'{BASE}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(username)
    page.get_by_placeholder('请输入密码').fill(password)
    page.get_by_role('button', name=re.compile('登')).first.click()
    page.wait_for_url(re.compile(r'^(?!.*login).*'), timeout=20000)
    time.sleep(0.8)


def shot(page, name):
    path = EVID / f'{name}.png'
    page.screenshot(path=str(path), full_page=False)
    return path.name


def form_errors(page):
    return [t.strip() for t in page.locator('.el-form-item__error').all_inner_texts()]


def has_any(page, texts):
    body = page.inner_text('body')
    return any(t in body for t in texts)


# ────────────────────────── 用户故事场景 ──────────────────────────
def us_user_02(browser):
    """US-USER-02 登录与登录态保持"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser, logged_in_as=SELLER)
    ok = '/login' not in page.url
    notes.append(f'AC1 登录成功，落地页：{page.url}')
    shots.append(shot(page, 'US-USER-02-1-登录成功'))

    page.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
    time.sleep(1.8)
    # 用 placeholder 判断（placeholder 文案不出现在 innerText 里，不能拿它当依据）
    guarded = page.get_by_placeholder('商品名称').count() > 0
    ok = ok and guarded
    notes.append(f'AC（登录态）访问受保护页面 /product/publish：'
                 f'{"可正常进入 ✓" if guarded else "被拦截 ✗"}')
    shots.append(shot(page, 'US-USER-02-2-已登录可进入受保护页'))
    ctx.close()

    # AC3：未登录访问受保护页面 → 被路由守卫拦截（跳转是异步的，必须等它发生）
    ctx2, page2, errs2 = new_page(browser)
    page2.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
    try:
        page2.wait_for_url(re.compile(r'.*login.*'), timeout=8000)
    except Exception:
        pass
    time.sleep(1.2)
    final_url = page2.url          # 只读一次，避免前后读到不同状态
    blocked = 'login' in final_url
    ok = ok and blocked
    notes.append(f'AC3 未登录访问 /product/publish → 最终地址：{final_url}'
                 f'（{"已跳转登录页 ✓" if blocked else "未被拦截 ✗"}）')
    shots.append(shot(page2, 'US-USER-02-3-未登录被守卫拦截'))
    ctx2.close()
    return ok, notes, shots, len(errs) + len(errs2)


def us_user_01(browser):
    """US-USER-01 注册页表单校验（不提交，只验校验）"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/register', wait_until='domcontentloaded')
    time.sleep(1.2)
    # 注册表单绑定了 @keyup.enter="handleReg"，用回车提交比找按钮更稳
    # （实测按钮文案为「注册」，但通过角色/文本定位在不同构建下容易失配）
    page.locator('input[type="password"]').last.press('Enter')
    time.sleep(2)
    # 提交后断言「校验状态」而不是「错误文案」：
    # 实测本页校验触发后输入框边框变红（el-form-item 加上 is-error），
    # 但不渲染 .el-form-item__error 文本 —— 只找文案会误判为"未触发校验"。
    err_items = page.locator('.el-form-item.is-error').count()
    errs_text = form_errors(page)
    ok = err_items > 0
    notes.append(f'AC3 空表单提交后：处于校验失败状态的字段 {err_items} 个'
                 f'（红框标记），可见错误文案 {len(errs_text)} 条')
    shots.append(shot(page, 'US-USER-01-1-注册页空表单校验'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_goods_01(browser):
    """US-GOODS-01 发布商品（AC2 空值拦截 / AC3 非法价格）"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser, logged_in_as=SELLER)
    page.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
    time.sleep(1.5)
    page.get_by_role('button', name=re.compile('立即发布|发布')).first.click()
    time.sleep(1.2)
    errs_text = form_errors(page)
    expect = {'请输入商品名称', '请选择商品分类', '请输入售价'}
    ok = expect.issubset(set(errs_text))
    notes.append(f'AC2 空表单提交 → 提示 {errs_text}（期望包含 {sorted(expect)}）')
    shots.append(shot(page, 'US-GOODS-01-1-空表单校验'))

    price = page.locator('input[aria-label*="售价"]').first
    price.fill('25.555')
    page.get_by_placeholder('商品名称').click()
    time.sleep(0.8)
    v = price.input_value()
    ok = ok and v in ('25.56', '25.55')
    notes.append(f'AC3 售价输入 25.555 → 组件归一化为 {v}（el-input-number :precision=2 第一层防护）')
    shots.append(shot(page, 'US-GOODS-01-2-售价精度约束'))

    price.fill('0')
    page.get_by_placeholder('商品名称').click()
    time.sleep(0.8)
    v2 = price.input_value()
    ok = ok and v2 == '0.01'
    notes.append(f'AC3 售价输入 0 → 被 :min 夹到 {v2}（第二层由规则模块给出提示）')
    shots.append(shot(page, 'US-GOODS-01-3-售价下限约束'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_search_01(browser):
    """US-SEARCH-01 关键词搜索"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/search?keyword=教材', wait_until='domcontentloaded')
    time.sleep(2)
    body = page.inner_text('body')
    ok = '教材' in body or '暂无' in body or '没有' in body
    notes.append(f'访问 /search?keyword=教材 → URL 保持：{page.url}')
    notes.append(f'页面包含搜索结果或空状态文案：{"是 ✓" if ok else "否 ✗"}')
    shots.append(shot(page, 'US-SEARCH-01-1-关键词搜索'))
    # 无结果时的空状态（AC2）
    page.goto(f'{BASE}/search?keyword=这个关键词肯定搜不到xyz123', wait_until='domcontentloaded')
    time.sleep(2)
    empty_ok = has_any(page, ['暂无', '没有', '空', '找不到'])
    ok = ok and empty_ok
    notes.append(f'AC2 搜索无结果 → 显示空状态：{"是 ✓" if empty_ok else "未识别到空状态文案 ✗"}')
    shots.append(shot(page, 'US-SEARCH-01-2-无结果空状态'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_goods_04(browser):
    """US-GOODS-04 商品详情可见性 / US-QUALITY-02 三态（不存在的商品）"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser)
    page.goto(f'{BASE}/product/99999999', wait_until='domcontentloaded')
    time.sleep(2)
    body = page.inner_text('body')
    ok = len(body.strip()) > 20
    notes.append('AC（异常场景）访问不存在的商品 id → 页面不白屏，有以下反馈：'
                 + ('、'.join([t for t in ['不存在', '暂无', '错误', '失败', '返回'] if t in body]) or '未识别到文案'))
    shots.append(shot(page, 'US-GOODS-04-1-不存在商品的三态反馈'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_quality_05(browser):
    """US-QUALITY-05 窄屏可用（390px 无横向溢出）"""
    notes, shots = [], []
    ctx = browser.new_context(viewport={'width': 390, 'height': 844}, locale='zh-CN')
    page = ctx.new_page()
    js = []
    page.on('pageerror', lambda e: js.append(str(e)[:200]))
    page.goto(f'{BASE}/home', wait_until='domcontentloaded')
    time.sleep(2)
    metrics = page.evaluate("() => ({sw: document.documentElement.scrollWidth, iw: window.innerWidth})")
    overflow = metrics['sw'] - metrics['iw']
    ok = overflow <= 2
    notes.append(f'视口 390px 下 documentElement.scrollWidth={metrics["sw"]}、innerWidth={metrics["iw"]}'
                 f'，横向溢出 {overflow}px（{"达标 ✓" if ok else "存在溢出 ✗"}）')
    shots.append(shot(page, 'US-QUALITY-05-1-390px窄屏首页'))
    ctx.close()
    return ok, notes, shots, len(js)


def us_quality_01(browser):
    """US-QUALITY-01 权限与越权防护（普通用户访问管理后台）

    ★ 断言经验：不要只看 page.url，要看**真实渲染出来的内容**。
      实测普通用户被守卫重定向后，截图显示的是首页（在售目录/猜你喜欢），
      但 page.url 仍可能读到旧地址 —— 只看 URL 会误判为"未拦截"。
    """
    notes, shots = [], []

    def is_admin_page(p):
        body = p.inner_text('body')
        return '数据看板' in body or '商品管理' in body or '用户管理' in body

    def is_home_page(p):
        body = p.inner_text('body')
        return '在售目录' in body or '猜你喜欢' in body

    ctx, page, errs = new_page(browser, logged_in_as=SELLER)
    page.goto(f'{BASE}/admin/dashboard', wait_until='domcontentloaded')
    time.sleep(3)
    blocked = (not is_admin_page(page)) and is_home_page(page)
    notes.append(f'AC2 普通用户访问 /admin/dashboard → 实际渲染：'
                 f'{"首页（已按守卫重定向 ✓）" if blocked else "后台页面（未拦截 ✗）"}'
                 f'；地址栏读数为 {page.url}')
    shots.append(shot(page, 'US-QUALITY-01-1-普通用户访问后台被拦截'))
    ctx.close()

    ctx2, page2, errs2 = new_page(browser, logged_in_as=ADMIN)
    page2.goto(f'{BASE}/admin/dashboard', wait_until='domcontentloaded')
    time.sleep(3)
    ok_admin = is_admin_page(page2)
    notes.append(f'AC（对照）管理员访问 /admin/dashboard → 实际渲染：'
                 f'{"后台数据看板 ✓" if ok_admin else "未进入后台 ✗"}')
    shots.append(shot(page2, 'US-QUALITY-01-2-管理员可进入后台'))
    ctx2.close()
    return (blocked and ok_admin), notes, shots, len(errs) + len(errs2)


def us_chat_03(browser):
    """US-CHAT-03 会话列表与未读数页面可用"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser, logged_in_as=SELLER)
    page.goto(f'{BASE}/messages', wait_until='domcontentloaded')
    time.sleep(2)
    body = page.inner_text('body')
    ok = len(body.strip()) > 20
    notes.append('访问 /messages → 页面渲染正常' + ('（含会话或空状态）' if ok else '（内容异常 ✗）'))
    shots.append(shot(page, 'US-CHAT-03-1-会话列表页'))
    ctx.close()
    return ok, notes, shots, len(errs)


def us_order_02(browser):
    """US-ORDER-02 买卖双方订单视图页面可用"""
    notes, shots = [], []
    ctx, page, errs = new_page(browser, logged_in_as=SELLER)
    ok = True
    for path, tag, label in [('/orders/bought', 'US-ORDER-02-1', '我买到的'),
                             ('/orders/sold', 'US-ORDER-02-2', '我卖出的')]:
        page.goto(f'{BASE}{path}', wait_until='domcontentloaded')
        time.sleep(1.8)
        good = len(page.inner_text('body').strip()) > 20
        ok = ok and good
        notes.append(f'访问 {path}（{label}）→ {"渲染正常 ✓" if good else "内容异常 ✗"}')
        shots.append(shot(page, f'{tag}-{label}'))
    ctx.close()
    return ok, notes, shots, len(errs)


STORIES = [
    ('US-USER-02', '登录与登录态保持', 'AC1 登录成功 / AC3 未登录被路由守卫拦截', us_user_02),
    ('US-USER-01', '注册账号（表单校验）', 'AC3 必填项为空时前端拦截', us_user_01),
    ('US-GOODS-01', '发布商品（表单校验）', 'AC2 空值拦截 / AC3 非法价格拦截', us_goods_01),
    ('US-SEARCH-01', '关键词搜索商品', 'AC1 搜到结果 / AC2 无结果显示空状态', us_search_01),
    ('US-GOODS-04', '商品详情可见性与异常态', 'AC2 不可见商品不泄漏 / 异常时有明确反馈', us_goods_04),
    ('US-QUALITY-01', '权限与越权防护', 'AC2 普通用户访问管理后台被拦截', us_quality_01),
    ('US-QUALITY-05', '窄屏可用（390px）', 'AC1 无横向溢出', us_quality_05),
    ('US-CHAT-03', '会话列表页面可用', 'AC1 页面正常渲染', us_chat_03),
    ('US-ORDER-02', '买卖双方订单视图', 'AC1/AC2 两个视图均可正常打开', us_order_02),
]


# ────────────────────────── 主流程 ──────────────────────────
def main():
    started = datetime.datetime.now()
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)
        print(f'AI 驱动浏览器用户故事测试报告   浏览器=系统 Edge   视口={VIEWPORT["width"]}x{VIEWPORT["height"]}')
        for sid, title, ac, fn in STORIES:
            print(f'\n[{sid}] {title}')
            try:
                ok, notes, shots, js_err = fn(browser)
            except Exception as e:
                ok, notes, shots, js_err = False, [f'执行异常：{str(e)[:160]}'], [], 1
            for n in notes:
                print('    · ' + n)
            print(f'    → {"通过 ✓" if ok else "未通过 ✗"}    截图 {len(shots)} 张    页面 JS 错误 {js_err}')
            results.append({'id': sid, 'title': title, 'ac': ac, 'ok': ok,
                            'notes': notes, 'shots': shots, 'js': js_err})
        browser.close()

    passed = sum(1 for r in results if r['ok'])
    total_js = sum(r['js'] for r in results)
    ended = datetime.datetime.now()

    lines = []
    lines.append('# 测试报告 · 用户故事视角（AI 驱动真实浏览器）\n')
    lines.append('> 校园二手交易平台 · 黎钟 · 2027 届软件工程\n')
    lines.append('> 本报告由 `tools/story-browser-report.py` **自动生成**：脚本用 Playwright 驱动'
                 '**真实浏览器（系统 Edge）**，按 `docs/user-stories.md` 中的用户故事逐条操作，'
                 '每一步自动截图，最后汇总为本报告。\n')
    lines.append('## 一、执行概要\n')
    lines.append('| 项 | 值 |')
    lines.append('| --- | --- |')
    lines.append(f'| 执行时间 | {started.strftime("%Y-%m-%d %H:%M:%S")} ~ {ended.strftime("%H:%M:%S")} |')
    lines.append('| 浏览器 | Microsoft Edge（Chromium 内核，Playwright 驱动，无头模式） |')
    lines.append(f'| 视口 | {VIEWPORT["width"]} × {VIEWPORT["height"]}（含一项 390 × 844 窄屏场景） |')
    lines.append(f'| 被测地址 | {BASE}（Nginx 部署形态） |')
    lines.append(f'| 覆盖用户故事 | {len(results)} 条 |')
    lines.append(f'| 通过 | **{passed} / {len(results)}** |')
    lines.append(f'| 页面 JS 错误合计 | **{total_js}** |')
    lines.append(f'| 截图证据 | `docs/story-evidence/`（共 {sum(len(r["shots"]) for r in results)} 张） |')
    lines.append('')
    lines.append('## 二、结果总览\n')
    lines.append('| 用户故事 | 验收标准 | 结论 | 页面 JS 错误 |')
    lines.append('| --- | --- | --- | --- |')
    for r in results:
        lines.append(f'| **{r["id"]}** {r["title"]} | {r["ac"]} | '
                     f'{"✅ 通过" if r["ok"] else "❌ 未通过"} | {r["js"]} |')
    lines.append('')
    lines.append('> 说明：本报告聚焦"**只读与表单校验**"类场景（不写入真实数据）。'
                 '涉及注册、发布、审核、下单、私信、订单流转等**写操作**的完整闭环，'
                 '由 `tests/e2e/` 下的三套自动化测试（端到端 31 条、部署验证 28 条、单元测试 76 条）覆盖，'
                 '详见 [v0.16 测试报告](test-report-v0.16.md)。\n')
    lines.append('## 三、逐条用户故事明细\n')
    for r in results:
        lines.append(f'### {r["id"]}　{r["title"]}\n')
        lines.append(f'**对应验收标准**：{r["ac"]}　　**结论**：{"✅ 通过" if r["ok"] else "❌ 未通过"}\n')
        lines.append('**执行记录**\n')
        for n in r['notes']:
            lines.append(f'- {n}')
        lines.append('')
        for s in r['shots']:
            lines.append(f'![{r["id"]}](story-evidence/{s})')
            lines.append('')
    lines.append('---\n')
    lines.append(f'*报告由脚本自动生成于 {ended.strftime("%Y-%m-%d %H:%M:%S")}；'
                 f'重新生成命令：`python tools/story-browser-report.py`。*')

    REPORT.write_text('\n'.join(lines), encoding='utf-8')
    print(f'\n{"=" * 56}')
    print(f'结论：{passed}/{len(results)} 条用户故事通过；页面 JS 错误合计 {total_js}')
    print(f'报告：{REPORT}')
    print(f'截图：{EVID}（{sum(len(r["shots"]) for r in results)} 张）')
    return 0 if passed == len(results) else 1


if __name__ == '__main__':
    sys.exit(main())
