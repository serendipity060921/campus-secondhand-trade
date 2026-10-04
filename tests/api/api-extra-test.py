# -*- coding: utf-8 -*-
"""v0.10 测试方案 · 第三部分「各模块独立功能检查」接口层补充用例

覆盖浏览器难以覆盖的数据层校验：
  - 商品：下架后首页列表不可见、下架后无法下单
  - 收藏：重复收藏不产生多条记录（数据库计数）、商品下架后收藏列表不展示
  - 私信：会话列表最新消息与未读数、聊天记录分页、已读标记
  - 用户：密码 BCrypt 密文、JWT 过期 401、非法 ID 不抛 500
执行：python tests/api/api-extra-test.py
"""
import base64
import hashlib
import hmac
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = 'http://127.0.0.1:8080/api'
MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
TMP = pathlib.Path(os.environ.get('TEMP', '.')) / 'dsh-sqlval'
JWT_SECRET = 'campus-secondhand-trade-jwt-secret-key-2026-graduation-project'
RESULTS = []


def sql(q):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', '-e', f'USE campus_trade; {q}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=30)
    return (r.stdout or '').strip()


def one(q):
    v = sql(q)
    return v.splitlines()[0] if v else ''


def call(method, path, body=None, token=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return json.loads(raw)
        except Exception:
            return {'code': e.code, 'message': raw[:80]}
    except Exception as e:  # noqa: BLE001
        return {'code': -1, 'message': str(e)}


def brief(r):
    return f"code={r.get('code')} {str(r.get('message', ''))[:36]}"


def rec(cid, section, scenario, inputs, expected, ok, actual):
    RESULTS.append(dict(id=cid, section=section, scenario=scenario, inputs=inputs,
                        expected=expected, actual=actual, ok=bool(ok), evidence=''))
    print(f"  [{'PASS' if ok else 'FAIL'}] {cid:9s} {scenario[:36]:38s} {actual[:92]}")
    sys.stdout.flush()


def login(u, p):
    r = call('POST', '/user/login', {'username': u, 'password': p})
    return r['data']['token'] if r.get('code') == 200 else None


def expired_token():
    now = int(time.time())
    b64 = lambda d: base64.urlsafe_b64encode(d).rstrip(b'=').decode()
    segs = [b64(json.dumps({'alg': 'HS256'}, separators=(',', ':')).encode()),
            b64(json.dumps({'sub': '1', 'username': 'admin', 'role': 1, 'iss': 'campus-trade',
                            'iat': now - 10000, 'exp': now - 3600}, separators=(',', ':')).encode())]
    return '.'.join(segs) + '.' + b64(hmac.new(JWT_SECRET.encode(), '.'.join(segs).encode(), hashlib.sha256).digest())


tag = uuid.uuid4().hex[:5]
admin = login('admin', '123456')
stu = login('stu_test01', 'abc12345')
seller = f'apx{tag}'
call('POST', '/user/register', {'username': seller, 'password': 'abc12345', 'nickname': f'补充卖家{tag}'})
seller_token = login(seller, 'abc12345')
seller_id = one(f"SELECT id FROM `user` WHERE username='{seller}'")
stu_id = one(f"SELECT id FROM `user` WHERE username='stu_test01'")

print('=' * 104)
print('第三部分补充用例（接口层 / 数据层校验）')
print('=' * 104)

# ---------- 商品模块 ----------
r = call('POST', '/product/publish', {'title': f'补充测试商品-{tag}', 'categoryId': 8, 'price': 20.0,
                                      'conditionLevel': 2, 'description': '接口层补充用例'}, token=seller_token)
pid = r['data']['id']
rec('3A-1', 'three', '商品发布后处于在售状态', f'POST /api/product/publish → id={pid}', 'code=200 且 status=1',
    r.get('code') == 200 and one(f'SELECT status FROM product WHERE id={pid}') == '1',
    f"{brief(r)}，数据库 status={one(f'SELECT status FROM product WHERE id={pid}')}")

r = call('GET', '/product/list?page=1&size=50')
in_list = any(x['id'] == pid for x in r['data']['records'])
rec('3A-2', 'three', '在售商品出现在首页列表', 'GET /api/product/list', '列表中包含该商品',
    in_list, f"code={r.get('code')}，列表中{'包含' if in_list else '不包含'} id={pid}")

r = call('PUT', '/product/status', {'productId': pid, 'status': 3}, token=seller_token)
r2 = call('GET', '/product/list?page=1&size=50')
gone = not any(x['id'] == pid for x in r2['data']['records'])
rec('3A-3', 'three', '商品下架后首页列表不再展示', f'下架 id={pid} 后再查列表', '下架成功且列表中不可见',
    r.get('code') == 200 and gone, f"{brief(r)}；下架后列表中{'已无该商品' if gone else '仍能看到该商品'}")

r = call('POST', '/order/create', {'productId': pid}, token=stu)
rec('3A-4', 'three', '商品下架后无法下单', f'stu_test01 下单已下架商品 id={pid}', 'code=6001 商品已下架',
    r.get('code') == 6001, brief(r))

rec('3A-5', 'three', '商品详情正常展示发布者信息', f'GET /api/product/{pid}',
    '返回卖家昵称等信息', call('GET', f'/product/{pid}').get('data', {}).get('sellerNickname') == f'补充卖家{tag}',
    f"卖家昵称={call('GET', f'/product/{pid}').get('data', {}).get('sellerNickname')}")

# ---------- 收藏模块 ----------
call('PUT', '/product/status', {'productId': pid, 'status': 1}, token=seller_token)
c1 = call('POST', '/favorite/operate', {'productId': pid}, token=stu)
c2 = call('POST', '/favorite/operate', {'productId': pid}, token=stu)   # 重复收藏
cnt = one(f"SELECT COUNT(*) FROM favorite WHERE product_id={pid} AND user_id={stu_id} AND deleted=0")
rec('3B-1', 'three', '重复收藏不会生成多条记录', f'对 id={pid} 连续收藏两次', '第一次 200，第二次 4001，数据库仅 1 条',
    c1.get('code') == 200 and c2.get('code') == 4001 and cnt == '1',
    f"第一次 {brief(c1)}；第二次 {brief(c2)}；数据库记录数={cnt}")

fav = call('GET', '/favorite/list?page=1&size=50', token=stu)
has = any(x['productId'] == pid for x in fav['data']['records'])
call('PUT', '/product/status', {'productId': pid, 'status': 3}, token=seller_token)
fav2 = call('GET', '/favorite/list?page=1&size=50', token=stu)
still = any(x['productId'] == pid for x in fav2['data']['records'])
rec('3B-2', 'three', '商品下架后收藏列表不再展示', f'收藏 id={pid} → 下架 → 再看收藏列表',
    '下架前可见、下架后不展示', has and not still,
    f"下架前{'可见' if has else '不可见'}，下架后{'仍展示' if still else '已移除'}")

c3 = call('POST', '/favorite/operate', {'productId': pid, 'type': 2}, token=stu)
fav3 = call('GET', '/favorite/list?page=1&size=50', token=stu)
rec('3B-3', 'three', '取消收藏后收藏列表移除该商品', f'取消收藏 id={pid}', '取消成功且列表不再包含',
    c3.get('code') == 200 and not any(x['productId'] == pid for x in fav3['data']['records']), brief(c3))

# ---------- 私信模块 ----------
call('PUT', '/product/status', {'productId': pid, 'status': 1}, token=seller_token)
m1 = call('POST', '/message/send', {'toUserId': int(seller_id), 'content': f'补充用例消息1-{tag}', 'productId': pid},
          token=stu)
time.sleep(1.1)
m2 = call('POST', '/message/send', {'toUserId': int(seller_id), 'content': f'补充用例消息2-{tag}', 'productId': pid},
          token=stu)
conv = call('GET', '/message/conversationList', token=seller_token)
mine = [c for c in conv['data'] if str(c['peerId']) == str(stu_id)]
last_ok = bool(mine) and f'补充用例消息2-{tag}' in (mine[0].get('lastMessage') or '')
rec('3C-1', 'three', '会话列表自动更新为最新一条消息', '连续发两条消息后查会话列表',
    'lastMessage 为第二条（最新）', last_ok,
    f"会话 lastMessage=「{(mine[0].get('lastMessage') if mine else '')[:24]}」，未读数={mine[0].get('unreadCount') if mine else '-'}")

hist = call('GET', f'/message/history?peerId={stu_id}&page=1&size=1', token=seller_token)
rec('3C-2', 'three', '聊天记录分页加载', 'GET /api/message/history?page=1&size=1',
    '返回 1 条且 total>1（分页生效）', hist.get('code') == 200 and len(hist['data']['records']) == 1 and hist['data']['total'] > 1,
    f"code={hist.get('code')}，本页 {len(hist.get('data', {}).get('records', []))} 条，总计 {hist.get('data', {}).get('total')} 条")

call('PUT', '/message/read', {'peerId': int(stu_id)}, token=seller_token)
unread = one(f"SELECT COUNT(*) FROM message WHERE to_user_id={seller_id} AND from_user_id={stu_id} AND is_read=0")
rec('3C-3', 'three', '消息已读标记生效', 'A 标记与 B 的会话已读', '未读消息数变为 0',
    unread == '0', f"标记已读后数据库中未读消息数={unread}")

# ---------- 用户模块 ----------
pwd = one(f"SELECT password FROM `user` WHERE username='{seller}'")
rec('3D-1', 'three', '密码以 BCrypt 密文存储（看不到明文）', 'SELECT password FROM user',
    '以 $2a$ 开头、长度 60 的密文', pwd.startswith('$2a$') and len(pwd) == 60,
    f"密文前缀={pwd[:10]}…，长度={len(pwd)}")

r = call('GET', '/user/info', token=expired_token())
rec('3D-2', 'three', 'JWT 过期后访问接口返回 401', '使用已过期 Token 请求 /api/user/info',
    'code=401 登录已过期', r.get('code') == 401, brief(r))

r1 = call('GET', '/product/999999')
r2 = call('GET', '/order/999999', token=stu)
rec('3D-3', 'three', '非法 ID 不抛 500 异常', 'GET /api/product/999999、GET /api/order/999999',
    '分别返回 3001 / 6003 业务码', r1.get('code') == 3001 and r2.get('code') == 6003,
    f"商品 {brief(r1)}；订单 {brief(r2)}")

r = call('POST', '/product/publish', {'title': '', 'categoryId': None, 'price': -5, 'conditionLevel': 9},
         token=seller_token)
rec('3D-4', 'three', '后端参数校验：空名称 / 负价格 / 非法成色', 'POST /api/product/publish 非法参数',
    'code=400 并返回字段级提示', r.get('code') == 400, brief(r))

# ---------- 搜索与分类 ----------
r = call('GET', '/product/search?keyword=' + urllib.parse.quote(f'补充测试商品-{tag}'))
rec('3E-1', 'three', '搜索框按名称模糊匹配', f'keyword=补充测试商品-{tag}', '命中该商品',
    r.get('code') == 200 and r['data']['total'] >= 1,
    f"命中 {r['data']['total']} 条，首条=「{(r['data']['records'][0]['title'] if r['data']['records'] else '')}」")

r = call('GET', '/product/search?categoryId=8&page=1&size=50')
only8 = all(x['categoryId'] == 8 for x in r['data']['records'])
rec('3E-2', 'three', '分类筛选只返回该分类商品', 'categoryId=8（其他闲置）',
    '结果全部属于该分类', r.get('code') == 200 and only8, f"命中 {r['data']['total']} 条，全部分类一致={only8}")

total = len(RESULTS)
passed = sum(1 for x in RESULTS if x['ok'])
print('=' * 104)
print(f'第三部分补充用例汇总：{passed}/{total} 通过')
for x in RESULTS:
    if not x['ok']:
        print(f"  ✗ {x['id']} {x['scenario']} —— {x['actual']}")

(TMP / 'api_extra_result.json').write_text(json.dumps(dict(results=RESULTS, tag=tag, seller=seller, pid=pid),
                                                       ensure_ascii=False, indent=1), encoding='utf-8')
print(f'结果已保存：{TMP / "api_extra_result.json"}')
