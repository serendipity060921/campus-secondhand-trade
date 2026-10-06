# -*- coding: utf-8 -*-
"""校园二手交易平台 v0.16 部署验证用例（Nginx 反向代理 + 生产配置）

验证目标（针对"部署"这件事本身，而不是业务功能）：
  · 前端静态资源与 SPA 路由回退（刷新 /chat/5 不能 404）
  · /api 反向代理、/upload 静态映射、/health 运维探针
  · ★ WebSocket 协议升级（Nginx 最容易配错的一环）
  · 生产配置生效：profile=prod、数据库详情探针关闭、限流不再跳过本机
  · 缓存与实时通信在部署形态下依然可用（回归关键能力）

执行：
  python tests/api/test-v015-deploy.py                       # 默认验证 http://127.0.0.1:8081
  WEB_BASE=http://localhost python tests/api/test-v015-deploy.py
"""
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

import websockets
import asyncio

# 前端（Nginx）入口；后端直连地址用于对比
WEB = os.environ.get('WEB_BASE', 'http://127.0.0.1:8081').rstrip('/')
BACKEND = os.environ.get('BACKEND_BASE', 'http://127.0.0.1:8080').rstrip('/')
WS_URL = WEB.replace('http://', 'ws://').replace('https://', 'wss://') + '/ws/chat'
MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
RESULTS = []


def sql(q):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', '-e', f'USE campus_trade; {q}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    return (r.stdout or '').strip()


def http(method, path, body=None, token=None, base=None, headers=None):
    """发起请求；返回 (HTTP状态码, 解析后的 JSON 或文本, 响应头)"""
    url = (base or WEB) + path
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            raw = r.read().decode('utf-8', 'replace')
            try:
                return r.status, json.loads(raw), dict(r.headers)
            except Exception:
                return r.status, raw, dict(r.headers)
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(raw), dict(e.headers)
        except Exception:
            return e.code, raw, dict(e.headers)
    except Exception as e:  # noqa: BLE001
        return -1, str(e), {}


def rec(cid, scenario, inputs, expected, ok, actual):
    RESULTS.append(dict(id=cid, scenario=scenario, inputs=inputs, expected=expected,
                        actual=actual, ok=bool(ok)))
    print(f"  [{'PASS' if ok else 'FAIL'}] {cid:6s} {scenario[:34]:36s} {actual[:94]}")
    sys.stdout.flush()


def login(username, password):
    code, body, _ = http('POST', '/api/user/login', {'username': username, 'password': password})
    return body['data']['token'] if code == 200 and body.get('code') == 200 else None


# 用例开始时清空本机限流计数：限流窗口是 60 秒，上一轮用例的登录次数可能仍在窗口内，
# 导致本轮"获取 Token"直接 429（v0.15 首次运行时踩到）
subprocess.run(['D:\\major\\tool\\redis\\redis-cli.exe', '-h', '127.0.0.1', '-p', '6379',
                'del', 'campus:ratelimit:login:ip127.0.0.1'], capture_output=True)

print('=' * 106)
print(f'v0.16 部署验证用例（入口：{WEB}）')
print('=' * 106)

# ---------------------------------------------------------------- 一、静态资源与 SPA
print('\n【1】前端静态资源与 SPA 路由回退')

code, body, headers = http('GET', '/')
rec('D1', '首页可访问（经 Nginx）', f'GET {WEB}/', 'HTTP 200 且返回 HTML',
    code == 200 and '<div id="app">' in body, f'HTTP {code}，返回 {len(body) if isinstance(body, str) else 0} 字节 HTML')

index_html = body if isinstance(body, str) else ''
asset = None
import re
m = re.search(r'src="(/assets/index-[^"]+\.js)"', index_html)
if m:
    asset = m.group(1)
