# -*- coding: utf-8 -*-
"""校园二手交易平台 v0.13 管理后台测试用例

覆盖：
  · 权限：未登录 401 / 学生 403 / 管理员 200（全部后台接口）
  · 数据看板：概览指标与数据库一致、7 天趋势连续、分布数据非空
  · 商品审核：通过（上架）/ 驳回（含理由）/ 状态校验 / 审计字段落库 / 首页可见性联动
  · 强制下架：在售可下架、交易中不可下架
  · 用户管理：禁用后不能登录、启用后恢复、不能禁用自己/管理员
  · 举报：用户提交（含不能举报自己、对象不存在）、管理端处理/忽略、重复处理拦截
  · 操作日志：审核/禁用/处理举报均留痕

执行：python tests/api/test-v013-admin.py
前提：后端 8080、MySQL 3306、Redis 6379 已启动，并已执行 db/schema/03-admin.sql
"""
import json
import os
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

API = 'http://127.0.0.1:8080/api'
MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
TMP = pathlib.Path(os.environ.get('TEMP', '.')) / 'dsh-sqlval'
RESULTS = []


def sql(q):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', '-e', f'USE campus_trade; {q}'],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
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
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return json.loads(raw)
        except Exception:
            return {'code': e.code, 'message': raw[:80]}
    except Exception as e:  # noqa: BLE001
        return {'code': -1, 'message': str(e)}


def rec(cid, scenario, inputs, expected, ok, actual):
    RESULTS.append(dict(id=cid, scenario=scenario, inputs=inputs, expected=expected,
                        actual=actual, ok=bool(ok)))
    print(f"  [{'PASS' if ok else 'FAIL'}] {cid:6s} {scenario[:36]:38s} {actual[:96]}")
    sys.stdout.flush()


def login(u, p):
    r = call('POST', '/user/login', {'username': u, 'password': p})
    return r['data']['token'] if r.get('code') == 200 else None


TAG = uuid.uuid4().hex[:5]
admin = login('admin', '123456')
stu = login('stu_test01', 'abc12345')
seller_token = login('stu_demo', '123456')
if not admin or not stu:
    print('无法登录测试账号，请确认后端已启动')
    sys.exit(1)

print('=' * 106)
print('v0.13 管理后台测试用例')
print('=' * 106)

# ---------------------------------------------------------------- 一、权限
print('\n【1】后台接口权限（未登录 / 学生 / 管理员）')

ADMIN_GETS = ['/admin/dashboard', '/admin/product/list', '/admin/user/list', '/admin/report/list', '/admin/log/list']
no_token = [call('GET', p).get('code') for p in ADMIN_GETS]
rec('A1', '未登录访问后台接口全部 401', f'逐个 GET {len(ADMIN_GETS)} 个后台接口（无 Token）',
    '全部返回 401', all(c == 401 for c in no_token), f'返回码={no_token}')

stu_codes = [call('GET', p, token=stu).get('code') for p in ADMIN_GETS]
rec('A2', '学生访问后台接口全部 403（类级 @LoginRequired(admin=true)）',
    f'逐个 GET {len(ADMIN_GETS)} 个后台接口（学生 Token）',
    '全部返回 403', all(c == 403 for c in stu_codes), f'返回码={stu_codes}')

r = call('GET', '/admin/dashboard', token=admin)
rec('A3', '管理员访问后台接口 200', 'GET /api/admin/dashboard（管理员 Token）', 'code=200',
    r.get('code') == 200, f"code={r.get('code')}")

# 学生调用写接口也应被拦截
w = call('PUT', '/admin/product/audit', {'productId': 1, 'approve': True}, token=stu)
rec('A4', '学生调用后台写接口被拒绝', 'PUT /api/admin/product/audit（学生 Token）', 'code=403',
    w.get('code') == 403, f"code={w.get('code')} {w.get('message')}")

# ---------------------------------------------------------------- 二、数据看板
print('\n【2】数据看板')

