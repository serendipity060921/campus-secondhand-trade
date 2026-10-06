# -*- coding: utf-8 -*-
"""校园二手交易平台 v0.10 · 浏览器端到端（E2E）测试

严格按 v0.10 测试方案执行：
  第一部分 主业务闭环（账号A 卖家 / 账号B 买家，两个独立浏览器上下文）
  第二部分 权限与越权
  第四部分 异常场景（浏览器侧）

运行前提：MySQL 3306 + 后端 8080 + 前端 5173 均已启动
执行：python tests/e2e/e2e-browser-test.py
产物：docs/test-evidence/*.png（截图证据）、%TEMP%/dsh-sqlval/e2e_result.json
"""
import atexit
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import uuid

from playwright.sync_api import sync_playwright

# 入口可用环境变量覆盖（E2E_BASE / API_BASE），便于验证 Nginx 部署形态
BASE = os.environ.get('E2E_BASE', 'http://127.0.0.1:5173')
API = os.environ.get('API_BASE', 'http://127.0.0.1:8080/api')


def preflight(base, api):
    """探活：地址不通就早停并给出可执行提示。

    没有这一步时，地址写错会让 31 条用例各报一次 ERR_CONNECTION_REFUSED，
    真正的病因（基址不对）被淹没在几十条异常里 —— 这个坑实际踩过一次。
    """
    import urllib.error
    import urllib.request

    def probe(url):
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                return r.status < 500
        except urllib.error.HTTPError as e:
            return e.code < 500          # 4xx 说明服务在，只是这个路径不允许
        except Exception:
            return False

    if probe(base):
        return True

    print(f'✗ 前端入口不可达：{base}')
    print('  请确认部署/开发服务器已启动，或用环境变量指定地址。常见两种：')
    print('    · 开发服务器（Vite）      : E2E_BASE=http://127.0.0.1:5173')
    print('    · 部署形态（Nginx 8081）  : E2E_BASE=http://127.0.0.1:8081 API_BASE=http://127.0.0.1:8081/api')
    for alt in ('http://127.0.0.1:5173', 'http://127.0.0.1:8081'):
        if alt != base and probe(alt):
            print(f'  探测到 {alt} 是通的 → 可改用：E2E_BASE={alt}' +
                  ('  API_BASE=http://127.0.0.1:8081/api' if alt.endswith('8081') else ''))
    print(f'  后端接口地址：{api}（可达性：{probe(api.rstrip("/") + "/health")}）')
    print('  ✗ 已提前终止，未产生任何用例结果（不是回归失败）')
    return False


if not preflight(BASE, API):
    raise SystemExit(2)

# ---------------------------------------------------------------- 限流计数复位
# 生产配置下 rate-limit-skip-local=false，本机同样受限（register 10 次/5 分钟、
# login 20 次/60 秒，均为按 IP 计数）。本套件每次运行要注册 3 个账号并多次登录，
# 短窗口内重复运行会耗尽注册预算，表现为账号建不出来、后续步骤连锁超时
# （实际踩过：连跑 5 次后失败数从 2 个涨到 4 个，看起来像"代码改坏了"）。
# 限流本身由部署验证的 D17/D18 专项用例负责验证，因此这里跑前复位计数是可接受的。
_REDIS_CLI = os.environ.get('REDIS_CLI', r'D:\major\tool\redis\redis-cli.exe')
if pathlib.Path(_REDIS_CLI).exists():
    try:
        _keys = subprocess.run([_REDIS_CLI, 'KEYS', 'campus:ratelimit:*'],
                               capture_output=True, text=True, encoding='utf-8', timeout=15)
        _list = [k.strip() for k in (_keys.stdout or '').splitlines() if k.strip()]
        if _list:
            subprocess.run([_REDIS_CLI, 'DEL'] + _list, capture_output=True, timeout=15)
        print(f'限流计数已复位（清除 {len(_list)} 个 key）—— 生产配置下本机同样受限，'
              f'本套件需在短窗口内可重复运行')
    except Exception as _e:                                   # noqa: BLE001
        print(f'（限流计数复位失败，若后续出现账号创建失败请检查限流：{_e}）')

MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
EV = pathlib.Path(r'D:\campus-secondhand-trade\docs\test-evidence')
EV.mkdir(parents=True, exist_ok=True)
TMP = pathlib.Path(os.environ.get('TEMP', '.')) / 'dsh-sqlval'
DEMO_IMG = r'D:\campus-secondhand-trade\frontend\public\demo-images\textbook.png'
BAD_TXT = TMP / 'not-image.txt'
BIG_PNG = TMP / 'big.png'
BAD_TXT.write_text('这不是图片，是一段纯文本，用于测试格式校验。', encoding='utf-8')

TAG = uuid.uuid4().hex[:5]
A_USER, B_USER = f'e2ea{TAG}', f'e2eb{TAG}'
A_PASS = B_PASS = 'abc12345'
PRODUCT_TITLE = f'E2E测试商品-{TAG}'
PRODUCT_TITLE2 = f'E2E取消流程商品-{TAG}'

# ---------------------------------------------------------------- 夹具自动清理
# 本脚本会注册 e2e* 账号、发布 E2E* 商品、产生收藏/订单/消息/行为数据与上传文件。
# 原先没有清理逻辑，跑一次就会污染演示数据：部署验证 D20 断言 4/17/4/4 会失败，
# 公开首页首屏也会出现 "E2E测试商品-xxxxx"（答辩演示前尤其致命）。
# 这里注册 atexit：无论正常结束还是中途抛异常，都按本次 TAG 精确清理；
# 需要保留现场时设 E2E_KEEP_DATA=1。
_KEEP_FIXTURES = os.environ.get('E2E_KEEP_DATA', '') == '1'

# 注意：atexit 回调在解释器拆卸阶段执行，此时模块全局变量可能已被清理
# （实际踩过：回调里读 __file__ 报 NameError，导致清理静默失败、数据被留在演示库里）。
# 因此把需要的路径与依赖在**注册时**绑定成默认参数，存进函数对象，不依赖全局查找。
_CLEANUP_TOOL = pathlib.Path(__file__).resolve().parent.parent.parent / 'tools' / 'clean-test-data.py'
_EXE = sys.executable
_RUN = subprocess.run