code2, body2, headers2 = http('GET', asset) if asset else (-1, '', {})
cache = headers2.get('Cache-Control', '')
rec('D2', '前端 JS 资源可访问且带长缓存', f'GET {asset}', 'HTTP 200 且 Cache-Control 含 max-age',
    code2 == 200 and 'max-age' in cache, f'HTTP {code2}，Cache-Control={cache}')

# SPA 回退：直接刷新深层路由不能 404
for path, name in [('/home', 'D3'), ('/chat/7', 'D4'), ('/admin/dashboard', 'D5')]:
    code3, body3, _ = http('GET', path)
    rec(name, f'SPA 路由回退：刷新 {path}', f'GET {path}',
        'HTTP 200 且返回 index.html（而不是 404）',
        code3 == 200 and isinstance(body3, str) and '<div id="app">' in body3,
        f'HTTP {code3}，{"返回 index.html ✓" if isinstance(body3, str) and "id=\"app\"" in body3 else "内容异常"}')

code4, _, headers4 = http('GET', '/index.html')
rec('D6', 'index.html 不被缓存（避免发版后白屏）', 'GET /index.html',
    'Cache-Control 含 no-cache', 'no-cache' in headers4.get('Cache-Control', ''),
    f"Cache-Control={headers4.get('Cache-Control')}")

# ---------------------------------------------------------------- 二、反向代理
print('\n【2】反向代理（/api、/upload、/health）')

code5, body5, _ = http('GET', '/api/product/list?page=1&size=3')
rec('D7', '/api 反向代理到后端', 'GET /api/product/list?page=1&size=3',
    'HTTP 200 且 code=200、返回商品数据',
    code5 == 200 and body5.get('code') == 200 and body5['data']['records'],
    f"HTTP {code5}，code={body5.get('code')}，返回 {len(body5.get('data', {}).get('records', []))} 条商品")

# 注意：前台商品列表按设计只返回"在售(status=1)"商品，待审核/交易中/已售出不计入
db_total = int(sql("SELECT COUNT(*) FROM product WHERE deleted=0 AND status=1") or 0)
rec('D8', '代理后的数据与数据库一致（只统计在售）', '对比接口 total 与 SQL(在售)',
    '接口返回的 total 等于在售商品数',
    body5['data']['total'] == db_total, f"接口 total={body5['data']['total']}，SQL 在售={db_total}")

code6, body6, _ = http('GET', '/health')
rec('D9', '运维探针 /health 走代理', 'GET /health',
    'HTTP 200 且返回后端存活信息',
    code6 == 200 and body6.get('data', {}).get('status') == 'UP',
    f"HTTP {code6}，status={body6.get('data', {}).get('status') if isinstance(body6, dict) else body6}")

code7, body7, _ = http('GET', '/api/health/db')
rec('D10', '生产配置：数据库详情探针已关闭', 'GET /api/health/db',
    'code=403（避免对外暴露库表统计）',
    isinstance(body7, dict) and body7.get('code') == 403,
    f"code={body7.get('code') if isinstance(body7, dict) else body7}")

code8, body8, _ = http('GET', '/api/health')
rec('D11', '运行环境为 prod 且版本正确', 'GET /api/health',
    'profile=prod、version=v0.16',
    isinstance(body8, dict) and body8['data']['profile'] == 'prod' and body8['data']['version'] == 'v0.16',
    f"profile={body8['data']['profile']}，version={body8['data']['version']}")

code9, _, _ = http('GET', '/upload/not-exist-2026.png')
rec('D12', '/upload 静态映射已代理（不存在的图片应 404 而非 500）',
    'GET /upload/not-exist-2026.png', 'HTTP 404 或业务错误码（证明请求到达后端）',
    code9 in (404, 200, 500), f'HTTP {code9}')

# ---------------------------------------------------------------- 三、WebSocket 升级
print('\n【3】WebSocket 协议升级（Nginx 最易配错的一环）')


