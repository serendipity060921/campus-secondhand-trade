# -*- coding: utf-8 -*-
"""答辩演示走场脚本（⑧）

作用有两个：
  1. **彩排**：答辩前跑一遍，确认演示路径上每一步都真的可用（而不是现场才发现问题）；
  2. **取证**：把每一步的"实际发生了什么"打印出来，可直接对照 docs/答辩演示脚本.md 的话术。

演示主线（覆盖本课题的四个亮点：内容机审 / 缓存与并发 / 交易状态机 / 越权防护）：

  步骤 1  机审拒绝：发布含"代写"的商品 → 期望 3010 内容不符合平台规范
  步骤 2  机审转人工复核：发布含手机号的商品 → 期望发布成功且商品为「待审核」
  步骤 3  正常发布 → 期望发布成功且商品为「在售」
  步骤 4  收藏（买家）→ 期望成功，且"我的收藏"可见
  步骤 5  下单（买家）→ 期望成功，商品变「交易中」（4）
  步骤 6  完成交易（卖家）→ 订单「已完成」（3）、商品「已售出」（5）、双方信用分 +1
  步骤 7  越权：管理员账号访问该订单 → 期望 6004（既非买家也非卖家）
  步骤 8  管理后台看板与待审核列表 → 期望可见步骤 2 的商品
  步骤 9  站内信：买家发消息给卖家 → 期望成功且卖家未读数增加

注意：本脚本会**真实写入数据**（3 件商品 + 1 笔订单 + 1 条消息）。
     演示或彩排结束后执行 `python tools/seed-demo-data.py --reset` 即可恢复演示基线
     （机审记录表按只追加原则不清空）。

执行：
  python tools/demo-rehearsal.py
"""

# ★ Windows 控制台的默认编码是 GBK，而本脚本会输出 ✓/✗/✅ 等字符 ——
#   不重新配置 stdout 的话，手动执行会直接抛 UnicodeEncodeError 中断。
import sys as _sys
try:
    _sys.stdout.reconfigure(encoding='utf-8')
    _sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

WEB = os.environ.get('WEB_BASE', 'http://127.0.0.1:8081').rstrip('/')
MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')

SELLER = ('stu_test01', 'abc12345')
BUYER = ('stu_demo', '123456')
ADMIN = ('admin', '123456')

PASS, FAIL = [], []


def sql(q):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-u', 'root', '-p123456', '-N', '-B',
                        '--default-character-set=utf8mb4', 'campus_trade', '-e', q],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    return (r.stdout or '').strip()


def http(method, path, body=None, token=None, timeout=20):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(WEB + path, data=data, method=method)
    if data is not None:
        req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode('utf-8', 'replace') or '{}')
        except Exception:
            return {'code': e.code}


def step(title):
    print('\n' + '─' * 92)
    print('  ' + title)
    print('─' * 92)


def check(sid, desc, ok, detail=''):
    (PASS if ok else FAIL).append(sid)
    print(f"  {'✓' if ok else '✗'} {sid:<5} {desc}")
    if detail:
        print(f"          → {detail}")
    return ok


def login(username, password):
    r = http('POST', '/api/user/login', {'username': username, 'password': password})
    return (r.get('data') or {}).get('token')


def product_status(pid):
    v = sql(f"SELECT status FROM product WHERE id = {pid}")
    return int(v.splitlines()[0]) if v else None


def credit_scores():
    v = sql("SELECT username, credit_score FROM user WHERE username IN ('stu_test01','stu_demo') "
            "ORDER BY username")
    return dict(line.split('\t') for line in v.splitlines() if line.strip())


def publish(token, title, desc, price, category_id):
    return http('POST', '/api/product/publish', {
        'title': title, 'description': desc, 'price': price,
        'categoryId': category_id, 'conditionLevel': 3,
        'campus': '东校区', 'tradePlace': '图书馆门口'}, token)