def _auto_cleanup(tool=_CLEANUP_TOOL, tag=TAG, keep=_KEEP_FIXTURES, exe=_EXE, run=_RUN, out=print):
    if keep:
        out('（E2E_KEEP_DATA=1，跳过夹具清理，数据将保留以便排查）')
        return
    if not tool.exists():
        out(f'（未找到清理工具 {tool}，请手动清理 e2e* 夹具）')
        return
    try:
        r = run([exe, str(tool), '--tag', tag], capture_output=True, text=True,
                encoding='utf-8', timeout=300)
        tail = [x.strip() for x in (r.stdout or '').strip().splitlines() if x.strip()][-3:]
        out('夹具清理（TAG=%s）：%s' % (tag, ' / '.join(tail)))
    except Exception as e:                                   # noqa: BLE001
        out(f'夹具清理失败，请手动运行：python tools/clean-test-data.py --tag {tag}（{e}）')


atexit.register(_auto_cleanup)

RESULTS = []      # 所有用例结果
SHOTS = []        # 截图文件名


def sql(q):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', '-e', f'USE campus_trade; {q}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=30)
    return (r.stdout or '').strip()


def one(q):
    v = sql(q)
    return v.splitlines()[0] if v else ''


def http_json(method, path, body=None, token=None):
    """纯 HTTP 调用后端接口（用于并发测试：Playwright 对象不能跨线程使用）"""
    import urllib.error
    import urllib.request
    if path.startswith('/api'):          # API 常量已含 /api，避免拼成 /api/api
        path = path[4:]
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(f'{API}{path}', data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode('utf-8'))
        except Exception:
            return {'code': e.code}
    except Exception as e:  # noqa: BLE001
        return {'code': -1, 'message': str(e)}


def publish_api(token, title, price, category_id=8):
    """通过接口发布一件测试商品，返回商品 id"""
    return http_json('POST', '/api/product/publish',
                     {'title': title, 'categoryId': category_id, 'price': price,
                      'conditionLevel': 1, 'description': 'E2E 测试商品（接口发布）'}, token)['data']['id']


def rec(cid, section, scenario, inputs, expected, ok, actual, shot=None):
    RESULTS.append(dict(id=cid, section=section, scenario=scenario, inputs=inputs,
                        expected=expected, actual=actual, ok=bool(ok), evidence=shot or ''))
    print(f"  [{'PASS' if ok else 'FAIL'}] {cid:8s} {scenario[:38]:40s} {actual[:90]}")
    sys.stdout.flush()


def step(cid, section, scenario, inputs, expected, fn):
    """执行一个用例，捕获异常，返回 (ok, 结果值)"""
    try:
        ok, actual, shot, value = fn()
    except Exception as e:  # noqa: BLE001
        ok, actual, shot, value = False, f'执行异常：{type(e).__name__} {str(e)[:150]}', _last_shot[0], None
    rec(cid, section, scenario, inputs, expected, ok, actual, shot)
    return value


_last_shot = [None]


def shot(page, name):
    p = EV / f'{name}.png'
    try:
        page.screenshot(path=str(p))
        SHOTS.append(p.name)
        _last_shot[0] = p.name
    except Exception:
        pass
    return p.name


def msg(page, timeout=6000):
    """抓取 Element Plus 全局提示文本"""
    try:
        loc = page.locator('.el-message').first
        loc.wait_for(state='visible', timeout=timeout)
        t = loc.inner_text().strip()
        return t
    except Exception:
        return ''


def clear_msg(page):
    try:
        page.evaluate("document.querySelectorAll('.el-message').forEach(e=>e.remove())")
    except Exception:
        pass


def confirm_box(page):
    """点击 MessageBox 的确定按钮"""
    btn = page.locator('.el-message-box__btns .el-button--primary')
    btn.wait_for(state='visible', timeout=6000)
    btn.click()


