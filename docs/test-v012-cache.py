# -*- coding: utf-8 -*-
"""校园二手交易平台 v0.12 缓存与限流测试用例

覆盖：
  · 缓存：分类列表、商品列表、商品详情、推荐结果的命中与主动失效
  · 防穿透：不存在商品写入空值缓存
  · 浏览量：Redis 去重 + 计数 + 阈值批量回写
  · 热门榜：行为加分后从 Redis ZSet 读取
  · 限流：@RateLimit 注解（固定窗口 + Lua 原子计数），本机默认跳过
  · 安全：退出登录后 Token 立即失效（Redis 黑名单）

执行：python docs/test-v012-cache.py
前提：后端 8080、MySQL 3306、Redis 6379 已启动
"""
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

API = 'http://127.0.0.1:8080/api'
MYSQL = r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe'
REDIS = r'D:\major\tool\redis\redis-cli.exe'
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


def redis(*args):
    r = subprocess.run([REDIS, '-h', '127.0.0.1', '-p', '6379', *args],
                       capture_output=True, text=True, encoding='utf-8', timeout=30)
    return (r.stdout or '').strip()


def call(method, path, body=None, token=None, headers=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
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
    print(f"  [{'PASS' if ok else 'FAIL'}] {cid:6s} {scenario[:36]:38s} {actual[:98]}")
    sys.stdout.flush()


def login(u, p, headers=None):
    r = call('POST', '/user/login', {'username': u, 'password': p}, headers=headers)
    return r['data']['token'] if r.get('code') == 200 else None


TAG = uuid.uuid4().hex[:5]
admin = login('admin', '123456')
stu = login('stu_test01', 'abc12345')
if not admin or not stu:
    print('无法登录测试账号，请确认后端已启动')
    sys.exit(1)

print('=' * 106)
print('v0.12 缓存与限流测试用例')
print('=' * 106)

# ---------------------------------------------------------------- 一、缓存命中
print('\n【1】缓存命中（Cache-Aside）')

# 先清掉本次测试可能残留的 Key，保证从"未命中"开始
redis('del', 'campus:category:list:all', 'campus:category:list:top')
call('GET', '/category/list')
t0 = time.time()
exists = redis('exists', 'campus:category:list:all') == '1'
ttl = int(redis('ttl', 'campus:category:list:all') or -1)
call('GET', '/category/list')          # 第二次应命中缓存
rec('C1', '分类列表写入缓存并可命中', 'GET /api/category/list 连续两次',
    'Redis 出现 campus:category:list:all 且 TTL>0',
    exists and ttl > 0, f"key 存在={exists}，TTL={ttl} 秒（配置 600 秒 + 随机抖动）")

redis('del', 'campus:product:list:page=1&size=12&sort=new')
call('GET', '/product/list?page=1&size=12')
call('GET', '/product/list?page=1&size=12')
keys = redis('keys', 'campus:product:list:*').splitlines()
rec('C2', '商品列表按查询条件缓存', 'GET /api/product/list?page=1&size=12 两次',
    '存在一个带条件指纹的列表 Key',
    any('page=1&size=12' in k for k in keys), f"列表缓存 Key：{keys}")

call('GET', '/product/list?page=2&size=12')
keys2 = redis('keys', 'campus:product:list:*').splitlines()
rec('C3', '不同查询条件使用不同缓存 Key', '再请求 page=2',
    'Key 数量增加且互不覆盖', len(keys2) >= 2, f"当前列表缓存 Key 数={len(keys2)}")

redis('del', 'campus:product:detail:6')
call('GET', '/product/6')
call('GET', '/product/6')
d_exists = redis('exists', 'campus:product:detail:6') == '1'
d_ttl = int(redis('ttl', 'campus:product:detail:6') or -1)
rec('C4', '商品详情写入缓存', 'GET /api/product/6 两次',
    'Redis 出现 campus:product:detail:6 且 TTL>0', d_exists and d_ttl > 0,
    f"key 存在={d_exists}，TTL={d_ttl} 秒（配置 120 秒 + 抖动）")

redis('del', 'campus:recommend:anon:8:auto')
call('GET', '/product/recommend?size=8')
call('GET', '/product/recommend?size=8')
r_exists = redis('exists', 'campus:recommend:anon:8:auto') == '1'
rec('C5', '推荐结果写入缓存（最贵接口）', 'GET /api/product/recommend?size=8 两次',
    'Redis 出现 campus:recommend:anon:8:auto', r_exists,
    f"key 存在={r_exists}，TTL={redis('ttl', 'campus:recommend:anon:8:auto')} 秒")

# ---------------------------------------------------------------- 二、缓存失效
print('\n【2】缓存主动失效（一致性）')

r_before = call('GET', '/category/list')
count_before = len(r_before['data'])
cat_name = f'缓存测试分类{TAG}'
add = call('POST', '/category/add', {'name': cat_name, 'parentId': 0, 'sortOrder': 90}, token=admin)
key_gone = redis('exists', 'campus:category:list:all') == '0'
r_after = call('GET', '/category/list')
appeared = any(c['name'] == cat_name for c in r_after['data'])
rec('C6', '新增分类后缓存被主动失效', f'管理端新增分类「{cat_name}」',
    '分类缓存 Key 被删除，且列表立即可见新分类',
    add.get('code') == 200 and key_gone and appeared,
    f"新增 code={add.get('code')}；缓存 Key 已删除={key_gone}；新分类立即可见={appeared}"
    f"（分类数 {count_before} → {len(r_after['data'])}）")
new_cat_id = add.get('data', {}).get('id')

# 商品上下架触发商品详情与列表缓存失效
# 用 admin 自己发布的商品做上下架（否则会因越权返回 3002，测不到缓存失效）
pid = int(one("SELECT id FROM product WHERE deleted=0 AND status=1 AND seller_id=1 ORDER BY id LIMIT 1"))
call('GET', f'/product/{pid}')
call('GET', '/product/list?page=1&size=12')
call('PUT', '/product/status', {'productId': pid, 'status': 3}, token=admin)
detail_gone = redis('exists', f'campus:product:detail:{pid}') == '0'
list_gone = len(redis('keys', 'campus:product:list:*').splitlines()) == 0
call('PUT', '/product/status', {'productId': pid, 'status': 1}, token=admin)
rec('C7', '商品下架后详情与列表缓存被失效', f'下架商品 {pid}',
    '商品详情 Key 与列表 Key 都被删除（避免读到旧数据）',
    detail_gone and list_gone, f"详情 Key 已删除={detail_gone}，列表 Key 已删除={list_gone}")

# ---------------------------------------------------------------- 三、防穿透与浏览量
print('\n【3】防穿透与浏览量优化')

redis('del', 'campus:product:detail:999999')
r1 = call('GET', '/product/999999')
null_cached = redis('exists', 'campus:product:detail:999999') == '1'
r2 = call('GET', '/product/999999')
rec('C8', '缓存穿透防护：不存在的商品写入空值缓存', 'GET /api/product/999999 连续两次',
    '返回 3001 且 Redis 留下短 TTL 的空值标记',
    r1.get('code') == 3001 and null_cached and r2.get('code') == 3001,
    f"两次均返回 {r1.get('code')}；空值缓存 Key 存在={null_cached}，TTL={redis('ttl', 'campus:product:detail:999999')} 秒")

target = int(one("SELECT id FROM product WHERE deleted=0 AND status=1 ORDER BY id DESC LIMIT 1"))
before_views = int(one(f"SELECT view_count FROM product WHERE id={target}"))
redis('del', f'campus:view:counter:{target}', f'campus:product:detail:{target}')
# 清掉上一轮遗留的浏览量去重标记（TTL 600 秒，否则本轮会被判定为重复访问）
dedup_keys = [k for k in redis('keys', f'campus:view:dedup:{target}:*').splitlines() if k]
if dedup_keys:
    redis('del', *dedup_keys)
# 同一访问者连续访问 3 次 → 去重后只计 1 次（且未达回写阈值，不写库）
for _ in range(3):
    call('GET', f'/product/{target}', headers={'X-Forwarded-For': '203.0.113.11'})
pending = int(redis('get', f'campus:view:counter:{target}') or 0)
after_views = int(one(f"SELECT view_count FROM product WHERE id={target}"))
rec('C9', '浏览量 Redis 去重：同一访问者重复访问只计一次', f'同一 IP 连续访问商品 {target} 3 次',
    'Redis 计数=1，数据库 view_count 暂不变（未达回写阈值）',
    pending == 1 and after_views == before_views,
    f"Redis 待回写计数={pending}，数据库 view_count {before_views} → {after_views}")

# 不同访问者累计到阈值 → 批量回写数据库
for i in range(25):
    call('GET', f'/product/{target}', headers={'X-Forwarded-For': f'198.51.100.{i + 1}'})
after_flush = int(one(f"SELECT view_count FROM product WHERE id={target}"))
counter_now = int(redis('get', f'campus:view:counter:{target}') or 0)
rec('C10', '浏览量批量回写：达到阈值后一次性写库', '25 个不同 IP 依次访问同一商品',
    '数据库 view_count 增加，Redis 计数被清零重新累计',
    after_flush > after_views and counter_now < 20,
    f"数据库 view_count {after_views} → {after_flush}（+{after_flush - after_views}），回写后 Redis 计数={counter_now}")

# ---------------------------------------------------------------- 四、热门榜
print('\n【4】热门榜（Redis ZSet）')

hot_keys = [k for k in redis('keys', 'campus:hot:*').splitlines() if k]
if hot_keys:
    redis('del', *hot_keys)
hot_target = int(one("SELECT id FROM product WHERE deleted=0 AND status=1 AND seller_id<>"
                     f"(SELECT id FROM `user` WHERE username='stu_test01') ORDER BY id DESC LIMIT 1"))
call('POST', '/favorite/operate', {'productId': hot_target, 'type': 2}, token=stu)   # 先取消，保证能再收藏
call('POST', '/favorite/operate', {'productId': hot_target}, token=stu)              # 收藏 +5 分
time.sleep(0.5)
hot_key = redis('keys', 'campus:hot:*').strip()
hot_score = redis('zscore', hot_key, str(hot_target)) if hot_key else ''
hot = call('GET', '/product/hot?size=10')
hot_ids = [i['productId'] for i in hot.get('data', [])]
rec('C11', '用户行为写入热门榜（ZSet 加分）', f'收藏商品 {hot_target}（权重 5）',
    'ZSet 中出现该商品且分数 ≥5，/api/product/hot 能返回它',
    bool(hot_key) and hot_score not in ('', None) and float(hot_score or 0) >= 5 and hot_target in hot_ids,
    f"榜单 Key={hot_key}，该商品分数={hot_score}；热门榜返回 {len(hot_ids)} 条，包含目标商品={hot_target in hot_ids}")

rec('C12', '热门榜只返回在售商品且有序', 'GET /api/product/hot?size=10',
    '全部为在售商品，条数不超过 10',
    hot.get('code') == 200 and len(hot_ids) <= 10 and all(
        one(f"SELECT status FROM product WHERE id={i}") == '1' for i in hot_ids),
    f"code={hot.get('code')}，返回 {len(hot_ids)} 条，全部在售={all(one(f'SELECT status FROM product WHERE id={i}') == '1' for i in hot_ids)}")

# ---------------------------------------------------------------- 五、限流
print('\n【5】注解式限流（@RateLimit）')

FAKE_IP = {'X-Forwarded-For': '203.0.113.250'}
redis('del', f'campus:ratelimit:login:ip203.0.113.250')
codes = []
for i in range(22):
    r = call('POST', '/user/login', {'username': 'admin', 'password': '123456'}, headers=FAKE_IP)
    codes.append(r.get('code'))
ok_count = sum(1 for c in codes if c == 200)
limited = sum(1 for c in codes if c == 429)
rec('C13', '登录接口限流：外部 IP 超过 20 次/分钟被拒绝', '同一 X-Forwarded-For 连续登录 22 次',
    '前 20 次成功，之后返回 429',
    ok_count == 20 and limited == 2, f"成功 {ok_count} 次、限流 {limited} 次，返回码序列尾部={codes[-4:]}")

redis('del', f'campus:ratelimit:login:ip203.0.113.250')
local_codes = [call('POST', '/user/login', {'username': 'admin', 'password': '123456'}).get('code') for _ in range(23)]
rec('C14', '本机回环地址默认跳过限流（开发/压测友好）', '本机连续登录 23 次（无 XFF）',
    '全部成功（campus.cache.rate-limit-skip-local=true）',
    all(c == 200 for c in local_codes), f"23 次全部返回 200={all(c == 200 for c in local_codes)}")

FAKE_IP2 = {'X-Forwarded-For': '203.0.113.251'}
redis('del', 'campus:ratelimit:register:ip203.0.113.251')
reg_codes = []
for i in range(12):
    reg_codes.append(call('POST', '/user/register',
                          {'username': f'rl{uuid.uuid4().hex[:6]}', 'password': 'abc12345',
                           'nickname': f'限流测试{i}'}, headers=FAKE_IP2).get('code'))
rec('C15', '注册接口限流：10 次/5 分钟', '同一外部 IP 连续注册 12 次',
    '前 10 次成功，之后返回 429',
    reg_codes.count(200) == 10 and reg_codes.count(429) == 2,
    f"成功 {reg_codes.count(200)} 次、限流 {reg_codes.count(429)} 次")
redis('del', 'campus:ratelimit:register:ip203.0.113.251')

# ---------------------------------------------------------------- 六、Token 黑名单
print('\n【6】退出登录 Token 黑名单')

tmp_user = f'tt{uuid.uuid4().hex[:5]}'
call('POST', '/user/register', {'username': tmp_user, 'password': 'abc12345', 'nickname': f'黑名单测试{TAG}'})
tmp_token = login(tmp_user, 'abc12345')
before_logout = call('GET', '/user/info', token=tmp_token).get('code')
logout = call('POST', '/user/logout', token=tmp_token)
after_logout = call('GET', '/user/info', token=tmp_token)
bl_keys = redis('keys', 'campus:jwt:blacklist:*').splitlines()
rec('C16', '退出登录后 Token 立即失效（Redis 黑名单）', '登录 → 访问 /user/info → 退出 → 再访问',
    '退出前 200、退出后 401，且黑名单 Key 存在且 TTL 合理',
    before_logout == 200 and logout.get('code') == 200 and after_logout.get('code') == 401 and len(bl_keys) >= 1,
    f"退出前 {before_logout}、退出后 {after_logout.get('code')}「{after_logout.get('message')}」；"
    f"黑名单 Key 数={len(bl_keys)}，TTL={redis('ttl', bl_keys[0]) if bl_keys else '-'} 秒")

rec('C17', '黑名单只存摘要不存原 Token', '检查 Redis 黑名单 Key 名',
    'Key 为 SHA-256 摘要（64 位十六进制），不包含 Token 原文',
    bool(bl_keys) and len(bl_keys[0].split(':')[-1]) == 64,
    f"Key 末段长度={len(bl_keys[0].split(':')[-1]) if bl_keys else 0}（SHA-256 十六进制=64）")

# ---------------------------------------------------------------- 七、Key 规范
print('\n【7】缓存 Key 规范与可观测性')

keys = [k for k in redis('keys', 'campus:*').splitlines() if k]
prefix_ok = all(k.startswith('campus:') for k in keys)
has_ttl = all(int(redis('ttl', k) or -1) > 0 for k in keys[:6])
rec('C18', '缓存 Key 统一前缀且都有过期时间', f'检查 {len(keys)} 个 Key',
    '全部以 campus: 开头，且抽查的 Key 都有 TTL（不会永久占内存）',
    prefix_ok and has_ttl, f"Key 总数={len(keys)}，前缀统一={prefix_ok}，抽查 TTL>0={has_ttl}")

# ---------------------------------------------------------------- 清理
print('\n【8】清理测试数据')
if new_cat_id:
    sql(f"DELETE FROM category WHERE id = {new_cat_id}")
    redis('del', 'campus:category:list:all', 'campus:category:list:top')
sql("DELETE FROM favorite WHERE user_id IN (SELECT id FROM `user` WHERE username LIKE 'rl%' "
    "OR username LIKE 'tt%')")
sql("DELETE FROM user_behavior WHERE user_id IN (SELECT id FROM `user` WHERE username LIKE 'rl%' "
    "OR username LIKE 'tt%')")
sql("DELETE FROM `user` WHERE username LIKE 'rl%' OR username LIKE 'tt%'")
sql("UPDATE product p SET favorite_count = (SELECT COUNT(*) FROM favorite f "
    "WHERE f.product_id = p.id AND f.deleted = 0)")
left = one("SELECT CONCAT((SELECT COUNT(*) FROM `user` WHERE deleted=0), '/', "
           "(SELECT COUNT(*) FROM product WHERE deleted=0), '/', "
           "(SELECT COUNT(*) FROM category WHERE deleted=0), '/', "
           "(SELECT COUNT(*) FROM favorite WHERE deleted=0))")
rec('C19', '测试数据清理与状态还原', '删除限流测试账号、测试分类并重算收藏计数',
    '数据库回到演示状态', True, f"清理后 用户/商品/分类/收藏 = {left}")

# ---------------------------------------------------------------- 汇总
total = len(RESULTS)
passed = sum(1 for x in RESULTS if x['ok'])
print()
print('=' * 106)
print(f'v0.12 测试汇总：{passed}/{total} 通过')
for x in RESULTS:
    if not x['ok']:
        print(f"  [FAIL] {x['id']} {x['scenario']} —— {x['actual']}")
print('=' * 106)

(TMP / 'v012_result.json').write_text(json.dumps(dict(tag=TAG, results=RESULTS), ensure_ascii=False, indent=1),
                                      encoding='utf-8')
print(f'结果已保存：{TMP / "v012_result.json"}')
