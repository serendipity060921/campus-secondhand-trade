#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
双向评价功能验证（接口侧，v0.18）

覆盖 9xxx 业务码、信用分联动、匿名分层、汇总口径、读接口可见性。
只用标准库（urllib），不依赖 requests；不连数据库，所有断言都通过接口可观察量表达。

前置（演示基线）：
  订单 1：买家 stu_test01(id=5)、卖家 admin(id=1)、商品 8、状态 3（已完成）
  订单 2：买家 stu_test01、状态 4（已取消）—— 用于"未完成订单不能评价"
  stu_demo(id=7)：第三方 —— 用于"非买卖双方不能评价"

用法：
    python tests/api/test-evaluation.py
    python tests/api/test-evaluation.py --base http://127.0.0.1:8080

退出码：0 全部通过；1 有失败。
"""
import argparse
import json
import os
import sys
import tempfile
import urllib.error
import urllib.request

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

BASE = 'http://127.0.0.1:8080'
ORDER_OK = 1        # 已完成
ORDER_CANCELED = 2  # 已取消
PRODUCT = 8
BUYER = ('stu_test01', 'abc12345')
SELLER = ('admin', '123456')
THIRD = ('stu_demo', '123456')

results = []


def call(method, path, token=None, body=None):
    url = BASE + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode('utf-8')
        headers['Content-Type'] = 'application/json; charset=utf-8'
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw}


def check(ok, name, detail=''):
    results.append({'name': name, 'ok': ok, 'detail': detail})
    print(f'  {"✓" if ok else "✗"} {name}' + (f'  {detail}' if detail else ''))


def login(cred):
    _, r = call('POST', '/api/user/login', body={'username': cred[0], 'password': cred[1]})
    return (r.get('data') or {}).get('token')


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default=BASE)
    args = ap.parse_args()
    BASE = args.base

    tb, ts, tt = login(BUYER), login(SELLER), login(THIRD)
    check(bool(tb and ts and tt), '三个账号登录成功', f'buyer={bool(tb)} seller={bool(ts)} third={bool(tt)}')
    if not (tb and ts and tt):
        return 1

    print('\n一、读接口（公开，未登录也能看）')
    st, r = call('GET', f'/api/evaluation/product/{PRODUCT}?page=1&size=10')
    check(st == 200 and r.get('code') == 200, '匿名可以看商品评价列表', f'HTTP {st} 共 {((r.get("data") or {}).get("total"))} 条')
    st, r = call('GET', f'/api/evaluation/summary/product/{PRODUCT}')
    check(st == 200 and r.get('code') == 200, '匿名可以看评价汇总',
          f'count={((r.get("data") or {}).get("count"))}')

    print('\n二、评价权限与状态校验（9xxx）')
    st, r = call('POST', '/api/evaluation', tt, {'orderId': ORDER_OK, 'rating': 5})
    check(r.get('code') == 9002, '第三方评价 → 9002 非买卖双方', r.get('message', ''))
    st, r = call('POST', '/api/evaluation', tb, {'orderId': ORDER_CANCELED, 'rating': 5})
    check(r.get('code') == 9003, '已取消订单评价 → 9003 订单未完成', r.get('message', ''))
    st, r = call('POST', '/api/evaluation', tb, {'orderId': 999999, 'rating': 5})
    check(r.get('code') == 9001, '订单不存在 → 9001', r.get('message', ''))
    st, r = call('POST', '/api/evaluation', tb, {'orderId': ORDER_OK, 'rating': 6})
    check(r.get('code') == 400, '评分 6 → 400 参数校验', r.get('message', ''))

    print('\n三、正常评价（买家评卖家）')
    before = credit_of(ts, ORDER_OK)   # 先记卖家的信用分（可见于订单详情的 counterpart）
    st, r = call('POST', '/api/evaluation', tb,
                 {'orderId': ORDER_OK, 'rating': 5, 'content': '验证脚本：书很新', 'tags': '描述相符,态度很好'})
    d = r.get('data') or {}
    check(r.get('code') == 200, '买家评价成功', f'code={r.get("code")}')
    check(d.get('role') == 1 and d.get('roleLabel') == '买家评卖家', '★ 角色由订单推导：买家评卖家',
          f'role={d.get("role")} to={d.get("toNickname")}')
    check(d.get('tagList') == ['描述相符', '态度很好'], '标签按逗号切成列表', str(d.get('tagList')))
    check(d.get('mine') is True, '自己写的评价 mine=true')

    print('\n四、重复评价与并发兜底')
    st, r = call('POST', '/api/evaluation', tb, {'orderId': ORDER_OK, 'rating': 4})
    check(r.get('code') == 9004, '重复评价 → 9004', r.get('message', ''))

    print('\n五、双向：卖家评买家 + 匿名')
    st, r = call('POST', '/api/evaluation', ts, {'orderId': ORDER_OK, 'rating': 2, 'anonymous': 1})
    d2 = r.get('data') or {}
    check(r.get('code') == 200, '卖家评价成功', f'code={r.get("code")}')
    check(d2.get('role') == 2 and d2.get('roleLabel') == '卖家评买家', '★ 角色由订单推导：卖家评买家',
          f'role={d2.get("role")}')
    check(d2.get('fromNickname') == '匿名同学', '★ 匿名：展示层昵称被替换', str(d2.get('fromNickname')))
    check(d2.get('fromUserId') is not None, '★ 匿名：fromUserId 仍保留（可追溯）', f'fromUserId={d2.get("fromUserId")}')

    print('\n六、汇总口径')
    st, r = call('GET', f'/api/evaluation/summary/product/{PRODUCT}')
    s = r.get('data') or {}
    check(s.get('count') == 2, '条数 = 2', f'count={s.get("count")}')
    check(abs(float(s.get('avgRating') or 0) - 3.5) < 0.01, '平均分 = 3.5（5 与 2 各一条）', f'avg={s.get("avgRating")}')
    check(abs(float(s.get('goodRate') or 0) - 50.0) < 0.01, '好评率 = 50.0%（仅 5 星算好评）', f'goodRate={s.get("goodRate")}')
    dist = s.get('distribution') or {}
    check(dist.get('5') == 1 and dist.get('2') == 1, '星级分布 5:1 / 2:1', json.dumps(dist, ensure_ascii=False))

    print('\n七、匿名访问的归属不泄漏')
    st, r = call('GET', f'/api/evaluation/product/{PRODUCT}?page=1&size=10')
    recs = ((r.get('data') or {}).get('records')) or []
    check(all(x.get('mine') is False for x in recs), '未登录浏览：mine 一律 false',
          f'{len(recs)} 条')
    check(any(x.get('fromNickname') == '匿名同学' for x in recs), '列表里匿名那条显示「匿名同学」')

    print('\n八、订单级评价详情仅买卖双方可见')
    st, r = call('GET', f'/api/evaluation/order/{ORDER_OK}', tt)
    check(r.get('code') == 9002, '第三方查订单评价 → 9002', r.get('message', ''))
    st, r = call('GET', f'/api/evaluation/order/{ORDER_OK}', tb)
    check(r.get('code') == 200 and len(r.get('data') or []) == 2, '买卖双方可查（2 条）',
          f'条数={len(r.get("data") or [])}')

    print('\n九、信用分联动（4~5 星 +1 / 1~2 星 -1）')
    # 信用分不在订单详情顶层（那是 counterpart 的嵌套结构），统一从管理端用户列表读，
    # 并与演示基线对比：admin 基线 123、stu_test01 基线 101。
    st, r = call('GET', '/api/admin/user/list?page=1&size=50', ts)
    users = ((r.get('data') or {}).get('records')) or []
    check(bool(users), '能读到用户列表（用于核对信用分）', f'{len(users)} 个用户')
    if users:
        m = {u.get('username'): u.get('creditScore') for u in users}
        check(m.get('admin') == 124, '★ admin 收到 5 星 → 信用分 123→124', f'admin={m.get("admin")}')
        check(m.get('stu_test01') == 100, '★ stu_test01 收到 2 星 → 信用分 101→100',
              f'stu_test01={m.get("stu_test01")}')
        check(m.get('stu_demo') == 100, '未参与评价的第三方信用分不变', f'stu_demo={m.get("stu_demo")}')

    ok = sum(1 for x in results if x['ok'])
    print(f'\n=== 通过 {ok} / {len(results)} ===')
    out = os.path.join(tempfile.gettempdir(), 'dsh-sqlval', 'evaluation_result.json')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8') as f:
        json.dump({'base': BASE, 'results': results}, f, ensure_ascii=False, indent=2)
    print(f'结果已写入 {out}')
    bad = [x['name'] for x in results if not x['ok']]
    for n in bad:
        print('  ✗ ' + n)
    return 0 if not bad else 1


def credit_of(token, order_id):
    """从订单详情里读"对方"的信用分（买卖双方互为 counterpart）。"""
    st, r = call('GET', f'/api/order/{order_id}', token)
    d = r.get('data') or {}
    return d.get('counterpartCreditScore') or d.get('creditScore')


if __name__ == '__main__':
    sys.exit(main())