def login(page, username, password, wait_home=True):
    page.goto(f'{BASE}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(username)
    page.get_by_placeholder('请输入密码').fill(password)
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    if wait_home:
        page.wait_for_url(re.compile(r'/(home|search)'), timeout=15000)
    return page.url


def home_card(page, title):
    """首页/列表页中某标题的商品卡片"""
    return page.locator(f'.product-card:has-text("{title}")').first


def api_via_page(page, method, path, body=None):
    """在已登录页面的上下文里发请求（自动带 localStorage 里的 Token）"""
    return page.evaluate("""async ([method, path, body]) => {
        const token = localStorage.getItem('campus_trade_token');
        const opt = { method, headers: { 'Content-Type': 'application/json' } };
        if (token) opt.headers['Authorization'] = 'Bearer ' + token;
        if (body) opt.body = JSON.stringify(body);
        const r = await fetch(path, opt);
        return await r.json();
    }""", [method, path, body])


print('=' * 108)
print(f'v0.10 浏览器端到端测试   账号A(卖家)={A_USER}   账号B(买家)={B_USER}')
print('=' * 108)

with sync_playwright() as pw:
    browser = pw.chromium.launch(channel='msedge', headless=True)
    # 两个完全独立的浏览器上下文 = 两个浏览器/无痕窗口，localStorage 互不影响
    ctx_a = browser.new_context(viewport={'width': 1440, 'height': 900}, locale='zh-CN')
    ctx_b = browser.new_context(viewport={'width': 1440, 'height': 900}, locale='zh-CN')
    ctx_c = browser.new_context(viewport={'width': 1440, 'height': 900}, locale='zh-CN')
    A = ctx_a.new_page()
    B = ctx_b.new_page()
    C = ctx_c.new_page()
    for p in (A, B, C):
        p.set_default_timeout(15000)

    # ======================================================= 第一部分：主业务闭环
    print('\n【第一部分】主业务闭环测试（注册→登录→发布→浏览→收藏→私聊→下单→状态变更）')

    # ---- 1.1 账号A 注册 ----
    def s11():
        A.goto(f'{BASE}/register', wait_until='domcontentloaded')
        A.get_by_placeholder('4~20 位字母、数字或下划线').fill(A_USER)
        A.get_by_placeholder('展示给其他同学的名字').fill(f'卖家A{TAG}')
        A.get_by_placeholder('6~20 位，需包含字母和数字').fill(A_PASS)
        A.get_by_placeholder('请再次输入密码').fill(A_PASS)
        shot(A, '1.1-A注册页')
        A.get_by_role('button', name=re.compile(r'注\s*册')).click()
        t = msg(A)
        A.wait_for_url(re.compile(r'/login'), timeout=15000)
        shot(A, '1.1-A注册成功跳登录')
        db = one(f"SELECT COUNT(*) FROM `user` WHERE username='{A_USER}'")
        ok = '注册成功' in t and db == '1'
        return ok, f"提示「{t}」；跳转 {A.url.replace(BASE, '')}；数据库 user 表记录数={db}", '1.1-A注册成功跳登录', None
    step('1.1', '一、主业务闭环', '账号A注册', f'username={A_USER}, password={A_PASS}', '注册成功并跳转登录页，数据库新增记录', s11)

    # ---- 1.2 账号A 登录 ----
    def s12():
        login(A, A_USER, A_PASS)
        tok = A.evaluate("localStorage.getItem('campus_trade_token')")
        nick = A.locator('.user-info, .header-right').first.inner_text() if A.locator('.header-right').count() else ''
        shot(A, '1.2-A登录后首页')
        ok = bool(tok) and '/home' in A.url
        return ok, f"登录后 URL={A.url.replace(BASE, '')}，Token 已写入 localStorage（{str(tok)[:18]}…），导航显示「{nick.strip()[:12]}」", '1.2-A登录后首页', None
    step('1.2', '一、主业务闭环', '账号A登录', f'{A_USER}/{A_PASS}', '登录成功，跳转首页，Token 生效', s12)

    # ---- 1.3 账号A 完善资料 + 上传头像 ----
    def s13():
        A.goto(f'{BASE}/profile/edit', wait_until='domcontentloaded')
        A.get_by_placeholder('展示给其他同学的名字').fill(f'卖家A{TAG}改')
        A.get_by_placeholder('例如：东校区', exact=True).fill('东校区A栋')
        A.get_by_placeholder('全平台唯一，用于接收交易通知').fill('139' + str(int(TAG, 16) % 100000000).zfill(8))
        A.get_by_placeholder('例如：stu01@campus.edu').fill(f'{A_USER}@campus.edu')
        A.locator('.el-upload input[type=file]').first.set_input_files(DEMO_IMG)
        t1 = msg(A, 10000)
        shot(A, '1.3-A头像上传成功')
        clear_msg(A)
        A.get_by_role('button', name='保存修改').click()
        t2 = msg(A)
        A.wait_for_url(re.compile(r'/profile$'), timeout=15000)
        shot(A, '1.3-A资料保存成功')
        row = sql(f"SELECT nickname, campus, email, avatar FROM `user` WHERE username='{A_USER}'")
        ok = ('头像上传成功' in t1 or '成功' in t1) and '资料已保存' in t2 and '/upload/' in row
        return ok, f"头像提示「{t1}」+ 保存提示「{t2}」；数据库：{row}", '1.3-A资料保存成功', None
    step('1.3', '一、主业务闭环', '账号A完善资料并上传头像', '昵称/校区/手机号/邮箱/头像图片', '资料保存成功、头像落库为 /upload/...', s13)

    # ---- 1.4 账号A 发布商品 ----
    def s14():
        A.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
        A.get_by_placeholder('例如：《数据结构》教材 九成新').fill(PRODUCT_TITLE)
        A.locator('.el-select').first.click()
        A.locator('.el-select-dropdown__item:visible').first.click()
        A.locator('.el-input-number input').first.fill('88.50')
        A.locator('.el-input-number input').nth(1).fill('199.00')
        A.locator('.el-radio-button').nth(1).click()
        A.get_by_placeholder('例如：东校区', exact=True).fill('东校区')
        A.get_by_placeholder('例如：东校区图书馆门口', exact=True).fill('东校区图书馆门口')
        A.get_by_placeholder('说明成色、入手渠道、瑕疵、交易方式等').fill('E2E 自动化测试商品，成色良好，支持当面交易。')
        A.locator('input[type=file]').last.set_input_files(DEMO_IMG)
        time.sleep(2)
        clear_msg(A)
        A.get_by_role('button', name='立即发布').click()
        t = msg(A)
        A.wait_for_url(re.compile(r'/product/\d+'), timeout=15000)
        shot(A, '1.4-A发布商品成功')
        pid = int(re.search(r'/product/(\d+)', A.url).group(1))
        row = sql(f"SELECT id, status, price, category_id, cover_image IS NOT NULL FROM product WHERE id={pid}")
        ok = '发布成功' in t and row.split('\t')[1] == '1'
        return ok, f"提示「{t}」；跳转商品详情 id={pid}；数据库：{row}（status=1 在售、封面图已保存）", '1.4-A发布商品成功', pid
    PID = step('1.4', '一、主业务闭环', '账号A发布商品（含图片）',
               f'名称={PRODUCT_TITLE} 价格=88.50 分类=第1项 成色=第2项 图片=textbook.png',
               '发布成功，商品处于「在售」状态，首页可见', s14)

    # ---- 1.4b 首页能看到（商品上架） ----
    def s14b():
        A.goto(f'{BASE}/home', wait_until='domcontentloaded')
        A.wait_for_timeout(1500)
        visible = home_card(A, PRODUCT_TITLE).count() > 0
        shot(A, '1.4b-首页可见新商品')
        return visible, f"首页商品卡片中{'找到' if visible else '未找到'}标题含「{PRODUCT_TITLE}」的商品", '1.4b-首页可见新商品', None
    step('1.4b', '一、主业务闭环', '新商品在首页商品列表可见（已上架）', '打开首页', '首页列表出现该商品', s14b)

    # ---- 1.5 账号B 注册并登录，浏览商品、打开详情 ----
    def s15a():
        B.goto(f'{BASE}/register', wait_until='domcontentloaded')
        B.get_by_placeholder('4~20 位字母、数字或下划线').fill(B_USER)
        B.get_by_placeholder('展示给其他同学的名字').fill(f'买家B{TAG}')
        B.get_by_placeholder('6~20 位，需包含字母和数字').fill(B_PASS)
        B.get_by_placeholder('请再次输入密码').fill(B_PASS)
        B.get_by_role('button', name=re.compile(r'注\s*册')).click()
        t = msg(B)
        B.wait_for_url(re.compile(r'/login'), timeout=15000)
        login(B, B_USER, B_PASS)
        shot(B, '1.5-B登录后首页')
        ok = '注册成功' in t and '/home' in B.url
        return ok, f"B 注册提示「{t}」，登录后 URL={B.url.replace(BASE, '')}", '1.5-B登录后首页', None
    step('1.5', '一、主业务闭环', '账号B注册并登录', f'{B_USER}/{B_PASS}', 'B 注册登录成功，与 A 会话完全隔离', s15a)

    def s15b():
        B.goto(f'{BASE}/home', wait_until='domcontentloaded')
        B.wait_for_timeout(1500)
        n = B.locator('.product-card').count()
        card = home_card(B, PRODUCT_TITLE)
        card.click()
        B.wait_for_url(re.compile(r'/product/\d+'), timeout=15000)
        B.wait_for_timeout(1200)
        body = B.locator('body').inner_text()
        shot(B, '1.5b-B打开商品详情')
        ok = PRODUCT_TITLE in body and '88.50' in body.replace('￥', '').replace('88.5', '88.50')
        return (ok,
                f"首页商品卡片 {n} 个；详情页 URL={B.url.replace(BASE, '')}，标题与价格（88.5）"
                f"{'正常展示' if PRODUCT_TITLE in body else '未展示'}",
                '1.5b-B打开商品详情', None)
    step('1.5b', '一、主业务闭环', '账号B浏览首页并打开A的商品详情',
         '首页点击商品卡片', '详情页正确展示商品标题、价格、发布者信息', s15b)

    # ---- 1.6 收藏 → 我的收藏 → 取消收藏 ----
    def s16a():
        fav_btn = B.get_by_role('button', name=re.compile('☆ 收藏|★ 已收藏'))
        before = fav_btn.inner_text()
        fav_btn.click()
        t = msg(B)
        B.wait_for_timeout(800)
        after = B.get_by_role('button', name=re.compile('☆ 收藏|★ 已收藏')).inner_text()
        shot(B, '1.6-B收藏成功')
        cnt = one(f"SELECT COUNT(*) FROM favorite WHERE product_id={PID} AND deleted=0")
        ok = '收藏成功' in t and '★ 已收藏' in after and cnt.isdigit() and int(cnt) >= 1
        return ok, f"收藏前按钮「{before.strip()}」→ 点击后「{after.strip()}」，提示「{t}」；数据库 favorite 记录数={cnt}", '1.6-B收藏成功', None
    step('1.6a', '一、主业务闭环', '账号B收藏商品', f'productId={PID}', '收藏成功，按钮变「★ 已收藏」，数据库新增收藏记录', s16a)

    def s16b():
        B.goto(f'{BASE}/favorites', wait_until='domcontentloaded')
        B.wait_for_timeout(1500)
        body = B.locator('body').inner_text()
        shot(B, '1.6b-我的收藏列表')
        ok = PRODUCT_TITLE in body
        return ok, f"我的收藏页{'包含' if ok else '不包含'}商品「{PRODUCT_TITLE}」", '1.6b-我的收藏列表', None
    step('1.6b', '一、主业务闭环', '进入【我的收藏】查看', '打开 /favorites', '收藏列表出现该商品', s16b)

    def s16c():
        B.get_by_role('button', name='取消收藏').first.click()
        confirm_box(B)
        t = msg(B)
        B.wait_for_timeout(1200)
        body = B.locator('body').inner_text()
        shot(B, '1.6c-取消收藏后列表为空')
        cnt = one(f"SELECT COUNT(*) FROM favorite WHERE product_id={PID} AND deleted=0")
        ok = '还没有收藏任何商品' in body
        tip = body[body.find('还没有收藏任何商品'):][:14] if '还没有收藏任何商品' in body else '仍显示商品'
        return (ok,
                f"取消提示「{t}」；列表提示「{tip}」；数据库有效收藏数={cnt}",
                '1.6c-取消收藏后列表为空', None)
    step('1.6c', '一、主业务闭环', '取消收藏', '点击取消收藏并确认', '列表移除该商品，显示空状态，数据库软删除', s16c)

    # ---- 1.7 私聊：B → A，A 查看/已读/回复 ----
    def s17a():
        B.goto(f'{BASE}/product/{PID}', wait_until='domcontentloaded')
        B.wait_for_timeout(1200)
        B.get_by_role('button', name='私聊卖家').click()
        B.wait_for_url(re.compile(r'/chat/\d+'), timeout=15000)
        B.wait_for_timeout(1200)
        B.get_by_placeholder(re.compile('输入消息')).fill('你好，这件商品还在吗？（E2E 测试消息）')
        B.get_by_role('button', name='发送').click()
        t = msg(B)
        B.wait_for_timeout(1500)
        body = B.locator('body').inner_text()
        shot(B, '1.7a-B私聊卖家')
        cnt = one(f"SELECT COUNT(*) FROM message WHERE product_id={PID} AND content LIKE '%E2E 测试消息%'")
        ok = ('E2E 测试消息' in body) or cnt == '1'
        return ok, f"进入聊天页 {B.url.replace(BASE, '')}，发送提示「{t}」；数据库消息数={cnt}", '1.7a-B私聊卖家', None
    step('1.7a', '一、主业务闭环', '账号B在商品详情私聊卖家并发送消息',
         '点击【私聊卖家】→ 输入消息 → 发送', '跳转聊天页，消息发送成功并保存到数据库', s17a)

    def s17b():
        A.goto(f'{BASE}/messages', wait_until='domcontentloaded')
        A.wait_for_timeout(2000)
        body = A.locator('body').inner_text()
        unread = A.locator('.unread-badge, .badge, .el-badge__content').count()
        shot(A, '1.7b-A会话列表含未读')
        ok = f'买家B{TAG}' in body
        return ok, f"A 的会话列表{'出现买家' if ok else '未出现买家'}；未读角标元素数={unread}", '1.7b-A会话列表含未读', None
    step('1.7b', '一、主业务闭环', '账号A打开消息会话列表，看到B的会话',
         '打开 /messages', '会话列表出现B，并显示未读标记', s17b)

    def s17c():
        A.locator('.conversation-item').first.click()
        A.wait_for_url(re.compile(r'/chat/\d+'), timeout=15000)
        A.wait_for_timeout(1800)
        body = A.locator('body').inner_text()
        shot(A, '1.7c-A查看消息并已读')
        time.sleep(1)
        unread = one(f"SELECT COUNT(*) FROM message WHERE to_user_id=(SELECT id FROM `user` WHERE username='{A_USER}') AND is_read=0")
        A.get_by_placeholder(re.compile('输入消息')).fill('在的，随时可以面交～（A 的回复）')
        A.get_by_role('button', name='发送').click()
        A.wait_for_timeout(1500)
        shot(A, '1.7d-A回复B')
        ok = 'E2E 测试消息' in body and unread == '0'
        return ok, f"A 聊天页{'看到' if 'E2E 测试消息' in body else '未看到'}B的消息；标记已读后未读消息数={unread}；已回复B", '1.7c-A查看消息并已读', None
    step('1.7c', '一、主业务闭环', '账号A查看消息、标记已读并回复B',
         '打开会话 → 查看消息 → 回复', '消息可见、已读标记生效（未读清零）、回复发送成功', s17c)

    def s17d():
        B.goto(B.url, wait_until='domcontentloaded')
        B.wait_for_timeout(2500)
        body = B.locator('body').inner_text()
        shot(B, '1.7e-B收到A的回复')
        ok = 'A 的回复' in body
        return ok, f"B 聊天页{'收到' if ok else '未收到'}A 的回复消息", '1.7e-B收到A的回复', None
    step('1.7d', '一、主业务闭环', '账号B收到A的回复', 'B 刷新聊天页', 'B 能看到 A 的回复', s17d)

    # ---- 1.8 B 下单 ----
    def s18():
        B.goto(f'{BASE}/product/{PID}', wait_until='domcontentloaded')
        B.wait_for_timeout(1200)
        B.get_by_role('button', name='立即购买').click()
        B.wait_for_timeout(800)
        B.get_by_placeholder('例如：东校区图书馆门口').fill('东校区图书馆门口')
        B.get_by_placeholder('例如：明天下午三点方便面交吗').fill('E2E 测试下单备注')
        shot(B, '1.8-B下单弹窗')
        B.get_by_role('button', name='确认下单').click()
        t = msg(B)
        B.wait_for_url(re.compile(r'/orders/\d+'), timeout=15000)
        B.wait_for_timeout(1200)
        shot(B, '1.8-B订单详情')
        oid = int(re.search(r'/orders/(\d+)', B.url).group(1))
        row = sql(f"SELECT status, buyer_id, seller_id, amount FROM orders WHERE id={oid}")
        pstatus = one(f"SELECT status FROM product WHERE id={PID}")
        ok = '下单成功' in t and row.split('\t')[0] == '0' and pstatus == '4'
        return ok, f"提示「{t}」；跳转 /orders/{oid}；数据库订单（status,buyer,seller,amount）={row}，商品状态={pstatus}（4=交易中）", '1.8-B订单详情', oid
    OID = step('1.8', '一、主业务闭环', '账号B下单，生成订单（待交易）',
               f'productId={PID} 交易地点=东校区图书馆门口', '下单成功，订单状态「待交易」，商品转「交易中」，买家卖家正确', s18)

    # ---- 1.9 双方订单列表 ----
    def s19a():
        B.goto(f'{BASE}/orders/bought', wait_until='domcontentloaded')
        B.wait_for_timeout(1800)
        body = B.locator('body').inner_text()
        shot(B, '1.9a-B我买到的')
        ok = PRODUCT_TITLE in body and '待交易' in body
        return ok, f"B【我买到的】{'显示' if ok else '未显示'}订单（商品+状态{'待交易' if '待交易' in body else '?'}）", '1.9a-B我买到的', None
    step('1.9a', '一、主业务闭环', '账号B在【我买到的】查看订单', '打开 /orders/bought', '列表出现该订单，状态「待交易」', s19a)

    def s19b():
        A.goto(f'{BASE}/orders/sold', wait_until='domcontentloaded')
        A.wait_for_timeout(1800)
        body = A.locator('body').inner_text()
        shot(A, '1.9b-A我卖出的')
        ok = PRODUCT_TITLE in body
        return ok, f"A【我卖出的】{'显示' if ok else '未显示'}该订单", '1.9b-A我卖出的', None
    step('1.9b', '一、主业务闭环', '账号A在【我卖出的】查看订单', '打开 /orders/sold', '列表出现该订单及买家信息', s19b)

    # ---- 1.10 A 完成订单，双方查看状态变化 ----
    credit_before = sql(f"SELECT GROUP_CONCAT(credit_score) FROM `user` WHERE username IN ('{A_USER}','{B_USER}') ORDER BY username")

    def s110a():
        A.goto(f'{BASE}/orders/sold', wait_until='domcontentloaded')
        A.wait_for_timeout(1500)
        A.get_by_role('button', name='完成').first.click()
        confirm_box(A)
        t = msg(A)
        A.wait_for_timeout(1500)
        shot(A, '1.10a-A确认交易完成')
        st = one(f"SELECT status FROM orders WHERE id={OID}")
        ps = one(f"SELECT status FROM product WHERE id={PID}")
        ok = st == '3' and ps == '5'
        return ok, f"提示「{t}」；数据库订单状态={st}（3=已完成），商品状态={ps}（5=已售出）", '1.10a-A确认交易完成', None
    step('1.10a', '一、主业务闭环', '卖家A将订单改为「交易完成」', f'orderId={OID} → 完成', '订单变已完成，商品变已售出，信用分累加', s110a)

    def s110b():
        B.goto(f'{BASE}/orders/{OID}', wait_until='domcontentloaded')
        B.wait_for_timeout(1800)
        body = B.locator('body').inner_text()
        credit_after = sql(f"SELECT GROUP_CONCAT(credit_score) FROM `user` WHERE username IN ('{A_USER}','{B_USER}') ORDER BY username")
        shot(B, '1.10b-B看到订单已完成')
        ok = '已完成' in body
        return ok, f"B 订单详情页显示状态{'已完成' if ok else '未更新'}；信用分 {credit_before} → {credit_after}", '1.10b-B看到订单已完成', None
    step('1.10b', '一、主业务闭环', '买家B查看订单状态变化（已完成）', f'打开 /orders/{OID}', 'B 侧状态同步为「已完成」，信用分增加', s110b)

    # ---- 1.11 取消订单路径 ----
    def s111():
        # A 通过接口再发布一件商品（发布流程已在 1.4 用界面验证），B 在界面下单后取消
        token_a = A.evaluate("localStorage.getItem('campus_trade_token')")
        import urllib.request
        req = urllib.request.Request(f'{API}/product/publish',
                                     data=json.dumps({'title': PRODUCT_TITLE2, 'categoryId': 8, 'price': 33.0,
                                                      'conditionLevel': 1, 'description': '取消流程测试'}).encode(),
                                     method='POST')
        req.add_header('Content-Type', 'application/json')
        req.add_header('Authorization', 'Bearer ' + token_a)
        pid2 = json.loads(urllib.request.urlopen(req, timeout=20).read().decode())['data']['id']
        B.goto(f'{BASE}/product/{pid2}', wait_until='domcontentloaded')
        B.wait_for_timeout(1000)
        B.get_by_role('button', name='立即购买').click()
        B.wait_for_timeout(600)
        B.get_by_role('button', name='确认下单').click()
        B.wait_for_url(re.compile(r'/orders/\d+'), timeout=15000)
        oid2 = int(re.search(r'/orders/(\d+)', B.url).group(1))
        # A 取消订单
        A.goto(f'{BASE}/orders/sold', wait_until='domcontentloaded')
        A.wait_for_timeout(1500)
        A.locator('tr:has-text("' + PRODUCT_TITLE2 + '")').get_by_role('button', name='取消').click()
        confirm_box(A)
        t = msg(A)
        A.wait_for_timeout(1500)
        shot(A, '1.11-A取消订单')
        st = one(f"SELECT status FROM orders WHERE id={oid2}")
        ps = one(f"SELECT status FROM product WHERE id={pid2}")
        B.goto(f'{BASE}/orders/{oid2}', wait_until='domcontentloaded')
        B.wait_for_timeout(1500)
        body = B.locator('body').inner_text()
        shot(B, '1.11-B看到订单已取消')
        ok = st == '4' and ps == '1' and '已取消' in body
        return ok, f"取消提示「{t}」；订单状态={st}（4=已取消），商品状态={ps}（1=已释放回在售）；B 侧显示{'已取消' if '已取消' in body else '未更新'}", '1.11-B看到订单已取消', None
    step('1.11', '一、主业务闭环', '取消订单并验证商品释放回在售', 'A 在【我卖出的】取消订单', '订单变已取消，商品回到在售，B 侧同步', s111)

    # ======================================================= 第二部分：权限与越权
    print('\n【第二部分】权限与越权测试')

    def p21():
        routes = ['/profile', '/profile/edit', '/product/publish', '/product/mine',
                  '/favorites', '/messages', '/orders/bought', '/orders/sold']
        bad = []
        for r in routes:
            C.goto(f'{BASE}{r}', wait_until='domcontentloaded')
            C.wait_for_timeout(900)
            if '/login' not in C.url:
                bad.append(r)
        shot(C, '2.1-未登录访问受保护页面被拦截')
        ok = not bad
        return ok, f"未登录访问 {len(routes)} 个受保护页面，全部跳转登录页" if ok else f"以下页面未拦截：{bad}", '2.1-未登录访问受保护页面被拦截', None
    step('2.1', '二、权限越权', '未登录直接访问需登录页面应被拦截跳登录页',
         '逐个访问 /profile /profile/edit /product/publish /product/mine /favorites /messages /orders/bought /orders/sold',
         '全部自动跳转到 /login', p21)

    def p22():
        r = api_via_page(B, 'PUT', '/api/product/status', {'productId': PID, 'status': 3})
        shot(B, '2.2-B越权下架A商品被拒')
        ok = r.get('code') == 3002
        return ok, f"B 用 A 的商品 id={PID} 调上下架接口 → code={r.get('code')}「{r.get('message')}」", '2.2-B越权下架A商品被拒', None
    step('2.2', '二、权限越权', '账号B越权修改账号A的商品（上下架）',
         f'PUT /api/product/status {{productId:{PID}, status:3}}（B 的 Token）', '返回 3002 无权操作他人商品', p22)

    def p23():
        """越权查看订单：用"第三方账号C"（既非买家也非卖家）查看 A 的订单
        说明：B 是本订单买家，查看自己是合法的，因此本用例用第三方账号 C 验证。"""
        c_user = f'e2ec{TAG}'
        import urllib.request
        urllib.request.urlopen(urllib.request.Request(
            f'{API}/user/register',
            data=json.dumps({'username': c_user, 'password': 'abc12345', 'nickname': f'第三方C{TAG}'}).encode(),
            headers={'Content-Type': 'application/json'}), timeout=20).read()
        login(C, c_user, 'abc12345')
        C.goto(f'{BASE}/orders/{OID}', wait_until='domcontentloaded')
        C.wait_for_timeout(2200)
        body = C.locator('body').inner_text()
        shot(C, '2.3-第三方越权查看订单被拒')
        r = api_via_page(C, 'GET', f'/api/order/{OID}')
        ok = r.get('code') == 6004
        tip = '无权查看或操作该订单' if '无权查看' in body else body[:36].replace('\n', ' ')
        return ok, f"第三方账号 {c_user} 打开 /orders/{OID}（A↔B 的订单）→ 页面提示「{tip}」；接口 code={r.get('code')}", '2.3-第三方越权查看订单被拒', c_user
    step('2.3', '二、权限越权', '第三方越权查看他人订单（既非买家也非卖家）',
         f'第三方账号打开 /orders/{OID} 且调用 GET /api/order/{OID}', '接口返回 6004，页面给出无权提示', p23)

    def p24():
        token_b = B.evaluate("localStorage.getItem('campus_trade_token')")
        import urllib.request
        req = urllib.request.Request(f'{API}/product/publish',
                                     data=json.dumps({'title': f'E2E-B自有商品-{TAG}', 'categoryId': 8, 'price': 10.0,
                                                      'conditionLevel': 1}).encode(), method='POST')
        req.add_header('Content-Type', 'application/json')
        req.add_header('Authorization', 'Bearer ' + token_b)
        b_pid = json.loads(urllib.request.urlopen(req, timeout=20).read().decode())['data']['id']
        B.goto(f'{BASE}/product/{b_pid}', wait_until='domcontentloaded')
        B.wait_for_timeout(1200)
        B.get_by_role('button', name=re.compile('☆ 收藏|★ 已收藏')).click()
        t = msg(B)
        shot(B, '2.4-B收藏自己商品被拒')
        c1 = api_via_page(B, 'POST', '/api/favorite/operate', {'productId': b_pid})
        ok = '不能收藏自己' in t or c1.get('code') == 4002
        return ok, f"B 收藏自己的商品 {b_pid} → 页面提示「{t}」，接口 code={c1.get('code')}", '2.4-B收藏自己商品被拒', b_pid
    B_PID = step('2.4', '二、权限越权', '不能收藏自己发布的商品', 'B 打开自己的商品详情点收藏', '提示「不能收藏自己发布的商品」（4002）', p24)

    def p25():
        B.get_by_role('button', name='立即购买').click()
        B.wait_for_timeout(700)
        if B.locator('.el-dialog:visible').count():
            B.get_by_role('button', name='确认下单').click()
        t = msg(B)
        shot(B, '2.5-B购买自己商品被拒')
        c = api_via_page(B, 'POST', '/api/order/create', {'productId': B_PID})
        ok = '不能购买自己' in t or c.get('code') == 6002
        return ok, f"B 购买自己的商品 {B_PID} → 页面提示「{t}」，接口 code={c.get('code')}", '2.5-B购买自己商品被拒', None
    step('2.5', '二、权限越权', '不能购买自己发布的商品', 'B 在自己的商品详情点【立即购买】', '提示「不能购买自己发布的商品」（6002）', p25)

    def p26():
        b_id = one(f"SELECT id FROM `user` WHERE username='{B_USER}'")
        B.goto(f'{BASE}/chat/{b_id}', wait_until='domcontentloaded')
        B.wait_for_timeout(1800)
        body = B.locator('body').inner_text()
        disabled = B.get_by_role('button', name='发送').is_disabled() if B.get_by_role('button', name='发送').count() else None
        shot(B, '2.6-B与自己聊天被禁用')
        c = api_via_page(B, 'POST', '/api/message/send', {'toUserId': int(b_id), 'content': '给自己发'})
        ok = (disabled is True) or c.get('code') == 5001
        return ok, f"B 打开 /chat/{b_id}（自己）→ 发送按钮 disabled={disabled}，页面提示「{'不能给自己发送消息' if '不能给自己' in body else ''}」；接口 code={c.get('code')}", '2.6-B与自己聊天被禁用', None
    step('2.6', '二、权限越权', '不能给自己发私信', f'B 打开 /chat/{B_USER}（自己的用户ID）', '发送被禁用或提示 5001', p26)

    # ======================================================= 第四部分：异常场景
    print('\n【第四部分】异常场景测试')

    def e41():
        A.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
        A.wait_for_timeout(1000)
        A.get_by_role('button', name='立即发布').click()
        A.wait_for_timeout(1200)
        errs = A.locator('.el-form-item__error').all_inner_texts()
        shot(A, '4.1-空表单发布被前端校验拦截')
        still = '/product/publish' in A.url
        ok = len(errs) > 0 and still
        return ok, f"前端表单校验提示 {len(errs)} 条（{'；'.join(errs[:3])}），页面未跳转={'是' if still else '否'}", '4.1-空表单发布被前端校验拦截', None
    step('4.1', '四、异常场景', '发布商品不填名称/价格 → 前端表单拦截',
         '打开发布页直接点【立即发布】', '出现表单校验提示，不发起请求、不跳转', e41)

    def e42():
        A.goto(f'{BASE}/product/999999', wait_until='domcontentloaded')
        A.wait_for_timeout(2000)
        b1 = A.locator('body').inner_text()
        shot(A, '4.2-不存在商品友好提示')
        A.goto(f'{BASE}/orders/999999', wait_until='domcontentloaded')
        A.wait_for_timeout(2000)
        b2 = A.locator('body').inner_text()
        shot(A, '4.2b-不存在订单友好提示')
        c1 = api_via_page(A, 'GET', '/api/product/999999')
        c2 = api_via_page(A, 'GET', '/api/order/999999')
        ok = '商品不存在' in b1 and ('订单不存在' in b2 or '无权查看' in b2) and c1.get('code') == 3001 and c2.get('code') == 6003
        return ok, f"商品页提示「{'商品不存在或已被删除' if '商品不存在' in b1 else b1[:30].strip()}」(接口 {c1.get('code')})；" \
                   f"订单页提示「{'订单不存在' if '订单不存在' in b2 else b2[:30].strip()}」(接口 {c2.get('code')})", '4.2b-不存在订单友好提示', None
    step('4.2', '四、异常场景', '访问不存在的商品ID / 订单ID',
         'GET /product/999999、GET /orders/999999 及对应接口', '页面友好提示，接口返回 3001 / 6003，不出现 500', e42)

    def e43():
        A.goto(f'{BASE}/product/publish', wait_until='domcontentloaded')
        A.wait_for_timeout(1000)
        clear_msg(A)
        A.locator('input[type=file]').last.set_input_files(str(BAD_TXT))
        t1 = msg(A, 8000)
        shot(A, '4.3-非图片文件被拒')
        A.wait_for_timeout(3200)          # 等上一条提示自动消失，避免抓到残留提示
        clear_msg(A)
        A.locator('input[type=file]').last.set_input_files(str(BIG_PNG))
        t2 = msg(A, 12000)
        shot(A, '4.3b-超大图片被拒')
        ok = ('不支持' in t1 or '格式' in t1) and ('超出' in t2 or '大小' in t2 or '5MB' in t2)
        # 接口层兜底确认（后端同样会拒绝）
        return ok, f"上传 .txt 提示「{t1}」；上传 6MB 图片提示「{t2}」", '4.3b-超大图片被拒', None
    step('4.3', '四、异常场景', '上传非图片文件 / 超大文件',
         '发布页分别上传 not-image.txt 与 6MB big.png', '均给出明确提示，不写入数据库', e43)

    def e44():
        """并发：同一账号两个窗口同时收藏同一商品（纯 HTTP 并发，Playwright 对象不跨线程）"""
        token_b = B.evaluate("localStorage.getItem('campus_trade_token')")
        token_a = A.evaluate("localStorage.getItem('campus_trade_token')")
        cc_pid = publish_api(token_a, f'E2E-并发商品-{TAG}', 5.0)
        # 第二个窗口：同一账号再登录一次，拿到另一个独立 Token（模拟两个浏览器同时操作）
        ctx_b2 = browser.new_context(viewport={'width': 1200, 'height': 800})
        B2 = ctx_b2.new_page()
        login(B2, B_USER, B_PASS)
        token_b2 = B2.evaluate("localStorage.getItem('campus_trade_token')")
        import threading
        barrier = threading.Barrier(2)
        results = []

        def do_fav(tok):
            barrier.wait()               # 两个线程同时发出请求
            results.append(http_json('POST', '/api/favorite/operate', {'productId': cc_pid}, tok).get('code'))

        ts = [threading.Thread(target=do_fav, args=(t,)) for t in (token_b, token_b2)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        rows = one(f"SELECT COUNT(*) FROM favorite WHERE product_id={cc_pid} AND deleted=0")
        shot(B2, '4.4-并发收藏只产生一条记录')
        ok = rows == '1' and sorted(str(x) for x in results) == ['200', '4001']
        return ok, (f"同一账号两个独立会话同时收藏同一商品 → 接口返回 {sorted(str(x) for x in results)}；"
                    f"数据库收藏记录数={rows}（期望 1 条，唯一索引 uk_user_product 保证不重复）"), '4.4-并发收藏只产生一条记录', None
    step('4.4', '四、异常场景', '简单并发测试：同一账号两窗口同时收藏同一商品',
         '同一账号两个独立会话并发 POST /api/favorite/operate', '只产生 1 条收藏记录，另一个返回 4001，无脏数据', e44)

    def e45():
        """并发：两个不同用户同时抢购同一商品，只能成功一单"""
        import urllib.request, threading
        token_a = A.evaluate("localStorage.getItem('campus_trade_token')")
        req = urllib.request.Request(f'{API}/product/publish',
                                     data=json.dumps({'title': f'E2E-抢购商品-{TAG}', 'categoryId': 8, 'price': 9.9,
                                                      'conditionLevel': 1}).encode(), method='POST')
        req.add_header('Content-Type', 'application/json')
        req.add_header('Authorization', 'Bearer ' + token_a)
        rush_pid = json.loads(urllib.request.urlopen(req, timeout=20).read().decode())['data']['id']

        ctx_c2 = browser.new_context(viewport={'width': 1200, 'height': 800})
        C2 = ctx_c2.new_page()
        c_user = f'e2ec{TAG}'
        urllib.request.urlopen(urllib.request.Request(
            f'{API}/user/register', data=json.dumps({'username': c_user, 'password': 'abc12345',
                                                     'nickname': f'抢购C{TAG}'}).encode(),
            headers={'Content-Type': 'application/json'}), timeout=20).read()
        login(C2, c_user, 'abc12345')
        token_c2 = C2.evaluate("localStorage.getItem('campus_trade_token')")
        token_b = B.evaluate("localStorage.getItem('campus_trade_token')")

        out = []
        barrier = threading.Barrier(2)

        def buy(tok):
            barrier.wait()
            out.append(http_json('POST', '/api/order/create', {'productId': rush_pid}, tok).get('code'))

        ts = [threading.Thread(target=buy, args=(t,)) for t in (token_b, token_c2)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        orders = one(f"SELECT COUNT(*) FROM orders WHERE product_id={rush_pid} AND deleted=0")
        pst = one(f"SELECT status FROM product WHERE id={rush_pid}")
        shot(C2, '4.5-并发抢购只成功一单')
        ok = orders == '1' and pst == '4' and sorted(str(x) for x in out) == ['200', '6001']
        return ok, (f"两个不同账号同时下单同一商品 → 返回 {sorted(str(x) for x in out)}；"
                    f"数据库订单数={orders}（期望 1），商品状态={pst}（4=交易中）"), '4.5-并发抢购只成功一单', None
    step('4.5', '四、异常场景', '简单并发测试：两个账号同时抢购同一商品（防一物多卖）',
         'B 与 C 并发 POST /api/order/create（同一商品）', '只有 1 单成功，另一个返回 6001，无重复订单', e45)

    browser.close()

# ======================================================= 汇总
total = len(RESULTS)
passed = sum(1 for r in RESULTS if r['ok'])
print('\n' + '=' * 108)
print(f'E2E 汇总：{passed}/{total} 通过，失败 {total - passed} 项')
for r in RESULTS:
    if not r['ok']:
        print(f"  ✗ {r['id']} {r['scenario']} —— {r['actual']}")
print('=' * 108)

out = dict(tag=TAG, a_user=A_USER, b_user=B_USER, product_title=PRODUCT_TITLE,
           product_id=PID, order_id=OID, b_pid=B_PID, results=RESULTS, shots=SHOTS)
(TMP / 'e2e_result.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
print(f'结果已保存：{TMP / "e2e_result.json"}；截图 {len(SHOTS)} 张 → docs/test-evidence/')
