# -*- coding: utf-8 -*-
"""校园二手交易平台 v0.14 实时私信（WebSocket）测试用例

覆盖：
  · 握手鉴权：无 token / 非法 token / 已登出（黑名单）/ 被禁用账号
  · 连接与心跳：welcome 包（未读总数）、ping→pong
  · 消息收发：A→B 双方都收到 chat 帧、消息确实入库、REST 与 WS 两条路径一致
  · 已读回执：B 标记已读 → A 收到 read 帧，数据库 is_read 变更
  · 在线状态：WS 批量查询、REST 单查、Redis ZSet 心跳、断开后转离线
  · 多端在线：同一账号两个连接，都能收到消息；关闭一个仍在线，全部关闭才离线
  · 异常场景：给自己发、给不存在的人发、内容为空、超长内容、未知类型
  · 限流：WebSocket 通道同样受"30 条/分钟"限制（不能绕过 REST 的 @RateLimit）
  · 离线消息：对方不在线时消息仍入库，登录后可查到（不丢消息）
  · 管理联动：禁用账号会立即断开其 WebSocket 连接

执行：python tests/api/test-v014-websocket.py
前提：后端 8080、MySQL 3306、Redis 6379 已启动
"""
import asyncio
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

import websockets

API = 'http://127.0.0.1:8080/api'
WS = 'ws://127.0.0.1:8080/ws/chat'
MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
REDIS = os.environ.get('REDIS_CLI', r'D:\major\tool\redis\redis-cli.exe')
TMP = pathlib.Path(os.environ.get('TEMP', '.')) / 'dsh-sqlval'
RESULTS = []
STU_ID, DEMO_ID, ADMIN_ID = 5, 7, 1


def sql(q):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', '-e', f'USE campus_trade; {q}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    return (r.stdout or '').strip()


def one(q):
    v = sql(q)
    return v.splitlines()[0] if v else ''


def redis(*args):
    r = subprocess.run([REDIS, '-h', '127.0.0.1', '-p', '6379', *args],
                       capture_output=True, text=True, encoding='utf-8', timeout=30)
    return (r.stdout or '').strip()


def rest(method, path, body=None, token=None):
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


def login(u, p):
    r = rest('POST', '/user/login', {'username': u, 'password': p})
    return r['data']['token'] if r.get('code') == 200 else None


def rec(cid, scenario, inputs, expected, ok, actual):
    RESULTS.append(dict(id=cid, scenario=scenario, inputs=inputs, expected=expected,
                        actual=actual, ok=bool(ok)))
    print(f"  [{'PASS' if ok else 'FAIL'}] {cid:6s} {scenario[:34]:36s} {actual[:96]}")
    sys.stdout.flush()