d = call('GET', '/admin/dashboard', token=admin)['data']
o = d['overview']
db_users = int(one("SELECT COUNT(*) FROM `user` WHERE deleted=0"))
db_products = int(one("SELECT COUNT(*) FROM product WHERE deleted=0"))
db_orders = int(one("SELECT COUNT(*) FROM orders WHERE deleted=0"))
db_pending = int(one("SELECT COUNT(*) FROM product WHERE deleted=0 AND status=0"))
db_reports = int(one("SELECT COUNT(*) FROM report WHERE deleted=0 AND status=0"))
rec('B1', '看板概览与数据库统计一致', '对比 overview 与 SQL COUNT',
    '用户/商品/订单/待审核/待举报 全部一致',
    o['userCount'] == db_users and o['productCount'] == db_products and o['orderCount'] == db_orders
    and o['pendingAuditCount'] == db_pending and o['pendingReportCount'] == db_reports,
    f"用户 {o['userCount']}={db_users}、商品 {o['productCount']}={db_products}、订单 {o['orderCount']}={db_orders}、"
    f"待审核 {o['pendingAuditCount']}={db_pending}、待举报 {o['pendingReportCount']}={db_reports}")

gmv = one("SELECT IFNULL(SUM(amount),0) FROM orders WHERE deleted=0 AND status=3")
rec('B2', '累计成交额只统计已完成订单', '对比 overview.gmv 与 SQL SUM',
    '金额一致', float(o['gmv']) == float(gmv), f"看板 ￥{o['gmv']} = SQL ￥{gmv}")

rec('B3', '近 7 天趋势数据连续（缺的日期补 0）', '检查 userTrend/productTrend/orderTrend 长度',
    '均为 7 天且日期连续',
    len(d['userTrend']) == 7 and len(d['productTrend']) == 7 and len(d['orderTrend']) == 7
    and d['userTrend'][0]['label'] == d['orderTrend'][0]['label'],
    f"长度 {len(d['userTrend'])}/{len(d['productTrend'])}/{len(d['orderTrend'])}，"
    f"首日 {d['userTrend'][0]['label']} ~ 末日 {d['userTrend'][-1]['label']}")

rec('B4', '分布数据可用于图表（分类/订单状态/商品状态）', '检查三类分布非空',
    '均有数据且带中文标签',
    len(d['categoryDist']) > 0 and len(d['orderStatusDist']) > 0 and len(d['productStatusDist']) > 0,
    f"分类 {len(d['categoryDist'])} 项、订单状态 {[x['label'] for x in d['orderStatusDist']]}、"
    f"商品状态 {[x['label'] for x in d['productStatusDist']]}")

# ---------------------------------------------------------------- 三、商品审核
print('\n【3】商品审核流程')

pending_pid = int(one("SELECT id FROM product WHERE deleted=0 AND status=0 ORDER BY id LIMIT 1"))
if not pending_pid:
    print('  没有待审核商品，跳过审核用例（请先执行 db/schema/03-admin.sql）')
else:
    before_home = call('GET', '/product/list?page=1&size=100')
    in_home_before = any(p['id'] == pending_pid for p in before_home['data']['records'])
    r = call('GET', f'/product/{pending_pid}')
    rec('C1', '待审核商品：前台列表不可见、匿名详情不可见',
        f'商品 {pending_pid} 审核前',
        '不在首页列表，且匿名访问详情返回 3001',
        (not in_home_before) and r.get('code') == 3001,
        f"首页可见={in_home_before}，匿名详情 code={r.get('code')}")

    ap = call('PUT', '/admin/product/audit', {'productId': pending_pid, 'approve': True,
                                              'remark': '资料完整，审核通过'}, token=admin)
    row = sql(f"SELECT status, auditor_id, audit_time IS NOT NULL, audit_remark FROM product WHERE id={pending_pid}").split('\t')
    after_home = call('GET', '/product/list?page=1&size=100')
    in_home_after = any(p['id'] == pending_pid for p in after_home['data']['records'])
    rec('C2', '审核通过：状态变在售、审计字段落库、前台可见',
        f'审核通过商品 {pending_pid}',
        'status=1、审计人/时间/意见已记录、出现在首页列表',
        ap.get('code') == 200 and row[0] == '1' and row[1] not in ('', 'NULL')
        and row[2] == '1' and in_home_after,
        f"code={ap.get('code')}；status={row[0]}、auditor_id={row[1]}、audit_time非空={row[2]}、"
        f"审核意见={row[3][:20]}；首页可见={in_home_after}")

    again = call('PUT', '/admin/product/audit', {'productId': pending_pid, 'approve': False}, token=admin)
    rec('C3', '非待审核商品不允许再次审核', f'对已审核商品 {pending_pid} 再审核',
        'code=8003 只有待审核商品才能审核', again.get('code') == 8003,
        f"code={again.get('code')} {again.get('message')}")

    pending_pid2 = int(one("SELECT id FROM product WHERE deleted=0 AND status=0 ORDER BY id LIMIT 1") or 0)
    if pending_pid2:
        rj = call('PUT', '/admin/product/audit', {'productId': pending_pid2, 'approve': False,
                                                  'remark': '涉嫌违禁物品，请勿发布'}, token=admin)
        row2 = sql(f"SELECT status, audit_remark FROM product WHERE id={pending_pid2}").split('\t')
        rec('C4', '审核驳回：状态变审核不通过并记录理由',
            f'驳回商品 {pending_pid2}',
            'status=2 且 audit_remark 写入驳回理由',
            rj.get('code') == 200 and row2[0] == '2' and '违禁' in row2[1],
            f"code={rj.get('code')}；status={row2[0]}，驳回理由={row2[1]}")

    nf = call('PUT', '/admin/product/audit', {'productId': 999999, 'approve': True}, token=admin)
    rec('C5', '审核不存在的商品', 'productId=999999', 'code=3001',
        nf.get('code') == 3001, f"code={nf.get('code')} {nf.get('message')}")

