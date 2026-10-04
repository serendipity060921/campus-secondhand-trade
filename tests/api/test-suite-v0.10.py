# -*- coding: utf-8 -*-
"""校园二手交易平台 v0.10 系统测试（完整版）
PART 1  冒烟测试：注册 → 登录 → 发布商品 → 收藏 → 私聊 → 下单 → 完成（完整闭环）
PART 2  功能测试用例：注册登录 / 商品 / 收藏 / 消息 / 订单 / 辅助，含正常、异常、越权
PART 3  越权专项：受保护接口全覆盖 401 检查、管理员接口 403 检查、伪造身份/边界状态探测
"""
import base64
import hashlib
import hmac
import json
import mimetypes
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = 'http://127.0.0.1:8080/api'
TMP = r'C:\Users\26859\AppData\Local\Temp\dsh-sqlval'
MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
JWT_SECRET = 'campus-secondhand-trade-jwt-secret-key-2026-graduation-project'


def sql(q):
    """执行 SQL（用于构造边界状态与校验数据），返回 TSV 文本"""
    try:
        r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                            '--default-character-set=utf8mb4', '-N', '-B', '-e', f'USE campus_trade; {q}'],
                           capture_output=True, text=True, encoding='utf-8', timeout=30)
        return (r.stdout or '').strip()
    except Exception as e:  # noqa: BLE001
        return f'SQL_ERROR: {e}'