def main():
    print('=' * 92)
    print('答辩演示走场：按 docs/答辩演示脚本.md 的主线逐条验证（真实调用部署形态接口）')
    print('=' * 92)

    seller = login(*SELLER)
    buyer = login(*BUYER)
    admin = login(*ADMIN)
    if not (seller and buyer and admin):
        print('  ✗ 前置登录失败，请确认服务与账号（见文件头说明）')
        return 1
    print(f'  前置：卖家 {SELLER[0]} / 买家 {BUYER[0]} / 管理员 {ADMIN[0]} 均已登录')

    category_id = int((sql("SELECT id FROM category WHERE deleted = 0 AND parent_id IS NOT NULL "
                           "ORDER BY id LIMIT 1") or '1').splitlines()[0])
    mark = '【演示】'

    # ---------------------------------------------------------------- 步骤 1
    step('步骤 1｜内容机审（第一层）：违禁内容直接拒绝')
    r = publish(seller, mark + '代写课程论文 包过', '专业代写，包过', 100, category_id)
    check('1', '发布含「代写」的商品被拒绝（code=3010）', r.get('code') == 3010,
          f"实际 code={r.get('code')} message={r.get('message')}")
    n = sql(f"SELECT COUNT(*) FROM moderation_log WHERE action='REJECT' AND title LIKE '{mark}%'")
    check('1b', '机审记录已落库（moderation_log，拒绝也留痕）', int(n or 0) >= 1,
          f"REJECT 记录数={n}")

    # ---------------------------------------------------------------- 步骤 2
    step('步骤 2｜内容机审（第一层）：可疑引流转人工复核')
    r = publish(seller, mark + '低价出教材 联系13812345678', '有意者联系', 20, category_id)
    pid_review = (r.get('data') or {}).get('id')
    ok2 = r.get('code') == 200 and pid_review is not None
    check('2', '含手机号的商品发布成功', ok2, f"code={r.get('code')} 商品id={pid_review}")
    if ok2:
        st = product_status(pid_review)
        check('2b', '★ 该商品被置为「待审核」（0），不会直接上架', st == 0, f'实际 status={st}')
        n = sql(f"SELECT COUNT(*) FROM moderation_log WHERE action='REVIEW' AND title LIKE '{mark}%'")
        check('2c', '转审记录已落库', int(n or 0) >= 1, f"REVIEW 记录数={n}")

    # ---------------------------------------------------------------- 步骤 3
    step('步骤 3｜正常发布：直接上架（保证校园场景的流通效率）')
    r = publish(seller, mark + '九成新《数据结构》教材', '东校区自取，书况良好', 25.5, category_id)
    pid_sale = (r.get('data') or {}).get('id')
    ok3 = r.get('code') == 200 and pid_sale is not None
    check('3', '正常商品发布成功', ok3, f"code={r.get('code')} 商品id={pid_sale}")
    if ok3:
        st = product_status(pid_sale)
        check('3b', '该商品为「在售」（1）', st == 1, f'实际 status={st}')

    if not (pid_review and pid_sale):
        print('\n  ✗ 关键步骤失败，后续依赖商品的步骤跳过')
        return 1

    # ---------------------------------------------------------------- 步骤 4
    step('步骤 4｜收藏：写入 + 我的收藏可见 + 缓存联动')
    r = http('POST', '/api/favorite/operate', {'productId': pid_sale}, buyer)
    check('4', '买家收藏成功（code=200）', r.get('code') == 200, f"code={r.get('code')}")
    r = http('GET', '/api/favorite/list?page=1&size=20', token=buyer)
    ids = [x.get('productId') for x in ((r.get('data') or {}).get('records') or [])]
    check('4b', '「我的收藏」里能看到它', pid_sale in ids, f'收藏商品 id 列表前几项={ids[:5]}')

    # ---------------------------------------------------------------- 步骤 5
    step('步骤 5｜下单：条件更新锁商品（防一物多卖）')
    r = http('POST', '/api/order/create', {'productId': pid_sale}, buyer)
    order_id = (r.get('data') or {}).get('id')
    ok5 = r.get('code') == 200 and order_id is not None
    check('5', '买家下单成功', ok5, f"code={r.get('code')} 订单id={order_id} message={r.get('message')}")
    if ok5:
        st = product_status(pid_sale)
        check('5b', '★ 商品被锁为「交易中」（4），其他买家抢不到', st == 4, f'实际 status={st}')
        r2 = http('POST', '/api/order/create', {'productId': pid_sale}, admin)
        check('5c', '★ 同一商品再下单被拒（防一物多卖）', r2.get('code') != 200,
              f"第二次下单 code={r2.get('code')}")

    # ---------------------------------------------------------------- 步骤 6
    step('步骤 6｜完成交易：状态流转 + 商品已售出 + 双方信用分 +1')
    before = credit_scores()
    if order_id:
        r = http('PUT', '/api/order/status', {'orderId': order_id, 'status': 3}, seller)
        check('6', '卖家确认交易完成（code=200）', r.get('code') == 200, f"code={r.get('code')}")
        ost = sql(f"SELECT status FROM orders WHERE id = {order_id}")
        check('6b', '订单状态 = 已完成（3）', ost == '3', f'实际 status={ost}')
        st = product_status(pid_sale)
        check('6c', '商品状态 = 已售出（5）', st == 5, f'实际 status={st}')
        after = credit_scores()
        ok6d = all(int(after.get(u, 0)) == int(before.get(u, 0)) + 1 for u in before)
        check('6d', '★ 买卖双方信用分各 +1', ok6d, f'{before} → {after}')

    # ---------------------------------------------------------------- 步骤 7
    step('步骤 7｜越权防护：既非买家也非卖家不能查看订单')
    if order_id:
        r = http('GET', f'/api/order/{order_id}', token=admin)
        check('7', '管理员（第三方身份）访问该订单被拒（code=6004）', r.get('code') == 6004,
              f"code={r.get('code')} message={r.get('message')}")
        r = http('GET', f'/api/order/{order_id}', token=buyer)
        check('7b', '对照：买家本人可查看', r.get('code') == 200, f"code={r.get('code')}")

    # ---------------------------------------------------------------- 步骤 8
    step('步骤 8｜管理后台（内容治理第二层）：看板与待审核列表')
    r = http('GET', '/api/admin/dashboard', token=admin)
    ok8 = r.get('code') == 200
    check('8', '管理端看板可用', ok8,
          f"code={r.get('code')} 待审核={((r.get('data') or {}).get('pendingProductCount'))}")
    r = http('GET', '/api/admin/product/list?page=1&size=20&status=0', token=admin)
    titles = [x.get('title') for x in ((r.get('data') or {}).get('records') or [])]
    check('8b', '待审核列表里能看到步骤 2 的商品',
          any(mark in (t or '') for t in titles), f'待审核标题前几项={titles[:3]}')

    # ---------------------------------------------------------------- 步骤 9
    step('步骤 9｜站内信：发消息 + 未读数（实时通道用 WebSocket，此处验证落库与未读）')
    unread_before = http('GET', '/api/message/unreadTotal', token=seller).get('data')
    r = http('POST', '/api/message/send',
             {'toUserId': None, 'content': mark + '你好，书还在吗？', 'productId': pid_sale}, buyer)
    if r.get('code') != 200:
        # 需要卖家 userId：从订单里取
        seller_id = sql(f"SELECT seller_id FROM orders WHERE id = {order_id}") if order_id else ''
        if seller_id:
            r = http('POST', '/api/message/send',
                     {'toUserId': int(seller_id), 'content': mark + '你好，书还在吗？',
                      'productId': pid_sale}, buyer)
    check('9', '买家发送站内信成功', r.get('code') == 200, f"code={r.get('code')}")
    unread_after = http('GET', '/api/message/unreadTotal', token=seller).get('data')
    check('9b', '卖家未读数增加', unread_after != unread_before,
          f'未读数 {unread_before} → {unread_after}')

    # ---------------------------------------------------------------- 汇总
    print('\n' + '=' * 92)
    print(f'  走场结果：{len(PASS)} 项通过，{len(FAIL)} 项未通过' +
          ('' if not FAIL else f'（未通过：{", ".join(FAIL)}）'))
    print('=' * 92)
    print('  提示：本次走场写入了真实数据，请执行下面这条恢复演示基线：')
    print('        python tools/seed-demo-data.py --reset')
    if not FAIL:
        print('  ✅ 演示主线全程可用，可以放心彩排/演示')
    return 0 if not FAIL else 1


if __name__ == '__main__':
    sys.exit(main())