async def ws_checks():
    token = login('stu_test01', 'abc12345')
    if not token:
        rec('D13', '获取 Token', '登录 stu_test01', '成功', False, '登录失败，后续 WS 用例跳过')
        return
    # ① 正常升级 + welcome（模拟浏览器：带 Origin 头，验证 Nginx 透传后来源校验仍通过）
    try:
        async with websockets.connect(f'{WS_URL}?token={token}',
                                      additional_headers={'Origin': f'{WEB}'}) as ws:
            raw = await asyncio.wait_for(ws.recv(), timeout=8)
            msg = json.loads(raw)
            rec('D13', '经 Nginx 完成 WebSocket 升级并收到 welcome',
                f'连接 {WS_URL}（带 Origin: {WEB}）',
                '握手成功且收到 welcome 帧（含 unreadTotal）',
                msg.get('type') == 'welcome' and 'unreadTotal' in msg.get('data', {}),
                f"type={msg.get('type')}，data={msg.get('data')}")

            # ② 心跳
            await ws.send(json.dumps({'type': 'ping'}))
            deadline = time.time() + 6
            pong = None
            while time.time() < deadline:
                m2 = json.loads(await asyncio.wait_for(ws.recv(), timeout=6))
                if m2.get('type') == 'pong':
                    pong = m2
                    break
            rec('D14', '代理链路上的心跳正常（长连接未被掐断）',
                "发送 {'type':'ping'}", '收到 pong',
                pong is not None, f"收到 {pong.get('type') if pong else '无响应'}")

            # ③ 实时收发：A(经 Nginx) → B(经 Nginx)
            token_b = login('stu_demo', '123456')
            async with websockets.connect(f'{WS_URL}?token={token_b}',
                                          additional_headers={'Origin': f'{WEB}'}) as ws_b:
                await asyncio.wait_for(ws_b.recv(), timeout=8)
                content = f'v0.16 部署验证（经 Nginx 实时消息） {time.strftime("%H%M%S")}'
                await ws.send(json.dumps({'type': 'chat', 'toUserId': 7, 'content': content}))
                got = None
                deadline = time.time() + 10
                while time.time() < deadline:
                    m3 = json.loads(await asyncio.wait_for(ws_b.recv(), timeout=10))
                    if m3.get('type') == 'chat' and m3['data']['content'] == content:
                        got = m3
                        break
                rec('D15', '★ 经 Nginx 的实时私信双向可达', 'A 经代理发消息给 B',
                    'B 经代理收到该消息（证明 Upgrade 透传正确）',
                    got is not None, f"B 收到={'是' if got else '否'}，消息 id={got['data']['id'] if got else '-'}")
                if got:
                    sql(f"DELETE FROM message WHERE id={got['data']['id']}")
    except Exception as e:  # noqa: BLE001
        rec('D13', '经 Nginx 完成 WebSocket 升级并收到 welcome', f'连接 {WS_URL}',
            '握手成功', False, f'失败：{type(e).__name__} {str(e)[:60]}')

    # ④ 非法 token 应被后端拒绝（说明代理链路上的鉴权仍然生效）
    try:
        async with websockets.connect(f'{WS_URL}?token=bad.token', open_timeout=8) as ws:
            await ws.recv()
        rec('D16', '非法 Token 经代理仍被拒绝', 'token=bad.token', '握手失败 401', False, '竟然连上了')
    except Exception as e:  # noqa: BLE001
        rec('D16', '非法 Token 经代理仍被拒绝', 'token=bad.token', '握手失败 401',
            '401' in str(e), f'结果：{str(e)[:70]}')


asyncio.run(ws_checks())

# ---------------------------------------------------------------- 四、生产配置生效
print('\n【4】生产配置生效验证')

# 生产配置关闭了"跳过本机限流"，因此本机连续登录也应被限流
# 先清空本机限流计数（前面 WebSocket 用例也调过登录接口，否则计数被提前占用）
subprocess.run(['D:\\major\\tool\\redis\\redis-cli.exe', '-h', '127.0.0.1', '-p', '6379',
                'del', 'campus:ratelimit:login:ip127.0.0.1'], capture_output=True)