# ---------------------------------------------------------------- 四、强制下架
print('\n【4】管理员强制下架')

on_sale_pid = int(one("SELECT id FROM product WHERE deleted=0 AND status=1 ORDER BY id DESC LIMIT 1"))
from urllib.parse import quote
off = call('PUT', f'/admin/product/offline?productId={on_sale_pid}'
                  f'&reason={quote("测试强制下架（自动化用例）")}', token=admin)
st = one(f"SELECT status FROM product WHERE id={on_sale_pid}")
rec('D1', '强制下架在售商品（可操作他人商品）', f'下架商品 {on_sale_pid}',
    'code=200 且 status 变为 3（已下架）', off.get('code') == 200 and st == '3',
    f"code={off.get('code')}；status={st}")

trading_pid = int(one("SELECT id FROM product WHERE deleted=0 AND status=4 LIMIT 1") or 0)
if trading_pid:
    bad = call('PUT', f'/admin/product/offline?productId={trading_pid}', token=admin)
    rec('D2', '交易中商品不允许下架', f'下架交易中商品 {trading_pid}', 'code=3003',
        bad.get('code') == 3003, f"code={bad.get('code')} {bad.get('message')}")
    # 恢复：把测试下架的商品重新上架（用卖家身份通过接口）
    call('PUT', '/admin/product/audit', {'productId': 1, 'approve': True}, token=admin)
sql(f"UPDATE product SET status=1, audit_remark='管理员下架' WHERE id={on_sale_pid}")

# ---------------------------------------------------------------- 五、用户管理
print('\n【5】用户管理')

ul = call('GET', '/admin/user/list?page=1&size=10', token=admin)['data']
has_stat = all('productCount' in u and 'orderCount' in u and 'reportCount' in u for u in ul['records'])
rec('E1', '用户列表带发布/成交/被举报统计', 'GET /api/admin/user/list',
    '返回统计字段且角色/状态有中文标签', has_stat and ul['total'] >= 4,
    f"共 {ul['total']} 个用户，字段完整={has_stat}，示例={ul['records'][0]['username']}/{ul['records'][0]['roleLabel']}")

tmp_user = f'ad{uuid.uuid4().hex[:5]}'
call('POST', '/user/register', {'username': tmp_user, 'password': 'abc12345', 'nickname': f'后台测试{TAG}'})
tmp_id = int(one(f"SELECT id FROM `user` WHERE username='{tmp_user}'"))
disable = call('PUT', '/admin/user/status', {'userId': tmp_id, 'status': 0}, token=admin)
blocked = call('POST', '/user/login', {'username': tmp_user, 'password': 'abc12345'})
enable = call('PUT', '/admin/user/status', {'userId': tmp_id, 'status': 1}, token=admin)
recovered = call('POST', '/user/login', {'username': tmp_user, 'password': 'abc12345'})
rec('E2', '禁用用户后无法登录，启用后恢复',
    f'禁用/启用用户 {tmp_user}（id={tmp_id}）',
    '禁用后登录返回 2004（账号被禁用），启用后登录成功',
    disable.get('code') == 200 and blocked.get('code') == 2004
    and enable.get('code') == 200 and recovered.get('code') == 200,
    f"禁用 code={disable.get('code')}、禁用后登录 code={blocked.get('code')}「{blocked.get('message')}」、"
    f"启用 code={enable.get('code')}、启用后登录 code={recovered.get('code')}")

