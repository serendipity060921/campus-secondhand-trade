# -*- coding: utf-8 -*-
"""v0.13 管理后台端到端浏览器验证（含真实审核流程）

流程：
  1. 学生账号访问 /admin → 被路由守卫拦回首页
  2. 管理员登录 → 数据看板（截图）→ 校验统计卡片、图表、待办提示
  3. 商品管理 → 对「待审核」商品执行"通过" → 弹窗填写审核意见 → 确认（截图）
  4. 回前台首页 → 审核通过的商品已出现在列表中（业务闭环）
  5. 举报处理 / 用户管理页面截图
"""
import os
import pathlib
import re

from playwright.sync_api import sync_playwright

# 入口可用环境变量覆盖：E2E_BASE=http://127.0.0.1:8081 即可验证 Nginx 部署形态
BASE = os.environ.get('E2E_BASE', 'http://127.0.0.1:5173')
EV = pathlib.Path(r'D:\campus-secondhand-trade\docs\test-evidence')
EV.mkdir(parents=True, exist_ok=True)
shots = []


def shot(page, name):
    path = EV / name
    page.screenshot(path=str(path), full_page=True)
    shots.append(name)
    print(f'   [截图] {name}')



# ---------------------------------------------------------------- 前置数据与清理
# 本脚本需要一个「待审核」商品才能执行审核流程；库里若没有（例如上一次运行已把它审掉），
# 它会一直等「通过」按钮直到超时。这里改为**自带前置数据**：开始时插入一件待审核商品，
# 结束时按标题精确删除，保证脚本可重复运行且不留痕。
import subprocess  # noqa: E402
import uuid  # noqa: E402

MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
TAG = uuid.uuid4().hex[:5]
FIXTURE_TITLE = f'E2E审核演示商品-{TAG}'


def sql(q):
    r = subprocess.run([MYSQL, '-h127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B',
                        '-e', f'USE campus_trade; {q}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=30)
    return (r.stdout or '').strip()


def seed_pending_product():
    sql("INSERT INTO product (title, description, category_id, seller_id, price, original_price, "
        "cover_image, condition_level, campus, trade_place, status) VALUES "
        f"('{FIXTURE_TITLE}', '端到端脚本插入的前置数据，用于验证审核流程，运行结束会自动删除。', "
        "10, 5, 19.90, 39.90, '/demo-images/textbook.png', 1, '东校区', '东校区图书馆门口', 0)")
    return sql(f"SELECT id FROM product WHERE title='{FIXTURE_TITLE}' AND deleted=0 LIMIT 1")


def cleanup_fixture():
    sql(f"DELETE FROM product WHERE title LIKE 'E2E审核演示商品-{TAG}'")
    left = sql(f"SELECT COUNT(*) FROM product WHERE title LIKE 'E2E审核演示商品-{TAG}'")
    total = sql('SELECT COUNT(*) FROM product WHERE deleted=0')
    pending = sql('SELECT COUNT(*) FROM product WHERE deleted=0 AND status=0')
    print(f'   清理前置数据：残留={left}，商品总数={total}，待审核={pending}')


import atexit  # noqa: E402
atexit.register(cleanup_fixture)

FIXTURE_ID = seed_pending_product()
print(f'   已插入前置待审核商品 id={FIXTURE_ID}，标题={FIXTURE_TITLE}')

with sync_playwright() as pw:
    browser = pw.chromium.launch(channel='msedge', headless=True)
    ctx = browser.new_context(viewport={'width': 1500, 'height': 950}, locale='zh-CN')
    page = ctx.new_page()
    page.set_default_timeout(30000)
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))

    print('1) 学生账号访问后台（应被拦截）')
    page.goto(f'{BASE}/login')
    page.get_by_placeholder('请输入用户名').fill('stu_test01')
    page.get_by_placeholder('请输入密码').fill('abc12345')
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile('/home'))
    page.goto(f'{BASE}/admin/dashboard')
    page.wait_for_timeout(2500)
    print(f'   跳转后 URL = {page.url}（不含 /admin 表示拦截成功）')

    print('2) 管理员登录 → 数据看板')
    page.goto(f'{BASE}/login')
    page.get_by_placeholder('请输入用户名').fill('admin')
    page.get_by_placeholder('请输入密码').fill('123456')
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile('/home'))
    page.goto(f'{BASE}/admin/dashboard')
    page.wait_for_timeout(3500)
    cards = page.locator('.stat-value').all_inner_texts()
    print(f'   概览卡片 {len(cards)} 项: {cards}')
    print(f'   图表 canvas 数 = {page.locator("canvas").count()}')
    shot(page, 'v013-1-管理后台数据看板.png')

    print('3) 商品管理 → 审核通过一件商品')
    page.goto(f'{BASE}/admin/products')
    page.wait_for_timeout(2500)
    before = page.locator('.el-table__body .title').all_inner_texts()
    print(f'   审核前待审核商品 {len(before)} 件: {[t[:16] for t in before]}')
    target_title = before[0] if before else None
    page.locator('.el-table__body tr').first.get_by_role('button', name='通过').click()
    page.wait_for_timeout(1200)
    shot(page, 'v013-2-审核弹窗.png')
    page.get_by_role('button', name='确认通过').click()
    page.wait_for_timeout(2500)
    after = page.locator('.el-table__body .title').all_inner_texts()
    print(f'   审核后待审核商品 {len(after)} 件（应少 1）')
    shot(page, 'v013-3-审核后列表.png')

    print('4) 回前台首页验证商品已上架')
    page.goto(f'{BASE}/home')
    page.wait_for_timeout(2500)
    titles = page.locator('.product-title, .product-card').all_inner_texts()
    hit = target_title and any(target_title[:12] in t for t in titles) if titles else False
    print(f'   首页商品标题 {len(titles)} 条，包含刚审核通过的商品 = {hit}')
    shot(page, 'v013-4-审核通过后前台可见.png')

    print('5) 举报处理与用户管理')
    page.goto(f'{BASE}/admin/reports')
    page.wait_for_timeout(2200)
    rows = page.locator('.el-table__body tr').count()
    print(f'   待处理举报 {rows} 条')
    shot(page, 'v013-5-举报处理.png')
    page.goto(f'{BASE}/admin/users')
    page.wait_for_timeout(2200)
    print(f'   用户 {page.locator(".el-table__body tr").count()} 条')
    shot(page, 'v013-6-用户管理.png')

    print(f'   页面 JS 错误: {errors if errors else "无"}')
    print('6) 清理前置数据')
    cleanup_fixture()
    browser.close()

print(f'共 {len(shots)} 张截图已保存到 {EV}')