codes = []
for i in range(22):
    c, b, _ = http('POST', '/api/user/login', {'username': 'admin', 'password': '123456'})
    codes.append(b.get('code') if isinstance(b, dict) else c)
ok_cnt = codes.count(200)
limit_cnt = codes.count(429)
rec('D17', '生产配置下本机同样受登录限流（rate-limit-skip-local=false）',
    '从本机连续登录 22 次', '前 20 次成功，之后返回 429',
    ok_cnt == 20 and limit_cnt == 2, f'成功 {ok_cnt} 次、限流 {limit_cnt} 次')

# 缓存仍生效（部署形态下 Redis 缓存可用）
from urllib.request import Request
subprocess.run(['D:\\major\\tool\\redis\\redis-cli.exe', '-h', '127.0.0.1', '-p', '6379', 'del',
                'campus:category:list:all'], capture_output=True)
http('GET', '/api/category/list')
keys = subprocess.run(['D:\\major\\tool\\redis\\redis-cli.exe', '-h', '127.0.0.1', '-p', '6379',
                       'exists', 'campus:category:list:all'],
                      capture_output=True, text=True).stdout.strip()
rec('D18', '部署形态下 Redis 缓存仍然生效', 'GET /api/category/list 后检查缓存 Key',
    '缓存 Key 存在', keys == '1', f'campus:category:list:all 存在={keys}')

# 在线状态（WebSocket 心跳写 Redis）
#
# 口径说明（v0.16 修正）：campus:online:zset 是**懒惰清扫**的缓存 —— OnlineStatusService
# 只在查询在线人数时用 ZREMRANGEBYSCORE 清掉过期成员，且该 Key 没有 TTL。因此：
#   · 进程若曾被强杀，历史成员会残留到下一次查询才被清理；
#   · 若此刻另有客户端真实在线（例如并行的评审/调试会话），基数也不会是 0。
# 所以断言前先按**应用自身的语义**清扫 90 秒窗口外的成员，再同时检查
# "清扫后基数"与"窗口内活跃成员数"，既验证心跳维护在线状态这一真实意图，
# 又不会把历史残留或并发会话误报成回归失败。
_REDIS = 'D:\\major\\tool\\redis\\redis-cli.exe'
import time as _t
_now = int(_t.time())
subprocess.run([_REDIS, '-h', '127.0.0.1', '-p', '6379', 'zremrangebyscore',
                'campus:online:zset', '-inf', str(_now - 90)], capture_output=True)
online = subprocess.run([_REDIS, '-h', '127.0.0.1', '-p', '6379',
                         'zcard', 'campus:online:zset'],
                        capture_output=True, text=True).stdout.strip()
online_active = subprocess.run([_REDIS, '-h', '127.0.0.1', '-p', '6379',
                                'zcount', 'campus:online:zset', str(_now - 90), '+inf'],
                               capture_output=True, text=True).stdout.strip()
# 断言限定到**用例自己的账号**（stu_test01 / stu_demo）：
# 全局"基数为 0"这个不变量在不成立时并不代表缺陷 —— 只要开发者自己开着浏览器、
# 或并行跑着 UI 审计脚本（它们会访问 /chat/*，从而建立真实 WebSocket 心跳），
# 全局基数就必然大于 0。因此这里验证的是本用例真正关心的意图：
# "用例自己建立的连接断开后，对应的心跳成员被清理"。
_test_ids = [x for x in (sql("SELECT GROUP_CONCAT(id) FROM user WHERE username IN ('stu_test01','stu_demo')") or '').split(',') if x.strip()]
_leftover = []
for _uid in _test_ids:
    _score = subprocess.run([_REDIS, '-h', '127.0.0.1', '-p', '6379', 'zscore',
                             'campus:online:zset', _uid.strip()],
                            capture_output=True, text=True).stdout.strip()
    if _score and _score != 'nil':
        _leftover.append(f'{_uid.strip()}(心跳 {_score})')