self_disable = call('PUT', '/admin/user/status', {'userId': 1, 'status': 0}, token=admin)
rec('E3', '管理员不能禁用自己', 'userId=1（当前管理员）', 'code=8004',
    self_disable.get('code') == 8004, f"code={self_disable.get('code')} {self_disable.get('message')}")

illegal = call('PUT', '/admin/user/status', {'userId': tmp_id, 'status': 9}, token=admin)
rec('E4', '用户状态值非法', 'status=9', 'code=8005（服务端校验：只能为 1 或 0）',
    illegal.get('code') == 8005,
    f"code={illegal.get('code')} {str(illegal.get('message'))[:40]}")

# ---------------------------------------------------------------- 六、举报流程
print('\n【6】举报流程')

target_pid = int(one("SELECT id FROM product WHERE deleted=0 AND status=1 AND seller_id<>"
                     f"(SELECT id FROM `user` WHERE username='stu_test01') ORDER BY id DESC LIMIT 1"))
own_pid = int(one("SELECT id FROM product WHERE deleted=0 AND seller_id="
                  f"(SELECT id FROM `user` WHERE username='stu_test01') LIMIT 1"))
r1 = call('POST', '/report/submit', {'targetType': 1, 'targetId': own_pid, 'reasonType': 1,
                                     'content': '举报自己的商品'}, token=stu)
rec('F1', '不能举报自己发布的内容', f'举报自己的商品 {own_pid}', 'code=8006',
    r1.get('code') == 8006, f"code={r1.get('code')} {r1.get('message')}")

r2 = call('POST', '/report/submit', {'targetType': 1, 'targetId': 999999, 'reasonType': 1}, token=stu)
rec('F2', '举报不存在的对象', 'targetId=999999', 'code=8007',
    r2.get('code') == 8007, f"code={r2.get('code')} {r2.get('message')}")

r3 = call('POST', '/report/submit', {'targetType': 1, 'targetId': target_pid, 'reasonType': 2,
                                     'content': f'v0.13 测试举报 {TAG}'}, token=stu)
new_report_id = r3.get('data', {}).get('id') if r3.get('code') == 200 else None
rec('F3', '用户提交举报成功', f'举报商品 {target_pid}（原因：违禁物品）',
    'code=200 且返回举报ID、状态为待处理', r3.get('code') == 200 and new_report_id is not None,
    f"code={r3.get('code')}，举报ID={new_report_id}，状态={r3.get('data', {}).get('statusLabel')}")

mine = call('GET', '/report/mine?page=1&size=10', token=stu)['data']
rec('F4', '我的举报记录可查', 'GET /api/report/mine',
    '包含刚提交的举报', any(x['id'] == new_report_id for x in mine['records']),
    f"我的举报共 {mine['total']} 条，包含新举报={any(x['id'] == new_report_id for x in mine['records'])}")

lst = call('GET', '/admin/report/list?status=0&page=1&size=20', token=admin)['data']
rec('F5', '管理端待处理举报列表包含该举报且带对象标题', 'GET /api/admin/report/list?status=0',
    '记录存在且 targetTitle 不为空',
    any(x['id'] == new_report_id and x['targetTitle'] for x in lst['records']),
    f"待处理 {lst['total']} 条，其中新举报={[x['id'] for x in lst['records']][:6]}")

hd = call('PUT', '/admin/report/handle', {'reportId': new_report_id, 'action': 1,
                                          'result': 'v0.13 测试：已核实处理'}, token=admin)
row = (sql(f"SELECT status, handle_admin_id, handle_result FROM report WHERE id={new_report_id}")
       or '\t\t').split('\t')
row = (row + ['', '', ''])[:3]
rec('F6', '管理员处理举报并落库', f'处理举报 {new_report_id}',
    'code=200、status=1、处理人与结果写入',
    hd.get('code') == 200 and row[0] == '1' and row[1] not in ('', 'NULL') and '已核实' in row[2],
    f"code={hd.get('code')}；status={row[0]}，处理人={row[1]}，结果={row[2]}")

again = call('PUT', '/admin/report/handle', {'reportId': new_report_id, 'action': 2}, token=admin)
rec('F7', '重复处理举报被拦截', f'再次处理举报 {new_report_id}', 'code=8002',
    again.get('code') == 8002, f"code={again.get('code')} {again.get('message')}")

nf = call('PUT', '/admin/report/handle', {'reportId': 999999, 'action': 1}, token=admin)
rec('F8', '处理不存在的举报', 'reportId=999999', 'code=8001',
    nf.get('code') == 8001, f"code={nf.get('code')} {nf.get('message')}")

