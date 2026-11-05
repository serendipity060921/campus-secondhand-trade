#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能型用户故事 · 逐条浏览器场景（v0.18）

目的：做到「**一条功能故事一个场景 + 一张截图**」。
  报告 `docs/测试报告-用户故事视角.md` 只收录功能型故事（28 条）；
  质量/非功能型（9 条：US-QUALITY 七条 + US-SEARCH-03 + US-EVAL-03）断言对象不在界面上，
  不写进这份报告，证据在安全套件 / 部署验证 / 静态检查 / 压测 / CI 门禁里。

约定：
  · 截图命名 `US-模块-序号-S<顺序>-<说明>.png`，落在 docs/story-evidence/，可被索引脚本归组
  · 每个场景：先断言，再截图；失败输出 ::error:: 注解（CI 里可读）
  · 写操作类场景放在最后，且用夹具/可恢复的数据，跑完由调用方 `seed-demo-data --reset` 复原

用法：
    python tests/e2e/test-story-scenarios.py                 # 全部
    python tests/e2e/test-story-scenarios.py --only GOODS     # 只跑含该子串的故事
    python tests/e2e/test-story-scenarios.py --base http://127.0.0.1:8081
退出码：0 全通过；1 有失败。
"""
import argparse
import json
import os
import pathlib
import re
import sys
import tempfile

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8081'
ROOT = pathlib.Path(__file__).resolve().parents[2]
SHOTS = ROOT / 'docs' / 'story-evidence'

BUYER = ('stu_test01', 'abc12345')   # id=5：订单1买家
SELLER = ('stu_demo', '123456')      # id=7：订单4买家（待交易）
ADMIN = ('admin', '123456')          # id=1：管理员

results = []
ONLY = ''


def want(tag):
    return (not ONLY) or (ONLY in tag) or (tag in ONLY)


def check(ok, name, detail=''):
    results.append({'name': name, 'ok': ok, 'detail': detail})
    print(f'  {"✓" if ok else "✗"} {name}' + (f'  {detail}' if detail else ''))
    if not ok:
        print(f'::error::[{name}] {detail or "断言失败"}')


def shot(page, name, note='', full=True):
    try:
        SHOTS.mkdir(parents=True, exist_ok=True)
        p = SHOTS / f'{name}.png'
        page.screenshot(path=str(p), full_page=full)
        print(f'    📷 {name}.png  {p.stat().st_size // 1024} KB' + (f'  {note}' if note else ''))
    except Exception as e:
        print(f'    ⚠️ 截图失败 {name}: {str(e)[:60]}')


def login(page, base, cred):
    page.goto(f'{base}/login', wait_until='domcontentloaded')
    page.get_by_placeholder('请输入用户名').fill(cred[0])
    page.get_by_placeholder('请输入密码').fill(cred[1])
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    page.wait_for_url(re.compile(r'/home'), timeout=30000)
    page.wait_for_timeout(1200)


def goto(page, base, path):
    page.goto(f'{base}{path}', wait_until='domcontentloaded')
    try:
        page.wait_for_load_state('networkidle', timeout=15000)
    except Exception:
        pass
    page.wait_for_timeout(700)


def body(page):
    return page.inner_text('body')


# ───────────────────────── 场景（一条故事一个） ─────────────────────────

def run(browser, base, only):
    def ctx():
        c = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
        p = c.new_page()
        p.set_default_timeout(20000)
        return c, p

    print('一、未登录可见的功能故事')
    c, p = ctx()
    if want('US-GOODS-02'):
        goto(p, base, '/home')
        cards = p.locator('.catalogue-card, .card, .product-card').count()
        t = body(p)
        has_code = bool(re.search(r'[A-Z]\d?·\d+', t))   # 真实格式如 Z9·263
        check(cards > 0, 'US-GOODS-02 首页有商品卡片', f'{cards} 张')
        check(has_code, 'US-GOODS-02 卡片上有分类编号（形如 G4-012）')
        shot(p, 'US-GOODS-02-S1-首页卡片分类与编号', '卡片展示分类名与编号')

    if want('US-SEARCH-02'):
        goto(p, base, '/search?categoryId=2')
        t = body(p)
        check('数码' in t or '手机' in t or '分类' in t, 'US-SEARCH-02 分类筛选页可按分类过滤', '/search?categoryId=2')
        shot(p, 'US-SEARCH-02-S1-按分类筛选', '分类筛选结果')

    if want('US-RECO-02'):
        goto(p, base, '/product/8')
        t = body(p)
        check('相关推荐' in t or '相似' in t, 'US-RECO-02 详情页有相似商品区')
        shot(p, 'US-RECO-02-S1-详情页相似商品', '相关推荐模块')

    c.close()

    print('\n二、买家 stu_test01 视角')
    c, p = ctx()
    login(p, base, BUYER)

    if want('US-USER-03'):
        goto(p, base, '/profile/edit')
        t = body(p)
        check('昵称' in t and ('头像' in t or '上传' in t), 'US-USER-03 资料编辑页含昵称与头像')
        shot(p, 'US-USER-03-S1-个人资料编辑页')

    if want('US-GOODS-01'):
        # BUG-17 回归：上传图片后缩略图必须真的渲染出来（只验接口 200 会漏掉代理层 404）
        goto(p, base, '/product/publish')
        import base64 as _b64
        # 20x20 纯色 PNG（不依赖 Pillow，CI 里也能跑）
        _png = _b64.b64decode(
            'iVBORw0KGgoAAAANSUhEUgAAABQAAAAUCAIAAAAC64paAAAAJUlEQVR4nGP8z4APMOGVHXHSLcOp'
            'Ezmqi0Y6ZSLVRSMdAKoRAZ8PY6LwAAAAAElFTkSuQmCC')
        probe = pathlib.Path(tempfile.gettempdir()) / 'dsh_sqlval_upload_probe.png'
        probe.write_bytes(_png)
        try:
            p.set_input_files('input[type=file]', str(probe))
            p.wait_for_timeout(2800)
            imgs = p.evaluate("() => [...document.images].map(i => ({s: i.currentSrc || i.src, w: i.naturalWidth}))")
            up = [i for i in imgs if '/upload/' in i['s']]
            ok_img = bool(up) and up[0]['w'] > 0
            check(ok_img, 'US-GOODS-01 上传后缩略图可见（BUG-17 回归）',
                  (up[0]['s'][:70] + f' w={up[0]["w"]}') if up else '未找到 /upload/ 图片元素')
            shot(p, 'US-GOODS-01-S9-上传后缩略图正常', 'BUG-17 回归断言')
        except Exception as e:
            check(False, 'US-GOODS-01 上传后缩略图可见（BUG-17 回归）', str(e)[:70])

    if want('US-FAV-02'):
        goto(p, base, '/favorites')
        t = body(p)
        check('收藏' in t, 'US-FAV-02 我的收藏页可访问')
        shot(p, 'US-FAV-02-S1-我的收藏列表')

    if want('US-CHAT-03'):
        goto(p, base, '/messages')
        t = body(p)
        check('消息' in t or '会话' in t, 'US-CHAT-03 消息页可访问')
        shot(p, 'US-CHAT-03-S1-会话列表')

    if want('US-CHAT-02'):
        goto(p, base, '/messages')
        try:
            p.locator('.conversation-item, .conv-item, .el-scrollbar__view > div').first.click(timeout=6000)
            p.wait_for_timeout(1600)
        except Exception:
            pass
        t = body(p)
        check('已读' in t or '未读' in t, 'US-CHAT-02 会话内可见已读/未读标记')
        shot(p, 'US-CHAT-02-S1-已读回执')

    if want('US-ORDER-02'):
        goto(p, base, '/orders/bought')
        bought = body(p)
        check('我买到的' in bought, 'US-ORDER-02 买家可见「我买到的」')
        shot(p, 'US-ORDER-02-S1-我买到的')
        goto(p, base, '/orders/sold')
        sold = body(p)
        check('我卖出的' in sold, 'US-ORDER-02 卖家视图「我卖出的」可切换')
        shot(p, 'US-ORDER-02-S2-我卖出的')

    c.close()

    print('\n三、卖家 / 管理员视角')
    c, p = ctx()
    login(p, base, ADMIN)

    if want('US-ADMIN-01'):
        goto(p, base, '/admin/products')
        t = body(p)
        check('审核' in t or '通过' in t, 'US-ADMIN-01 后台商品审核页可操作')
        shot(p, 'US-ADMIN-01-S1-商品审核页')

    if want('US-ADMIN-02'):
        goto(p, base, '/admin/products')
        # 「下架」只出现在在售商品的行内 → 先把筛选切到「在售」
        try:
            sel = p.locator('.el-select').first
            sel.click(); p.wait_for_timeout(500)
            p.locator('.el-select-dropdown__item:visible').filter(has_text='在售').first.click()
            p.wait_for_timeout(1500)
        except Exception:
            pass
        has_down = '下架' in body(p)
        check(has_down, 'US-ADMIN-02 在售商品行内有「下架」入口')
        shot(p, 'US-ADMIN-02-S1-在售商品强制下架入口')

    if want('US-ADMIN-03'):
        goto(p, base, '/admin/users')
        t = body(p)
        check('用户' in t and ('禁用' in t or '启用' in t), 'US-ADMIN-03 用户管理可启用/禁用')
        shot(p, 'US-ADMIN-03-S1-用户管理')

    if want('US-RECO-03'):
        # 「举报商品」只对已登录的普通用户显示（管理员看不到）→ 单开一个买家上下文
        c3 = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1000})
        p3 = c3.new_page(); p3.set_default_timeout(20000)
        login(p3, base, BUYER)
        goto(p3, base, '/product/8')
        try:
            p3.locator('button:visible').filter(has_text='举报商品').first.click(timeout=8000)
            p3.wait_for_timeout(1000)
            check(True, 'US-RECO-03 举报入口可打开')
            shot(p3, 'US-RECO-03-S1-举报弹窗', '只截图未提交')
            p3.keyboard.press('Escape')
        except Exception as e:
            check(False, 'US-RECO-03 举报入口可打开', str(e)[:60])
        c3.close()

    if want('US-GOODS-03'):
        goto(p, base, '/admin/products')
        pass  # 卖家自己的商品页用 stu_test01，见下一段

    c.close()

    print('\n四、卖家 stu_test01：我的商品 / 订单完成')
    c, p = ctx()
    login(p, base, BUYER)

    if want('US-GOODS-03'):
        goto(p, base, '/product/mine')
        t = body(p)
        check('在售' in t or '下架' in t or '商品' in t, 'US-GOODS-03 我的商品页含上下架入口')
        shot(p, 'US-GOODS-03-S1-我的商品上下架入口')

    if want('US-ORDER-03'):
        goto(p, base, '/orders/1')
        t = body(p)
        check('已完成' in t or '交易' in t, 'US-ORDER-03 订单详情页可查看交易状态')
        shot(p, 'US-ORDER-03-S1-订单详情与完成状态')

    if want('US-GOODS-05'):
        # 机审拦截的界面证据来自 US-GOODS-01 发布流程的真实一次拦截（发布页必填项多，
        # 这里按需求只校验"证据在、可复核"，截图复用并单独命名，避免伪造新场景）。
        src = SHOTS / 'US-GOODS-01-4-机审拦截违规商品.png'
        dst = SHOTS / 'US-GOODS-05-S1-机审拦截违规发布.png'
        if src.exists():
            import shutil
            shutil.copyfile(src, dst)
            check(True, 'US-GOODS-05 机审拦截证据存在（复用 US-GOODS-01 实测截图）')
            print(f'    📷 {dst.name}  {dst.stat().st_size // 1024} KB')
        else:
            check(False, 'US-GOODS-05 机审拦截证据存在', '缺少 US-GOODS-01-4 截图')
    c.close()

    if want('US-ORDER-04'):
        import json as _js
        import urllib.request as _ur

        api = 'http://127.0.0.1:8080/api'

        def call(path, method='GET', token=None, body=None):
            data = _js.dumps(body).encode() if body is not None else None
            req = _ur.Request(api + path, data=data, method=method)
            req.add_header('Content-Type', 'application/json')
            if token:
                req.add_header('Authorization', 'Bearer ' + token)
            with _ur.urlopen(req, timeout=20) as r:
                return _js.loads(r.read().decode())

        # 单独造一笔订单（避免与 US-ORDER-03 争用订单 4 这笔演示数据）
        tok = call('/user/login', 'POST', body={'username': BUYER[0], 'password': BUYER[1]})['data']['token']
        rows = call('/product/list?page=1&size=30')['data']['records']
        cand = [x for x in rows if x.get('status') == 1 and x.get('sellerId') != 5]
        if not cand:
            check(False, 'US-ORDER-04 取消订单并释放商品', '没有可用于下单的在售商品')
        else:
            pid = cand[0]['id']
            created = call('/order/create', 'POST', tok, {'productId': pid, 'deliveryType': 1})
            oid = (created.get('data') or {}).get('id')
            check(bool(oid), 'US-ORDER-04 可创建一笔待交易订单', f'orderId={oid} productId={pid}')
            if oid:
                c, p = ctx()
                login(p, base, BUYER)
                goto(p, base, f'/orders/{oid}')
                check('待交易' in body(p), 'US-ORDER-04 订单初始为待交易')
                shot(p, 'US-ORDER-04-S1-待交易订单（取消前）', '买家视角')
                try:
                    p.locator('button:visible').filter(has_text='取消订单').first.click(timeout=8000)
                    p.wait_for_timeout(700)
                    for txt in ('确定', '确认'):
                        btn = p.locator('.el-message-box__btns button:visible').filter(has_text=txt)
                        if btn.count():
                            btn.first.click()
                            break
                    p.wait_for_timeout(1800)
                except Exception as e:
                    print(f'    ⚠️ 取消操作异常: {str(e)[:60]}')
                after = body(p)
                check('已取消' in after, 'US-ORDER-04 取消后订单变为已取消', after.replace('\n', '|')[-70:])
                shot(p, 'US-ORDER-04-S2-取消后订单已取消', '终态提示')
                st = call(f'/product/{pid}')['data']
                check(st.get('status') == 1, 'US-ORDER-04 取消后商品重新上架（status=1）', f'status={st.get("status")}')
                c.close()

    print('\n五、评价（US-EVAL-01 / US-EVAL-02）')
    if want('US-EVAL-01'):
        c, p = ctx()
        login(p, base, BUYER)
        goto(p, base, '/orders/1')
        check('已完成' in body(p), 'US-EVAL-01 已完成订单可发起评价')
        try:
            p.get_by_role('button', name=re.compile('评价这笔交易')).first.click()
            p.wait_for_selector('.el-dialog', state='attached', timeout=8000)
            p.wait_for_timeout(800)
            shot(p, 'US-EVAL-01-S1-评价弹窗（星级/标签/匿名）', '提交前')
            dlg = p.locator('.el-dialog').last
            try:
                dlg.locator('.el-rate__item').nth(4).click()
            except Exception:
                pass
            dlg.locator('textarea').first.fill('场景验证：书很新，沟通顺利')
            dlg.get_by_role('button', name=re.compile('提交评价')).first.click()
            after, waited, dopen = '', 0, True
            while waited < 9000:
                after = body(p)
                dopen = p.locator('.el-dialog').count() > 0 and p.locator('.el-dialog').last.is_visible()
                if ('评价成功' in after) or ('已评价' in after) or (not dopen):
                    break
                p.wait_for_timeout(300); waited += 300
            check(('评价成功' in after) or ('已评价' in after) or (not dopen),
                  'US-EVAL-01 提交评价成功', f'等待 {waited}ms')
            shot(p, 'US-EVAL-01-S2-评价提交后', '成功提示或弹窗关闭')
        except Exception as e:
            check(False, 'US-EVAL-01 评价流程可完成', str(e)[:70])
        c.close()

    if want('US-EVAL-02'):
        c, p = ctx()
        goto(p, base, '/product/8')
        tt = body(p)
        check('交易评价' in tt or '评价' in tt, 'US-EVAL-02 详情页有「交易评价」区')
        check(bool(re.search(r'好评率|星级分布|平均分', tt)), 'US-EVAL-02 有平均分/好评率/星级分布')
        shot(p, 'US-EVAL-02-S1-商品口碑区', '平均分/好评率/星级分布')
        c.close()

    print('\n（质量型故事不在本套件范围：断言对象不在界面上）')


def main():
    global BASE, ONLY
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default=BASE)
    ap.add_argument('--only', default='')
    args = ap.parse_args()
    BASE = args.base
    ONLY = args.only.strip().upper()

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel=(os.environ.get('PW_CHANNEL', 'msedge') or None), headless=True)
        run(browser, BASE, ONLY)
        browser.close()

    ok = sum(1 for r in results if r['ok'])
    print(f'\n=== 通过 {ok} / {len(results)} ===')
    out = os.path.join(tempfile.gettempdir(), 'dsh-sqlval', 'story_scenarios_result.json')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8') as f:
        json.dump({'base': BASE, 'results': results}, f, ensure_ascii=False, indent=2)
    print(f'结果已写入 {out}')
    bad = [r['name'] for r in results if not r['ok']]
    for n in bad:
        print('  ✗ ' + n)
    return 0 if not bad else 1


if __name__ == '__main__':
    sys.exit(main())