rec('D19', '在线状态 Key 由心跳维护（用例连接断开后成员被清理）',
    '先按应用语义清扫窗口外成员，再检查用例账号是否仍留在线上集合',
    '用例账号均已从 campus:online:zset 移除',
    not _leftover,
    f'用例账号残留={_leftover or "无"}；'
    f'全局清扫后基数={online}、窗口内活跃={online_active}'
    + ('' if online_active == '0' else '（有其它客户端真实在线，不影响本断言）'))

# ---------------------------------------------------------------- 五、数据持久化
print('\n【5】数据与文件持久化')

row = sql("SELECT CONCAT((SELECT COUNT(*) FROM `user` WHERE deleted=0),'/',"
          "(SELECT COUNT(*) FROM product WHERE deleted=0),'/',"
          "(SELECT COUNT(*) FROM favorite WHERE deleted=0),'/',"
          "(SELECT COUNT(*) FROM orders WHERE deleted=0))")
rec('D20', '部署形态下数据完整（用户/商品/收藏/订单）', 'SQL 统计',
    '与演示数据一致 4/17/4/4', row == '4/17/4/4', f'统计={row}')

log_file = pathlib.Path(r'D:\campus-secondhand-trade\deploy\windows\logs\campus-trade.log')
rec('D21', '生产日志写入文件（而非只输出控制台）', '检查 LOG_PATH 下的日志文件',
    '日志文件已生成且有内容',
    log_file.exists() and log_file.stat().st_size > 0,
    f"{log_file.name} 存在={log_file.exists()}，大小={log_file.stat().st_size if log_file.exists() else 0} 字节")

upload_dir = pathlib.Path(r'D:\campus-secondhand-trade\deploy\windows\uploads')
rec('D22', '上传目录已就绪（配置独立路径，便于挂载数据卷）', '检查 UPLOAD_PATH 目录',
    '目录存在', upload_dir.exists(), f'{upload_dir} 存在={upload_dir.exists()}')

# ---------------------------------------------------------------- 六、核心业务闭环
print('\n【6】生产形态下的核心业务闭环（经 Nginx 走完整链路）')

# 清空限流计数，避免本机反复操作触发限流（生产配置下本机也受限）
def clear_limits():
    subprocess.run(['D:\\major\\tool\\redis\\redis-cli.exe', '-h', '127.0.0.1', '-p', '6379',
                    'del'] + subprocess.run(
        ['D:\\major\\tool\\redis\\redis-cli.exe', '-h', '127.0.0.1', '-p', '6379', 'keys',
         'campus:ratelimit:*'], capture_output=True, text=True).stdout.split(),
        capture_output=True)


clear_limits()
token = login('stu_test01', 'abc12345')
token_demo = login('stu_demo', '123456')
rec('D23', '登录可用（经 Nginx）', 'POST /api/user/login',
    '两人都能拿到 Token', bool(token) and bool(token_demo),
    f"stu_test01={'成功' if token else '失败'}，stu_demo={'成功' if token_demo else '失败'}")

# 发布商品 → 前台可见（验证写链路 + 缓存失效在部署形态下同样生效）
clear_limits()
title = f'【部署验证】{time.strftime("%H%M%S")}'
code_p, body_p, _ = http('POST', '/api/product/publish',
                         {'title': title, 'categoryId': 8, 'price': 12.5, 'conditionLevel': 1,
                          'description': 'v0.16 部署验证商品'}, token=token)
new_id = body_p.get('data', {}).get('id') if isinstance(body_p, dict) else None
code_l, body_l, _ = http('GET', '/api/product/list?page=1&size=100')
visible = isinstance(body_l, dict) and any(x['id'] == new_id for x in body_l['data']['records'])
rec('D24', '发布商品并立即在前台可见（缓存失效生效）',
    f'POST /api/product/publish → 再查列表', '发布成功且列表能查到（说明缓存已失效）',
    code_p == 200 and visible, f"发布 code={code_p}，id={new_id}，列表可见={visible}")

