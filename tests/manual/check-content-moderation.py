# -*- coding: utf-8 -*-
"""内容机审（C 方案）的接口级验证

验证三种发布结果：
  ① 明确违禁内容  → 拒绝发布（业务码 3010，带具体原因）
  ② 可疑内容      → 发布成功但进入「待审核」status=0（转人工复核）
  ③ 正常内容      → 发布成功且直接上架 status=1（回归：不能误伤）

运行：<python> tests/manual/check-content-moderation.py
清理：python tools/clean-test-data.py --all（标题以 E2E 开头，会被精确匹配清理）
"""
import json
import pathlib
import sys
import urllib.error
import urllib.request
import uuid

BASE = 'http://127.0.0.1:8080'
TAG = uuid.uuid4().hex[:5]
SELLER = ('stu_test01', 'abc12345')


def post(path, payload, token=None):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json',
                 **({'Authorization': 'Bearer ' + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode() or '{}')


def login():
    r = post('/api/user/login', {'username': SELLER[0], 'password': SELLER[1]})
    return (r.get('data') or {}).get('token')


def publish(token, title, desc, price='88.50'):
    return post('/api/product/publish', {
        'title': title, 'description': desc, 'categoryId': 1,
        'price': price, 'originalPrice': None, 'conditionLevel': 2,
        'campus': '东校区', 'tradePlace': '东校区图书馆门口', 'imageUrls': []
    }, token)


results = []
token = login()
print(f'  登录 {"成功" if token else "失败"}   夹具 TAG={TAG}')
if not token:
    sys.exit(1)

print('\n【① 明确违禁内容 → 应被拒绝（code 3010）】')
r1 = publish(token, f'E2E机审验证-{TAG}-专业代写毕业论文包过', '各科都能代写，价格好谈')
ok1 = r1.get('code') == 3010 and '代写' in str(r1.get('message', ''))
print(f'  返回 code={r1.get("code")} message={r1.get("message")}')
print(f'  → {"✓ 已拒绝且给出原因（不落库）" if ok1 else "✗ 未被拒绝"}')
results.append(('① 违禁内容被拒绝', ok1))

print('\n【② 可疑内容（描述留手机号）→ 应发布成功但转「待审核」status=0】')
r2 = publish(token, f'E2E机审验证-{TAG}-四级词汇书', '有意者联系 13812345678 详聊')
ok2 = r2.get('code') == 200 and (r2.get('data') or {}).get('status') == 0
print(f'  返回 code={r2.get("code")} status={(r2.get("data") or {}).get("status")} id={(r2.get("data") or {}).get("id")}')
print(f'  → {"✓ 已转待审核（进管理后台人工复核）" if ok2 else "✗ 未转待审核"}')
results.append(('② 可疑内容转人工复核（status=0）', ok2))

print('\n【③ 正常内容 → 应直接上架 status=1（回归：不能误伤）】')
r3 = publish(token, f'E2E机审验证-{TAG}-高等数学教材九成新', '大一用过，有少量笔记，东校区自提')
ok3 = r3.get('code') == 200 and (r3.get('data') or {}).get('status') == 1
print(f'  返回 code={r3.get("code")} status={(r3.get("data") or {}).get("status")} id={(r3.get("data") or {}).get("id")}')
print(f'  → {"✓ 正常商品直接上架（既有行为未受影响）" if ok3 else "✗ 正常商品被误伤"}')
results.append(('③ 正常内容直接上架（status=1）', ok3))

print('\n【④ 规避写法（词中插空格）→ 仍应被拒绝】')
r4 = publish(token, f'E2E机审验证-{TAG}-代 写 论 文', '包过')
ok4 = r4.get('code') == 3010
print(f'  返回 code={r4.get("code")} message={r4.get("message")}')
print(f'  → {"✓ 归一化后仍能命中" if ok4 else "✗ 被绕过"}')
results.append(('④ 规避写法仍被拦截', ok4))

print('\n' + '=' * 56)
passed = sum(1 for _, ok in results if ok)
for name, ok in results:
    print(('  ✓ ' if ok else '  ✗ ') + name)
print(f'结论：{passed}/{len(results)} 项通过')
print(f'提示：本次创建的商品标题均以 E2E 开头，可用 tools/clean-test-data.py --all 清理')
sys.exit(0 if passed == len(results) else 1)