async def wait_for(ws, msg_type, timeout=8):
    """读取帧直到拿到指定 type（跳过 online 之类的事件帧）"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        raw = await asyncio.wait_for(ws.recv(), timeout=max(0.5, deadline - time.time()))
        msg = json.loads(raw)
        if msg.get('type') == msg_type:
            return msg
    raise TimeoutError(f'未在 {timeout}s 内收到 {msg_type} 帧')


async def main():
    print('=' * 106)
    print('v0.14 实时私信（WebSocket）测试用例')
    print('=' * 106)

    token_stu = login('stu_test01', 'abc12345')
    token_demo = login('stu_demo', '123456')
    token_admin = login('admin', '123456')
    if not (token_stu and token_demo and token_admin):
        print('无法登录测试账号，请确认后端已启动')
        return

    # ---------------------------------------------------------------- 一、握手鉴权
    print('\n【1】握手鉴权')

    async def try_connect(url):
        try:
            ws = await websockets.connect(url, open_timeout=8)
            await ws.close()
            return 'connected'
        except Exception as e:  # noqa: BLE001
            return str(e)

    r1 = await try_connect(WS)
    rec('W1', '无 token 连接被拒绝', 'ws://.../ws/chat（无参数）', '握手失败且返回 401',
        '401' in r1, f'结果：{r1[:80]}')

    r2 = await try_connect(f'{WS}?token=not-a-real-token')
    rec('W2', '非法 token 连接被拒绝', 'token=not-a-real-token', '握手失败且返回 401',
        '401' in r2, f'结果：{r2[:80]}')

    # 退出登录后的 Token 进黑名单 → 不能建立实时连接
    #
    # 注意：JWT 的 iat/exp 只精确到秒，同一秒内为同一用户重复登录会签发**完全相同的 Token**，
    # 因此这里必须用一个独立账号做黑名单实验，否则会把主测试账号的 Token 一起拉黑（v0.14 踩过）。
    black_user = f'wb{int(time.time()) % 100000}'
    rest('POST', '/user/register', {'username': black_user, 'password': 'abc12345', 'nickname': '黑名单测试'})
    black_token = login(black_user, 'abc12345')
    black_id = int(one(f"SELECT id FROM `user` WHERE username='{black_user}'"))
    ok_before = 'connected' == await try_connect(f'{WS}?token={black_token}')
    rest('POST', '/user/logout', token=black_token)
    r3 = await try_connect(f'{WS}?token={black_token}')
    rec('W3', '已退出登录（黑名单）的 Token 无法连接',
        f'临时账号 {black_user} 登出后拿旧 Token 再连',
        '登出前可连接；登出后握手失败返回 401',
        ok_before and '401' in r3, f'登出前={ok_before}，登出后={r3[:60]}')
    sql(f"DELETE FROM user_behavior WHERE user_id={black_id}")
    sql(f"DELETE FROM `user` WHERE id={black_id}")

    # ---------------------------------------------------------------- 二、连接与心跳
    print('\n【2】连接、welcome 包与心跳')

    async with websockets.connect(f'{WS}?token={token_stu}') as ws_a:
        welcome = await wait_for(ws_a, 'welcome')
        unread_db = int(one(f"SELECT COUNT(*) FROM message WHERE deleted=0 AND type=2 "
                            f"AND to_user_id={STU_ID} AND is_read=0"))
        rec('W4', '连接成功下发 welcome（含未读总数）', '正常 token 连接',
            'welcome.userId 正确且 unreadTotal 与数据库一致',
            welcome['data']['userId'] == STU_ID and welcome['data']['unreadTotal'] == unread_db,
            f"userId={welcome['data']['userId']}，unreadTotal={welcome['data']['unreadTotal']}（数据库 {unread_db}）")

        await ws_a.send(json.dumps({'type': 'ping'}))
        pong = await wait_for(ws_a, 'pong')
        rec('W5', '心跳 ping → pong', "发送 {'type':'ping'}", '收到 pong 帧',
            pong['type'] == 'pong', f"收到 {pong['type']}")

        online_zscore = redis('zscore', 'campus:online:zset', str(STU_ID))
        rec('W6', '在线心跳写入 Redis（ZSet + 分用户标记）',
            '连接后检查 campus:online:zset 与标记 Key',
            'ZSet 中存在该用户且标记 Key 有 TTL',
            online_zscore not in ('', None) and int(redis('ttl', f'campus:online:user:{STU_ID}') or -1) > 0,
            f"ZSet 分数={online_zscore}，标记 Key TTL={redis('ttl', f'campus:online:user:{STU_ID}')} 秒")

        # ---------------------------------------------------------------- 三、消息收发
        print('\n【3】实时消息收发')

        async with websockets.connect(f'{WS}?token={token_demo}') as ws_b:
            await wait_for(ws_b, 'welcome')
            content = f"v0.14 自动化测试消息 {time.strftime('%H%M%S')}"
            await ws_a.send(json.dumps({'type': 'chat', 'toUserId': DEMO_ID, 'content': content}))
            got_b = await wait_for(ws_b, 'chat')
            got_a = await wait_for(ws_a, 'chat')
            rec('W7', '发送方与接收方都收到 chat 帧（多端同步）',
                'A 通过 WS 发消息给 B',
                'B 收到该消息；A 也收到自己的回显',
                got_b['data']['content'] == content and got_a['data']['content'] == content
                and got_b['data']['fromUserId'] == STU_ID,
                f"B 收到 from={got_b['data']['fromUserId']}，A 回显 id={got_a['data']['id']}")
            msg_id = got_b['data']['id']

            db_row = one(f"SELECT CONCAT(from_user_id,'/',to_user_id,'/',is_read) FROM message WHERE id={msg_id}")
            rec('W8', '消息确实写入数据库（推送不替代落库）', f'查询 message id={msg_id}',
                'from/to/is_read 正确', db_row == f'{STU_ID}/{DEMO_ID}/0',
                f"数据库记录 from/to/is_read = {db_row}")

            nickname_ok = bool(got_b['data'].get('fromNickname'))
            rec('W9', '推送帧包含发送人昵称（前端提醒需要）', '检查 chat 帧 data.fromNickname',
                'fromNickname 非空', nickname_ok, f"fromNickname={got_b['data'].get('fromNickname')}")

            # ------------------------------------------------------------ 四、已读回执
            print('\n【4】已读回执')

            await ws_b.send(json.dumps({'type': 'read', 'peerId': STU_ID}))
            receipt = await wait_for(ws_a, 'read')
            is_read_db = one(f"SELECT is_read FROM message WHERE id={msg_id}")
            rec('W10', '标记已读后对方收到 read 回执', 'B 发送 read 帧（peerId=A）',
                'A 收到 read 帧，且数据库 is_read=1',
                receipt['data']['readerId'] == DEMO_ID and receipt['data']['peerId'] == STU_ID
                and is_read_db == '1',
                f"A 收到 read(readerId={receipt['data']['readerId']})，数据库 is_read={is_read_db}")

            # ------------------------------------------------------------ 五、在线状态
            print('\n【5】在线状态')

            await ws_a.send(json.dumps({'type': 'queryOnline', 'userIds': [STU_ID, DEMO_ID, 6]}))
            online = await wait_for(ws_a, 'online')
            mapping = {item['userId']: item['online'] for item in online['data']}
            rec('W11', 'WS 批量查询在线状态', 'queryOnline([5,7,6])',
                '在线的两人为 True，未连接的 6 为 False',
                mapping.get(STU_ID) is True and mapping.get(DEMO_ID) is True and mapping.get(6) is False,
                f'查询结果={mapping}')

            rest_online = rest('GET', '/message/online?peerId=7', token=token_stu)
            rec('W12', 'REST 查询在线状态（含在线人数）',
                'GET /api/message/online?peerId=7',
                'online=True 且 onlineCount ≥2',
                rest_online['data']['online'] is True and rest_online['data']['onlineCount'] >= 2,
                f"online={rest_online['data']['online']}，onlineCount={rest_online['data']['onlineCount']}")

            # ------------------------------------------------------------ 六、异常场景
            print('\n【6】异常与校验（复用 REST 的业务规则）')

            await ws_a.send(json.dumps({'type': 'chat', 'toUserId': STU_ID, 'content': '给自己'}))
            e1 = await wait_for(ws_a, 'error')
            rec('W13', '不能给自己发消息', 'toUserId=自己', 'error 帧 code=5001',
                e1['code'] == 5001, f"{e1['code']} {e1['message']}")

            await ws_a.send(json.dumps({'type': 'chat', 'toUserId': 999999, 'content': 'x'}))
            e2 = await wait_for(ws_a, 'error')
            rec('W14', '接收人不存在', 'toUserId=999999', 'error 帧 code=5002',
                e2['code'] == 5002, f"{e2['code']} {e2['message']}")

            await ws_a.send(json.dumps({'type': 'chat', 'toUserId': DEMO_ID, 'content': '   '}))
            e3 = await wait_for(ws_a, 'error')
            rec('W15', '空内容被拒绝', 'content 全空格', 'error 帧 code=400',
                e3['code'] == 400, f"{e3['code']} {e3['message']}")

            await ws_a.send(json.dumps({'type': 'chat', 'toUserId': DEMO_ID, 'content': 'x' * 501}))
            e4 = await wait_for(ws_a, 'error')
            rec('W16', '超长内容被拒绝（>500 字）', 'content 长度 501', 'error 帧 code=400',
                e4['code'] == 400, f"{e4['code']} {e4['message']}")

            await ws_a.send(json.dumps({'type': 'unknown-type'}))
            e5 = await wait_for(ws_a, 'error')
            rec('W17', '未知消息类型', "type='unknown-type'", 'error 帧 code=400',
                e5['code'] == 400, f"{e5['code']} {e5['message']}")

            # 畸形 JSON 也不应断开连接
            await ws_a.send('这不是 JSON')
            e6 = await wait_for(ws_a, 'error')
            await ws_a.send(json.dumps({'type': 'ping'}))
            pong_after = await wait_for(ws_a, 'pong')
            rec('W18', '畸形报文返回 error 且连接保持存活', '发送非法 JSON 后再 ping',
                '先收到 error，随后仍能收到 pong',
                e6['code'] == 400 and pong_after['type'] == 'pong',
                f"error code={e6['code']}，之后 ping→{pong_after['type']}")

            # ------------------------------------------------------------ 七、多端在线
            print('\n【7】多端在线（同一账号多标签页）')

            async with websockets.connect(f'{WS}?token={token_stu}') as ws_a2:
                await wait_for(ws_a2, 'welcome')
                await ws_b.send(json.dumps({'type': 'chat', 'toUserId': STU_ID, 'content': '多端测试'}))
                got_1 = await wait_for(ws_a, 'chat')
                got_2 = await wait_for(ws_a2, 'chat')
                rec('W19', '同一账号的两个连接都能收到消息',
                    'B 发消息给 A（A 开了两个连接）',
                    '两个连接都收到同一条消息',
                    got_1['data']['id'] == got_2['data']['id'] and got_1['data']['content'] == '多端测试',
                    f"连接1 id={got_1['data']['id']}，连接2 id={got_2['data']['id']}")

            # 关闭第二个连接后，A 仍在第一个连接上在线
            await asyncio.sleep(1.0)
            still_online = rest('GET', f'/message/online?peerId={STU_ID}', token=token_demo)['data']['online']
            rec('W20', '关闭一个标签页后仍在线（全部关闭才算离线）',
                'A 关闭第二个连接后查询状态', 'online 仍为 True',
                still_online is True, f'online={still_online}')

        # B 已断开
        await asyncio.sleep(1.0)
        offline_state = rest('GET', '/message/online?peerId=7', token=token_stu)['data']
        rec('W21', '连接全部断开后转为离线', 'B 断开后查询 B 的在线状态',
            'online=False 且 Redis ZSet 中已移除',
            offline_state['online'] is False and redis('zscore', 'campus:online:zset', str(DEMO_ID)) in ('', None),
            f"online={offline_state['online']}，ZSet 分数={redis('zscore', 'campus:online:zset', str(DEMO_ID)) or '已移除'}")

        # ---------------------------------------------------------------- 八、离线消息
        print('\n【8】离线消息不丢失')
        offline_content = f"v0.14 离线消息 {time.strftime('%H%M%S')}"
        sent = rest('POST', '/message/send', {'toUserId': DEMO_ID, 'content': offline_content}, token=token_stu)
        db_saved = one(f"SELECT COUNT(*) FROM message WHERE deleted=0 AND to_user_id={DEMO_ID} "
                       f"AND content='{offline_content}'")
        rec('W22', '对方离线时消息仍入库（REST 路径）', 'B 离线，A 通过 REST 发消息',
            '接口 200 且数据库有记录（B 下次登录可拉取）',
            sent.get('code') == 200 and db_saved == '1',
            f"code={sent.get('code')}，数据库记录数={db_saved}")

        # ---------------------------------------------------------------- 九、限流
        print('\n【9】WebSocket 通道限流（不能绕过 REST 的 @RateLimit）')
        redis('del', f'campus:ratelimit:ws-message:user{STU_ID}')
        codes = []
        for i in range(33):
            await ws_a.send(json.dumps({'type': 'chat', 'toUserId': DEMO_ID, 'content': f'限流测试{i}'}))
            try:
                frame = await wait_for(ws_a, 'error', timeout=1.2)
                codes.append(frame['code'])
            except TimeoutError:
                codes.append(200)
        limited = codes.count(429)
        rec('W23', 'WebSocket 连续发送被限流（30 条/分钟）',
            'A 连续通过 WS 发送 33 条消息',
            '超出部分返回 error 429',
            29 <= codes.count(200) <= 31 and limited >= 2,
            f"成功 {codes.count(200)} 条、限流 {limited} 条（阈值 30 条/分钟）")
        redis('del', f'campus:ratelimit:ws-message:user{STU_ID}')

    # ---------------------------------------------------------------- 十、管理联动
    print('\n【10】禁用账号立即断开实时连接')

    tmp_user = f'ws{int(time.time()) % 100000}'
    rest('POST', '/user/register', {'username': tmp_user, 'password': 'abc12345', 'nickname': 'WS测试账号'})
    tmp_id = int(one(f"SELECT id FROM `user` WHERE username='{tmp_user}'"))
    tmp_token = login(tmp_user, 'abc12345')
    ws_tmp = await websockets.connect(f'{WS}?token={tmp_token}')
    await wait_for(ws_tmp, 'welcome')
    rest('PUT', '/admin/user/status', {'userId': tmp_id, 'status': 0}, token=token_admin)
    closed = False
    try:
        await asyncio.wait_for(ws_tmp.recv(), timeout=4)
    except Exception:  # noqa: BLE001  连接被关闭会抛异常/返回关闭帧
        closed = True
    rec('W24', '管理员禁用账号后其实时连接被断开', f'禁用用户 {tmp_user}（id={tmp_id}）',
        '该用户的 WebSocket 连接被服务端关闭',
        closed and redis('zscore', 'campus:online:zset', str(tmp_id)) in ('', None),
        f"连接已断开={closed}，Redis 在线记录={'已移除' if redis('zscore', 'campus:online:zset', str(tmp_id)) in ('', None) else '仍存在'}")

    # 清理
    sql(f"DELETE FROM message WHERE content LIKE 'v0.14%' OR content LIKE '多端测试' OR content LIKE '限流测试%'")
    sql(f"DELETE FROM favorite WHERE user_id={tmp_id}")
    sql(f"DELETE FROM user_behavior WHERE user_id={tmp_id}")
    sql(f"DELETE FROM message WHERE from_user_id={tmp_id} OR to_user_id={tmp_id}")
    sql(f"DELETE FROM `user` WHERE id={tmp_id}")
    rest('PUT', '/admin/user/status', {'userId': tmp_id, 'status': 1}, token=token_admin)

    total = len(RESULTS)
    passed = sum(1 for x in RESULTS if x['ok'])
    print()
    print('=' * 106)
    print(f'v0.14 实时私信测试汇总：{passed}/{total} 通过')
    for x in RESULTS:
        if not x['ok']:
            print(f"  [FAIL] {x['id']} {x['scenario']} —— {x['actual']}")
    print('=' * 106)

    (TMP / 'v014_result.json').write_text(
        json.dumps(dict(results=RESULTS), ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'结果已保存：{TMP / "v014_result.json"}')


asyncio.run(main())