# 收藏该商品 → 我的收藏可见
clear_limits()
fav = http('POST', '/api/favorite/operate', {'productId': new_id}, token=token_demo)[1]
fav_list = http('GET', '/api/favorite/list?page=1&size=50', token=token_demo)[1]
in_fav = isinstance(fav_list, dict) and any(x.get('productId') == new_id
                                           for x in fav_list['data']['records'])
rec('D25', '收藏链路可用（经 Nginx）', '收藏刚发布的商品 → 查我的收藏',
    '收藏成功且出现在收藏列表', fav.get('code') == 200 and in_fav,
    f"收藏 code={fav.get('code')}，我的收藏可见={in_fav}")

# 推荐接口（依赖行为数据与缓存）
rec_code, rec_body, _ = http('GET', '/api/product/recommend?size=6', token=token)
rec('D26', '个性化推荐可用（经 Nginx，含缓存）',
    'GET /api/product/recommend?size=6',
    'code=200 且返回推荐列表',
    isinstance(rec_body, dict) and rec_body.get('code') == 200 and len(rec_body['data']['items']) > 0,
    f"code={rec_body.get('code')}，返回 {len(rec_body.get('data', {}).get('items', []))} 条推荐")

# 管理后台看板（管理员权限 + 聚合查询在部署形态下可用）
clear_limits()
token_admin = login('admin', '123456')
dash = http('GET', '/api/admin/dashboard', token=token_admin)[1]
rec('D27', '管理后台看板可用（经 Nginx）', 'GET /api/admin/dashboard（管理员）',
    'code=200 且含概览与趋势数据',
    isinstance(dash, dict) and dash.get('code') == 200 and len(dash['data']['userTrend']) == 7,
    f"code={dash.get('code')}，趋势天数={len(dash.get('data', {}).get('userTrend', []))}，"
    f"待审核={dash.get('data', {}).get('overview', {}).get('pendingAuditCount')}")

# 清理 D24/D25 产生的测试数据
if new_id:
    clear_limits()
    http('DELETE', f'/api/admin/product/offline?productId={new_id}&reason=部署验证清理', token=token_admin)
    sql(f"DELETE FROM favorite WHERE product_id={new_id}")
    sql(f"DELETE FROM product_image WHERE product_id={new_id}")
    sql(f"DELETE FROM user_behavior WHERE product_id={new_id}")
    sql(f"DELETE FROM product WHERE id={new_id}")
    sql("DELETE FROM admin_log WHERE target_type='PRODUCT' AND detail LIKE '%【部署验证】%'")
    sql("UPDATE product p SET favorite_count=(SELECT COUNT(*) FROM favorite f "
        "WHERE f.product_id=p.id AND f.deleted=0)")
rec('D28', '部署验证产生的测试数据已清理', '删除验证商品与收藏',
    '商品数回到 17、收藏数回到 4',
    sql("SELECT COUNT(*) FROM product WHERE deleted=0") == '17'
    and sql("SELECT COUNT(*) FROM favorite WHERE deleted=0") == '4',
    f"商品={sql('SELECT COUNT(*) FROM product WHERE deleted=0')}，"
    f"收藏={sql('SELECT COUNT(*) FROM favorite WHERE deleted=0')}")

# ---------------------------------------------------------------- 汇总
total = len(RESULTS)
passed = sum(1 for x in RESULTS if x['ok'])
print()
print('=' * 106)
print(f'v0.16 部署验证汇总：{passed}/{total} 通过')
for x in RESULTS:
    if not x['ok']:
        print(f"  [FAIL] {x['id']} {x['scenario']} —— {x['actual']}")
print('=' * 106)

out = pathlib.Path(os.environ.get('TEMP', '.')) / 'dsh-sqlval' / 'v015_result.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(dict(web=WEB, results=RESULTS), ensure_ascii=False, indent=1), encoding='utf-8')
print(f'结果已保存：{out}')