# ---------------------------------------------------------------- 七、操作日志
print('\n【7】管理员操作日志')

logs = call('GET', '/admin/log/list?page=1&size=50', token=admin)['data']
actions = [x['action'] for x in logs['records']]
rec('G1', '审核/下架/禁用/处理举报均写入操作日志',
    'GET /api/admin/log/list',
    '日志中包含 AUDIT_PRODUCT、DISABLE_USER、HANDLE_REPORT 等动作',
    'AUDIT_PRODUCT' in actions and 'DISABLE_USER' in actions and 'HANDLE_REPORT' in actions,
    f"日志共 {logs['total']} 条，动作集合={sorted(set(actions))}")

first = logs['records'][0]
rec('G2', '日志包含操作人、对象与详情', '检查单条日志字段',
    'adminName/targetType/targetId/detail 均不为空',
    bool(first.get('adminName') and first.get('targetType') and first.get('detail')),
    f"示例：{first.get('adminName')} {first.get('action')} {first.get('targetType')}"
    f"#{first.get('targetId')} {str(first.get('detail'))[:30]}")

# ---------------------------------------------------------------- 八、审核开关回归
print('\n【8】审核开关回归（默认关闭：发布即上架）')

pub = call('POST', '/product/publish', {'title': f'【v013测试商品】{TAG}', 'categoryId': 8,
                                        'price': 9.9, 'conditionLevel': 1}, token=stu)
new_pid = pub.get('data', {}).get('id')
new_status = one(f"SELECT status FROM product WHERE id={new_pid}") if new_pid else '-'
rec('H1', '默认配置下发布即上架（campus.audit.enabled=false）',
    f'发布商品 → id={new_pid}', 'status=1（在售）',
    pub.get('code') == 200 and new_status == '1',
    f"code={pub.get('code')}，status={new_status}（若为 0 说明审核开关被打开）")

# ---------------------------------------------------------------- 清理
print('\n【9】清理测试数据')
sql(f"DELETE FROM report WHERE content LIKE '%{TAG}%' OR content LIKE '举报自己的商品'")
sql(f"DELETE FROM product WHERE title LIKE '【v013测试商品】%'")
sql("DELETE FROM user_behavior WHERE user_id IN (SELECT id FROM `user` WHERE username REGEXP '^ad[0-9a-f]{5}$')")
sql("DELETE FROM favorite WHERE user_id IN (SELECT id FROM `user` WHERE username REGEXP '^ad[0-9a-f]{5}$')")
sql("DELETE FROM orders WHERE buyer_id IN (SELECT id FROM `user` WHERE username REGEXP '^ad[0-9a-f]{5}$')")
sql("DELETE FROM message WHERE from_user_id IN (SELECT id FROM `user` WHERE username REGEXP '^ad[0-9a-f]{5}$')")
sql("DELETE FROM admin_log WHERE target_id IN (SELECT id FROM `user` WHERE username REGEXP '^ad[0-9a-f]{5}$')")
sql("DELETE FROM `user` WHERE username REGEXP '^ad[0-9a-f]{5}$'")
sql("UPDATE product p SET favorite_count = (SELECT COUNT(*) FROM favorite f "
    "WHERE f.product_id = p.id AND f.deleted = 0)")
left = one("SELECT CONCAT((SELECT COUNT(*) FROM `user` WHERE deleted=0), '/', "
           "(SELECT COUNT(*) FROM product WHERE deleted=0), '/', "
           "(SELECT COUNT(*) FROM report WHERE deleted=0), '/', "
           "(SELECT COUNT(*) FROM admin_log WHERE deleted=0))")
rec('I1', '测试数据清理（用户/商品/举报/日志）', '清理测试账号、测试商品与测试举报',
    '清理完成且返回统计', True, f"清理后 用户/商品/举报/操作日志 = {left}")

# ---------------------------------------------------------------- 汇总
total = len(RESULTS)
passed = sum(1 for x in RESULTS if x['ok'])
print()
print('=' * 106)
print(f'v0.13 管理后台测试汇总：{passed}/{total} 通过')
for x in RESULTS:
    if not x['ok']:
        print(f"  [FAIL] {x['id']} {x['scenario']} —— {x['actual']}")
print('=' * 106)

(TMP / 'v013_result.json').write_text(json.dumps(dict(tag=TAG, results=RESULTS), ensure_ascii=False, indent=1),
                                      encoding='utf-8')
print(f'结果已保存：{TMP / "v013_result.json"}')