def call(method, path, body=None, token=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
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


def upload(path, file_path, field='file', token=None, extra_fields=None):
    boundary = '----dsh' + uuid.uuid4().hex
    name = os.path.basename(file_path)
    ctype = mimetypes.guess_type(name)[0] or 'application/octet-stream'
    with open(file_path, 'rb') as f:
        content = f.read()
    parts = []
    for k, v in (extra_fields or {}).items():
        parts += [f'--{boundary}\r\n'.encode(),
                  f'Content-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()]
    parts += [f'--{boundary}\r\n'.encode(),
              f'Content-Disposition: form-data; name="{field}"; filename="{name}"\r\n'.encode(),
              f'Content-Type: {ctype}\r\n\r\n'.encode(), content,
              f'\r\n--{boundary}--\r\n'.encode()]
    req = urllib.request.Request(BASE + path, data=b''.join(parts), method='POST')
    req.add_header('Content-Type', f'multipart/form-data; boundary={boundary}')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8', 'replace'))


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()


def expired_token():
    now = int(time.time())
    segs = [b64(json.dumps({'alg': 'HS256'}, separators=(',', ':')).encode()),
            b64(json.dumps({'sub': '5', 'username': 'stu_test01', 'role': 0, 'iss': 'campus-trade',
                            'iat': now - 10800, 'exp': now - 3600}, separators=(',', ':')).encode())]
    sign = hmac.new(JWT_SECRET.encode(), '.'.join(segs).encode(), hashlib.sha256).digest()
    return '.'.join(segs) + '.' + b64(sign)


def login(u, p):
    r = call('POST', '/user/login', {'username': u, 'password': p})
    return r['data']['token'] if r.get('code') == 200 else None


def brief(res):
    if not isinstance(res, dict):
        return str(res)[:60]
    return f"code={res.get('code')} {str(res.get('message', ''))[:38]}"


admin = login('admin', '123456')
stu = login('stu_test01', 'abc12345')
if not admin or not stu:
    print('无法登录测试账号，请确认后端已启动')
    sys.exit(1)

# ============================================================ PART 1 冒烟测试
print('=' * 100)
print('PART 1  冒烟测试：注册 → 登录 → 发布商品 → 收藏 → 私聊 → 下单 → 完成')
print('=' * 100)
smoke = []


def step(no, title, ok, detail):
    smoke.append(dict(no=no, title=title, result='PASS' if ok else 'FAIL', detail=detail))
    print(f'  [{"OK  " if ok else "FAIL"}] {no:4s} {title:36s} {detail}')


tag = uuid.uuid4().hex[:6]
qa_user = 'qa' + tag
r = call('POST', '/user/register', {'username': qa_user, 'password': 'qa123456', 'nickname': f'冒烟用户{tag[-3:]}'})
step('S1', f'注册新用户 {qa_user}', r.get('code') == 200, brief(r))
qa = login(qa_user, 'qa123456')
step('S2', '新账号登录获取 Token', bool(qa), f'Token 长度={len(qa or "")}')

# 卖家先发布一件商品（保证冒烟流程有可交易的、确实在售的商品）
r = call('POST', '/product/publish', {'title': f'【冒烟】管理员商品 {tag}', 'description': 'v0.10 冒烟测试用',
                                      'categoryId': 8, 'price': 88.00, 'conditionLevel': 2, 'campus': '主校区',
                                      'tradePlace': '主校区南门'}, token=admin)
smoke_product = r['data']['id']
step('S3', '卖家发布待售商品', r.get('code') == 200, f'商品ID={smoke_product}')

r = call('POST', '/product/publish', {'title': f'【冒烟】{qa_user} 的闲置', 'description': '冒烟测试',
                                      'categoryId': 8, 'price': 9.90, 'conditionLevel': 1, 'campus': '东校区',
                                      'imageUrls': ['/demo-images/default.png']}, token=qa)
qa_product = r['data']['id']
step('S4', '新用户发布商品（含图片）', r.get('code') == 200, f'商品ID={qa_product}')
r = call('GET', f'/product/{qa_product}')
step('S5', '商品详情可访问且卖家正确', r.get('code') == 200 and r['data']['sellerNickname'].startswith('冒烟'),
     f"卖家={r['data']['sellerNickname']}")

r = call('POST', '/favorite/operate', {'productId': smoke_product}, token=qa)
step('S6', '收藏卖家商品', r.get('code') == 200, brief(r))
r = call('GET', '/favorite/list?page=1&size=10', token=qa)
step('S7', '收藏列表可见', r.get('code') == 200 and r['data']['total'] >= 1, f"收藏数={r['data']['total']}")

r = call('POST', '/message/send', {'toUserId': 1, 'content': '你好，这件商品还在吗？（冒烟）', 'productId': smoke_product}, token=qa)
step('S8', '私聊卖家', r.get('code') == 200, brief(r))
r = call('GET', '/message/conversationList', token=qa)
step('S9', '会话列表出现会话', r.get('code') == 200 and len(r['data']) >= 1, f"会话数={len(r['data'])}")
r = call('PUT', '/message/read', {'peerId': 1}, token=qa)
step('S10', '标记会话已读', r.get('code') == 200, brief(r))

r = call('POST', '/order/create', {'productId': smoke_product, 'tradePlace': '主校区南门', 'buyerRemark': '冒烟下单'}, token=qa)
smoke_order = r.get('data', {}).get('id') if r.get('code') == 200 else None
step('S11', '下单购买', r.get('code') == 200, f'订单ID={smoke_order}')
r = call('GET', f'/order/{smoke_order}', token=qa)
step('S12', '订单详情（买家视角）', r.get('code') == 200,
     f"状态={r['data']['statusLabel']} 商品={r['data']['productStatusLabel']}")
r = call('PUT', '/order/status', {'orderId': smoke_order, 'status': 3}, token=admin)
step('S13', '卖家确认完成', r.get('code') == 200, brief(r))
r = call('GET', '/order/buyList?page=1&size=50', token=qa)
step('S14', '订单出现在「我买到的」', r.get('code') == 200 and any(x['id'] == smoke_order for x in r['data']['records']),
     f"订单总数={r['data']['total']}")

smoke_pass = sum(1 for s in smoke if s['result'] == 'PASS')
print(f'\n冒烟测试：{smoke_pass}/{len(smoke)} 通过\n')

# ============================================================ PART 2 功能用例
CASES = []


def check(cid, module, scenario, inputs, expected, res, predicate=None, note=None):
    code = res.get('code') if isinstance(res, dict) else -1
    ok = (code == expected) if predicate is None else bool(predicate(res))
    CASES.append(dict(id=cid, module=module, scenario=scenario, inputs=inputs,
                      expected=f'code={expected}', actual=note or brief(res),
                      ok=ok, code=code))
    return res


# ---- 测试数据准备 ----
qa2_user = 'qa' + uuid.uuid4().hex[:6]
call('POST', '/user/register', {'username': qa2_user, 'password': 'qa123456', 'nickname': 'QA测试用户'})
qa2 = login(qa2_user, 'qa123456')
qa2_id = call('GET', '/user/info', token=qa2)['data']['id']

t = uuid.uuid4().hex[:4]
r = call('POST', '/product/publish', {'title': f'【测试】订单流程商品 {t}', 'description': '自动化测试商品',
                                      'categoryId': 8, 'price': 66.00, 'conditionLevel': 2, 'campus': '主校区'},
         token=admin)
test_product = r['data']['id']
my_product = call('POST', '/product/publish', {'title': f'【测试】{qa2_user} 的自有商品', 'categoryId': 8,
                                               'price': 5.00, 'conditionLevel': 1}, token=qa2)['data']['id']

# ---- 用户 / 认证 ----
u3 = 'qa' + uuid.uuid4().hex[:6]
check('TC-U01', '用户/认证', '正常注册新用户', f'username={u3}', 200,
      call('POST', '/user/register', {'username': u3, 'password': 'qa123456', 'nickname': '测试用户'}))
check('TC-U02', '用户/认证', '重复用户名注册', f'username={u3}（再次提交）', 2001,
      call('POST', '/user/register', {'username': u3, 'password': 'qa123456', 'nickname': '测试用户'}))
check('TC-U03', '用户/认证', '注册参数全为空', 'username/password/nickname 均为空', 400,
      call('POST', '/user/register', {'username': '', 'password': '', 'nickname': ''}))
check('TC-U04', '用户/认证', '密码强度不足', 'password=abcdef（无数字）', 400,
      call('POST', '/user/register', {'username': 'qa_x' + uuid.uuid4().hex[:4], 'password': 'abcdef', 'nickname': '测试用户'}))
res = call('POST', '/user/login', {'username': u3, 'password': 'qa123456'})
check('TC-U05', '用户/认证', '正常登录返回 Token', f'username={u3}', 200, res,
      predicate=lambda x: x.get('code') == 200 and 'token' in x.get('data', {}))
u3_token = res['data']['token']
check('TC-U06', '用户/认证', '密码错误', 'password=wrong123', 2003,
      call('POST', '/user/login', {'username': u3, 'password': 'wrong123'}))
check('TC-U07', '用户/认证', '账号不存在', 'username=not_exist_999', 2002,
      call('POST', '/user/login', {'username': 'not_exist_999', 'password': 'qa123456'}))
check('TC-U08', '用户/认证[越权]', '未登录访问个人中心', 'GET /api/user/info 无 Token', 401,
      call('GET', '/user/info'))
check('TC-U09', '用户/认证', '有效 Token 访问个人中心', 'GET /api/user/info + Token', 200,
      call('GET', '/user/info', token=u3_token))
check('TC-U10', '用户/认证[越权]', '伪造 Token', 'token=abc.def.ghi', 401,
      call('GET', '/user/info', token='abc.def.ghi'))
check('TC-U11', '用户/认证[越权]', '使用过期 Token', 'exp 为 1 小时前的 JWT', 401,
      call('GET', '/user/info', token=expired_token()))
check('TC-U12', '用户/认证', '查询完整资料', 'GET /api/user/profile', 200,
      call('GET', '/user/profile', token=u3_token))
check('TC-U13', '用户/认证', '修改个人资料', 'nickname/campus/gender/email', 200,
      call('PUT', '/user/update', {'nickname': 'QA测试用户', 'campus': '东校区', 'gender': 2,
                                   'email': 'qa@campus.edu'}, token=u3_token))
check('TC-U14', '用户/认证', '手机号被他人占用', 'phone=13800000099（stu_demo 已用）', 2006,
      call('PUT', '/user/update', {'phone': '13800000099'}, token=u3_token))
check('TC-U15', '用户/认证', '手机号格式错误', 'phone=123', 400,
      call('PUT', '/user/update', {'phone': '123'}, token=u3_token))
check('TC-U16', '用户/认证', '上传头像（png）', 'multipart file=up1.png', 200,
      upload('/user/avatar', os.path.join(TMP, 'up1.png'), token=u3_token))
check('TC-U17', '用户/认证', '上传非图片文件', 'multipart file=bad.txt', 3006,
      upload('/user/avatar', os.path.join(TMP, 'bad.txt'), token=u3_token))
check('TC-U18', '用户/认证', '上传超大图片（6MB）', 'multipart file=big.png', 3007,
      upload('/user/avatar', os.path.join(TMP, 'big.png'), token=u3_token))
check('TC-U19', '用户/认证[越权]', '未登录上传头像', 'POST /api/user/avatar 无 Token', 401,
      upload('/user/avatar', os.path.join(TMP, 'up1.png')))

# ---- 商品 ----
check('TC-P01', '商品[越权]', '未登录发布商品', 'POST /api/product/publish 无 Token', 401,
      call('POST', '/product/publish', {'title': '未登录商品', 'categoryId': 8, 'price': 1, 'conditionLevel': 1}))
check('TC-P02', '商品', '正常发布商品', '完整参数', 200,
      call('POST', '/product/publish', {'title': f'【测试】{qa2_user} 发布 {t}', 'description': '测试',
                                        'categoryId': 8, 'price': 12.50, 'originalPrice': 30,
                                        'conditionLevel': 2, 'campus': '东校区', 'tradePlace': '图书馆'},
           token=qa2))
check('TC-P03', '商品', '发布参数非法', 'title 为空 / price=-5 / conditionLevel=9', 400,
      call('POST', '/product/publish', {'title': '', 'categoryId': None, 'price': -5, 'conditionLevel': 9}, token=qa2))
check('TC-P04', '商品', '分类不存在', 'categoryId=999999', 3004,
      call('POST', '/product/publish', {'title': '分类不存在商品', 'categoryId': 999999, 'price': 10,
                                        'conditionLevel': 1}, token=qa2))
res = call('GET', '/product/list?page=1&size=30')
check('TC-P05', '商品', '商品列表只返回上架商品', 'GET /api/product/list', 200, res,
      predicate=lambda x: x.get('code') == 200 and all(i['status'] == 1 for i in x['data']['records']))
check('TC-P06', '商品', '查看商品详情', f'GET /api/product/{test_product}', 200, call('GET', f'/product/{test_product}'))
check('TC-P07', '商品', '查看不存在的商品', 'GET /api/product/999999', 3001, call('GET', '/product/999999'))
check('TC-P08', '商品', '图片多张上传', 'multipart files=up1.png', 200,
      upload('/product/upload', os.path.join(TMP, 'up1.png'), field='files', token=qa2))
check('TC-P09', '商品', '上传非法格式', 'multipart file=bad.txt', 3006,
      upload('/product/upload', os.path.join(TMP, 'bad.txt'), token=qa2))
check('TC-P10', '商品', '查询我的商品', 'GET /api/product/mine', 200,
      call('GET', '/product/mine?page=1&size=10', token=qa2))
check('TC-P11', '商品', '下架自己的商品', f'productId={test_product}, status=3', 200,
      call('PUT', '/product/status', {'productId': test_product, 'status': 3}, token=admin))
check('TC-P12', '商品[越权]', '下架他人发布的商品', f'qa2 下架 admin 的商品 {test_product}', 3002,
      call('PUT', '/product/status', {'productId': test_product, 'status': 3}, token=qa2))
call('PUT', '/product/status', {'productId': test_product, 'status': 1}, token=admin)
check('TC-P13', '商品', '上下架状态值非法', 'status=2', 400,
      call('PUT', '/product/status', {'productId': test_product, 'status': 2}, token=admin))
check('TC-P14', '商品/搜索', '按名称模糊搜索', 'keyword=教材', 200,
      call('GET', '/product/search?keyword=' + urllib.parse.quote('教材')),
      predicate=lambda x: x.get('code') == 200 and x['data']['total'] >= 1)
check('TC-P15', '商品/搜索', '按分类筛选（含子分类）', 'categoryId=1', 200, call('GET', '/product/search?categoryId=1'),
      predicate=lambda x: x.get('code') == 200 and x['data']['total'] >= 1)
check('TC-P16', '商品/搜索', '搜索分页参数非法', 'size=999', 400, call('GET', '/product/search?size=999'))
res = call('GET', '/products?page=1&size=50')
check('TC-P17', '商品[越权]', '脚手架演示接口不泄漏非上架商品（BUG-01 回归）', 'GET /api/products', 200, res,
      predicate=lambda x: x.get('code') == 200 and all(i['status'] == 1 for i in x['data']['records']))

# ---- BUG-02 回归：待审核商品可见性 ----
pending = call('POST', '/product/publish', {'title': f'【测试】待审核商品 {t}', 'categoryId': 8, 'price': 20,
                                            'conditionLevel': 1}, token=qa2)['data']['id']
sql(f"UPDATE product SET status = 0 WHERE id = {pending}")
check('TC-P18', '商品[越权]', '匿名查看待审核商品详情（BUG-02 回归）', f'GET /api/product/{pending} 无 Token', 3001,
      call('GET', f'/product/{pending}'))
check('TC-P19', '商品', '卖家本人可查看自己的待审核商品', f'GET /api/product/{pending} + 卖家 Token', 200,
      call('GET', f'/product/{pending}', token=qa2))

# ---- 收藏 ----
check('TC-F01', '收藏[越权]', '未登录收藏', 'POST /api/favorite/operate 无 Token', 401,
      call('POST', '/favorite/operate', {'productId': test_product}))
check('TC-F02', '收藏', '正常收藏他人商品', f'productId={test_product}', 200,
      call('POST', '/favorite/operate', {'productId': test_product}, token=qa2))
check('TC-F03', '收藏', '重复收藏', f'productId={test_product} 第二次', 4001,
      call('POST', '/favorite/operate', {'productId': test_product}, token=qa2))
check('TC-F04', '收藏', '收藏自己发布的商品', f'productId={my_product}', 4002,
      call('POST', '/favorite/operate', {'productId': my_product}, token=qa2))
check('TC-F05', '收藏', '收藏不存在的商品', 'productId=999999', 3001,
      call('POST', '/favorite/operate', {'productId': 999999}, token=qa2))
check('TC-F06', '收藏', '查询是否已收藏', f'productId={test_product}', 200,
      call('GET', f'/favorite/hasFavorite?productId={test_product}', token=qa2),
      predicate=lambda x: x.get('code') == 200 and x['data']['favorited'] is True)
check('TC-F07', '收藏', '取消收藏', f'productId={test_product}, type=2', 200,
      call('POST', '/favorite/operate', {'productId': test_product, 'type': 2}, token=qa2))
check('TC-F08', '收藏', '取消未收藏的商品', 'type=2 第二次', 4003,
      call('POST', '/favorite/operate', {'productId': test_product, 'type': 2}, token=qa2))
call('POST', '/favorite/operate', {'productId': test_product}, token=qa2)
check('TC-F09', '收藏', '我的收藏只含上架商品', 'GET /api/favorite/list', 200,
      call('GET', '/favorite/list?page=1&size=20', token=qa2),
      predicate=lambda x: x.get('code') == 200 and all(i['productStatus'] == 1 for i in x['data']['records']))
s_ids = {i['productId'] for i in call('GET', '/favorite/list?page=1&size=50', token=stu)['data']['records']}
q_ids = {i['productId'] for i in call('GET', '/favorite/list?page=1&size=50', token=qa2)['data']['records']}
check('TC-F10', '收藏[越权]', '不同用户收藏列表互不可见', '比对 stu_test01 与 qa2 的收藏', 200,
      {'code': 200, 'message': f'交集={sorted(s_ids & q_ids)}'}, predicate=lambda x: len(s_ids & q_ids) == 0)
check('TC-F11', '收藏', '收藏列表分页非法', 'page=0', 400, call('GET', '/favorite/list?page=0&size=10', token=qa2))

# ---- 消息 ----
check('TC-M01', '消息[越权]', '未登录发送私信', 'POST /api/message/send 无 Token', 401,
      call('POST', '/message/send', {'toUserId': 1, 'content': 'hi'}))
check('TC-M02', '消息', '给自己发消息', f'toUserId={qa2_id}（自己）', 5001,
      call('POST', '/message/send', {'toUserId': qa2_id, 'content': '给自己'}, token=qa2))
check('TC-M03', '消息', '接收人不存在', 'toUserId=999999', 5002,
      call('POST', '/message/send', {'toUserId': 999999, 'content': 'hi'}, token=qa2))
check('TC-M04', '消息', '消息内容为空', 'content=""', 400,
      call('POST', '/message/send', {'toUserId': 1, 'content': ''}, token=qa2))
check('TC-M05', '消息', '正常发送私信', 'toUserId=1 + productId', 200,
      call('POST', '/message/send', {'toUserId': 1, 'content': 'v0.10 测试消息', 'productId': test_product}, token=qa2))
check('TC-M06', '消息', '会话列表', 'GET /api/message/conversationList', 200,
      call('GET', '/message/conversationList', token=qa2),
      predicate=lambda x: x.get('code') == 200 and len(x['data']) >= 1)
check('TC-M07', '消息', '聊天记录分页', 'peerId=1, page=1, size=10', 200,
      call('GET', '/message/history?peerId=1&page=1&size=10', token=qa2),
      predicate=lambda x: x.get('code') == 200 and x['data']['total'] >= 1)
check('TC-M08', '消息', '标记会话已读', 'peerId=1', 200, call('PUT', '/message/read', {'peerId': 1}, token=qa2))
to_admin = [m['id'] for m in call('GET', '/message/history?peerId=1&page=1&size=50', token=qa2)['data']['records']
            if m['toUserId'] == 1]
check('TC-M09', '消息[越权]', '标记"他人收到的消息"为已读', f'messageIds={to_admin[:3]}', 200,
      call('PUT', '/message/read', {'messageIds': to_admin[:3]}, token=qa2),
      predicate=lambda x: x.get('code') == 200 and x.get('data') == 0)
check('TC-M10', '消息', '查询与自己无关的会话', 'peerId=999999', 200,
      call('GET', '/message/history?peerId=999999&page=1&size=10', token=qa2),
      predicate=lambda x: x.get('code') == 200 and x['data']['total'] == 0)
check('TC-M11', '消息', '聊天记录分页非法', 'size=999', 400,
      call('GET', '/message/history?peerId=1&page=1&size=999', token=qa2))
check('TC-M12', '消息', '缺少必填参数 peerId', 'GET /api/message/history', 400,
      call('GET', '/message/history?page=1&size=10', token=qa2))

# ---- 订单 ----
check('TC-O01', '订单[越权]', '未登录下单', 'POST /api/order/create 无 Token', 401,
      call('POST', '/order/create', {'productId': test_product}))
check('TC-O02', '订单', '下单商品不存在', 'productId=999999', 3001,
      call('POST', '/order/create', {'productId': 999999}, token=qa2))
check('TC-O03', '订单', '购买自己发布的商品', f'productId={my_product}', 6002,
      call('POST', '/order/create', {'productId': my_product}, token=qa2))
res = call('POST', '/order/create', {'productId': test_product, 'tradePlace': '主校区南门',
                                     'buyerRemark': 'v0.10 测试订单'}, token=qa2)
test_order = res.get('data', {}).get('id')
check('TC-O05', '订单', '正常下单', f'productId={test_product}', 200, res)
check('TC-O06', '订单', '重复下单同一商品', '第二人下单已锁定商品', 6001,
      call('POST', '/order/create', {'productId': test_product}, token=stu))
check('TC-O07', '订单', '我买到的订单列表', 'GET /api/order/buyList', 200,
      call('GET', '/order/buyList?page=1&size=10', token=qa2),
      predicate=lambda x: x.get('code') == 200 and x['data']['total'] >= 1)
check('TC-O08', '订单', '我卖出的订单列表', 'GET /api/order/sellList（admin）', 200,
      call('GET', '/order/sellList?page=1&size=10', token=admin),
      predicate=lambda x: x.get('code') == 200 and x['data']['total'] >= 1)
check('TC-O09', '订单', '订单详情（买家）', f'GET /api/order/{test_order}', 200,
      call('GET', f'/order/{test_order}', token=qa2))
check('TC-O10', '订单[越权]', '第三方查看订单详情', f'stu 查看 qa2 的订单 {test_order}', 6004,
      call('GET', f'/order/{test_order}', token=stu))
check('TC-O11', '订单[越权]', '第三方修改订单状态', 'stu 完成 qa2 的订单', 6004,
      call('PUT', '/order/status', {'orderId': test_order, 'status': 3}, token=stu))
check('TC-O12', '订单', '状态值非法', 'status=0', 400,
      call('PUT', '/order/status', {'orderId': test_order, 'status': 0}, token=qa2))
check('TC-O13', '订单', '订单不存在', 'orderId=999999', 6003,
      call('PUT', '/order/status', {'orderId': 999999, 'status': 3}, token=qa2))
check('TC-O14', '订单', '买家确认完成', f'orderId={test_order}, status=3', 200,
      call('PUT', '/order/status', {'orderId': test_order, 'status': 3}, token=qa2))
check('TC-O15', '订单', '已完成订单再次操作', 'status=4', 6005,
      call('PUT', '/order/status', {'orderId': test_order, 'status': 4}, token=qa2))
# 取消流程：用一件全新商品走"下单 → 取消 → 商品回到在售"
p2 = call('POST', '/product/publish', {'title': f'【测试】取消流程商品 {t}', 'categoryId': 8, 'price': 33.00,
                                       'conditionLevel': 1}, token=admin)['data']['id']
o2 = call('POST', '/order/create', {'productId': p2}, token=qa2)['data']['id']
r2 = call('PUT', '/order/status', {'orderId': o2, 'status': 4, 'cancelReason': 'v0.10 取消流程测试'}, token=qa2)
after = call('GET', f'/product/{p2}')
check('TC-O16', '订单', '取消订单后商品回到在售', f'orderId={o2} 取消后查询商品状态', 200, r2,
      predicate=lambda x: x.get('code') == 200 and after['data']['status'] == 1,
      note=f"code={r2.get('code')} 商品状态={after['data']['status']}（1=在售）")
check('TC-O17', '订单', '订单列表分页非法', 'page=0', 400,
      call('GET', '/order/sellList?page=0&size=10', token=admin))

# ---- 辅助 / 分类管理 ----
check('TC-A01', '辅助/分类', '分类列表查询', 'GET /api/category/list', 200, call('GET', '/category/list'))
check('TC-A02', '辅助/分类[越权]', '未登录新增分类', 'POST /api/category/add 无 Token', 401,
      call('POST', '/category/add', {'name': '未登录分类'}))
check('TC-A03', '辅助/分类[越权]', '普通学生新增分类', 'POST /api/category/add + 学生 Token', 403,
      call('POST', '/category/add', {'name': '学生分类'}, token=qa2))
cat_name = f'QA分类{t}'
res = call('POST', '/category/add', {'name': cat_name, 'parentId': 0, 'sortOrder': 60}, token=admin)
new_cat = res.get('data', {}).get('id')
check('TC-A04', '辅助/分类', '管理员新增分类', f'name={cat_name}', 200, res)
check('TC-A05', '辅助/分类', '同级分类重名', f'name={cat_name} 重复', 7001,
      call('POST', '/category/add', {'name': cat_name, 'parentId': 0}, token=admin))
check('TC-A06', '辅助/分类', '上级分类不存在', 'parentId=999999', 7003,
      call('POST', '/category/add', {'name': '孤儿分类', 'parentId': 999999}, token=admin))
check('TC-A07', '辅助/分类', '修改分类', f'id={new_cat} 改名+排序', 200,
      call('PUT', '/category/update', {'id': new_cat, 'name': cat_name + '改', 'sortOrder': 61}, token=admin))
check('TC-A08', '辅助/分类', '修改不存在的分类', 'id=999999', 7002,
      call('PUT', '/category/update', {'id': 999999, 'name': '不存在'}, token=admin))
check('TC-A09', '辅助/分类', '上级设为自己', f'id={new_cat}, parentId={new_cat}', 7004,
      call('PUT', '/category/update', {'id': new_cat, 'parentId': new_cat}, token=admin))

# ============================================================ PART 3 越权专项
print('\n' + '=' * 100)
print('PART 3  越权专项检查')
print('=' * 100)
SEC = []

# 3.1 所有需登录接口：无 Token → 401
PROTECTED = [
    ('GET', '/user/info'), ('GET', '/user/profile'), ('PUT', '/user/update', {'nickname': 'x'}),
    ('POST', '/product/publish', {'title': 'x', 'categoryId': 8, 'price': 1, 'conditionLevel': 1}),
    ('GET', '/product/mine'), ('PUT', '/product/status', {'productId': 1, 'status': 3}),
    ('POST', '/favorite/operate', {'productId': 1}), ('GET', '/favorite/list'),
    ('GET', '/favorite/hasFavorite?productId=1'),
    ('POST', '/message/send', {'toUserId': 1, 'content': 'x'}), ('GET', '/message/conversationList'),
    ('GET', '/message/history?peerId=1'), ('PUT', '/message/read', {'peerId': 1}), ('GET', '/message/peer?peerId=1'),
    ('POST', '/order/create', {'productId': 1}), ('PUT', '/order/status', {'orderId': 1, 'status': 3}),
    ('GET', '/order/buyList'), ('GET', '/order/sellList'), ('GET', '/order/1'),
    ('POST', '/category/add', {'name': 'x'}), ('PUT', '/category/update', {'id': 1, 'name': 'xx'}),
]
sec_fail = 0
for item in PROTECTED:
    method, path = item[0], item[1]
    body = item[2] if len(item) > 2 else None
    r = call(method, path, body)
    ok = r.get('code') == 401
    if not ok:
        sec_fail += 1
    SEC.append(dict(id=f'TC-SEC-401-{len(SEC) + 1}', item=f'{method} {path}', expect='401 未登录',
                    actual=brief(r), ok=ok))
print(f'  3.1 受保护接口未登录检查：{len(PROTECTED)} 个接口，{len(PROTECTED) - sec_fail} 个正确返回 401')

# 3.2 管理员接口：学生 Token → 403
ADMIN_ONLY = [('POST', '/category/add', {'name': '学生越权分类'}), ('PUT', '/category/update', {'id': 1, 'name': 'x'})]
for item in ADMIN_ONLY:
    r = call(item[0], item[1], item[2], token=qa2)
    SEC.append(dict(id=f'TC-SEC-403-{len(SEC) + 1}', item=f'{item[0]} {item[1]}', expect='403 无权限',
                    actual=brief(r), ok=r.get('code') == 403))
print(f'  3.2 管理员接口越权检查：{len(ADMIN_ONLY)} 个接口，结果 '
      f'{sum(1 for s in SEC[-2:] if s["ok"])}/{len(ADMIN_ONLY)}')

# 3.3 伪造身份：请求体里塞 userId/id 尝试改他人资料
before = sql("SELECT nickname FROM `user` WHERE id = 1")
r = call('PUT', '/user/update', {'id': 1, 'userId': 1, 'nickname': 'HACKED'}, token=qa2)
after = sql("SELECT nickname FROM `user` WHERE id = 1")
SEC.append(dict(id=f'TC-SEC-ID-{len(SEC) + 1}', item='PUT /user/update 伪造 id=1 想改管理员资料',
                expect='只改自己，admin 昵称不变', actual=f'code={r.get("code")} admin: {before}->{after}',
                ok=(before == after)))
print(f'  3.3 伪造身份改他人资料：admin 昵称 {before} -> {after}（{"未被篡改" if before == after else "被篡改！"}）')

# 3.4 给他人商品补传图片（用 qa2 的 Token 向 admin 的商品 test_product 传图）
r = upload('/product/upload', os.path.join(TMP, 'up1.png'), token=qa2, extra_fields={'productId': test_product})
SEC.append(dict(id=f'TC-SEC-IMG-{len(SEC) + 1}', item=f'POST /product/upload 向他人商品 {test_product} 传图',
                expect='3002 无权限', actual=brief(r), ok=r.get('code') == 3002))
print(f'  3.4 向他人商品补传图片：{brief(r)}')

# 3.5 待审核商品可见性（BUG-02）
r_anon = call('GET', f'/product/{pending}')
r_owner = call('GET', f'/product/{pending}', token=qa2)
r_admin = call('GET', f'/product/{pending}', token=admin)
SEC.append(dict(id=f'TC-SEC-PENDING-{len(SEC) + 1}', item=f'待审核商品 {pending} 可见性',
                expect='匿名 3001 / 卖家 200 / 管理员 200',
                actual=f'匿名={r_anon.get("code")} 卖家={r_owner.get("code")} 管理员={r_admin.get("code")}',
                ok=(r_anon.get('code') == 3001 and r_owner.get('code') == 200 and r_admin.get('code') == 200)))
print(f'  3.5 待审核商品可见性：匿名 {r_anon.get("code")} / 卖家 {r_owner.get("code")} / 管理员 {r_admin.get("code")}')

# 3.6 伪造 Token 访问受保护接口批量检查
fake_ok = 0
for method, path in [('GET', '/user/info'), ('GET', '/favorite/list'), ('GET', '/order/buyList'),
                     ('GET', '/message/conversationList')]:
    r = call(method, path, token='fake.token.value')
    fake_ok += 1 if r.get('code') == 401 else 0
SEC.append(dict(id=f'TC-SEC-FAKE-{len(SEC) + 1}', item='伪造 Token 访问 4 个受保护接口', expect='全部 401',
                actual=f'{fake_ok}/4 返回 401', ok=fake_ok == 4))
print(f'  3.6 伪造 Token 批量检查：{fake_ok}/4 返回 401')

sec_pass = sum(1 for s in SEC if s['ok'])
print(f'\n越权专项：{sec_pass}/{len(SEC)} 通过')

# ============================================================ 汇总
part2_ok = sum(1 for c in CASES if c['ok'])
print('\n' + '=' * 100)
print(f"汇总：冒烟 {smoke_pass}/{len(smoke)}　功能用例 {part2_ok}/{len(CASES)}　越权专项 {sec_pass}/{len(SEC)}")
fails = [c for c in CASES if not c['ok']] + [s for s in SEC if not s['ok']] + \
        [dict(id=s['no'], scenario=s['title'], actual=s['detail']) for s in smoke if s['result'] == 'FAIL']
if fails:
    print('\n未通过项：')
    for f in fails:
        print('  -', f.get('id'), f.get('scenario'), '|', f.get('actual'))

with open(os.path.join(TMP, 'v010_result.json'), 'w', encoding='utf-8') as fp:
    json.dump(dict(smoke=smoke, cases=CASES, security=SEC,
                   meta=dict(qa_user=qa_user, qa2_user=qa2_user, test_product=test_product,
                             test_order=test_order, pending=pending, new_cat=new_cat,
                             cat_name=cat_name, p2=p2, my_product=my_product)),
              fp, ensure_ascii=False, indent=1)
print('\n结果已保存到 v010_result.json')
sys.stdout.flush()
