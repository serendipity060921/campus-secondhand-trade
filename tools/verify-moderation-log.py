# -*- coding: utf-8 -*-
"""机审落库端到端验证（C3）

验证链路：真实调发布接口 → 机审判定 → 落库 moderation_log → 查库核对。

三条被测内容分别覆盖机审的三种结论：
  · PASS   ：普通教材，无任何命中 → 放行
  · REVIEW ：标题含手机号（R2-联系方式）→ 转人工复核
  · REJECT ：标题含违禁词「代写」（R1-违禁词）→ 拒绝发布

执行：
  python tools/verify-moderation-log.py
说明：
  · 依赖已登录的演示账号 stu_test01（发布者）；
  · 脚本结束会清理本次创建的商品（标题以「【验证】」开头），
    但**保留机审记录**（moderation_log 是只追加的审计事实表）。
"""
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get('API_BASE', 'http://127.0.0.1:8081').rstrip('/')
MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
SELLER = ('stu_test01', 'abc12345')
MARK = '【验证】'


def sql(q):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-uroot', '-p123456', '-N', '-B',
                        '--default-character-set=utf8mb4', 'campus_trade', '-e', q],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    if r.returncode != 0 and 'Using a password' not in (r.stderr or ''):
        print('  SQL 失败：' + (r.stderr or '').strip()[:200])
    return (r.stdout or '').strip()


def http(method, path, body=None, token=None):
    url = API + path
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode('utf-8', 'replace') or '{}')
        except Exception:
            return {'code': e.code}


def main():
    print('=' * 92)
    print('机审落库端到端验证：发布三种内容 → 核对 moderation_log')
    print('=' * 92)

    login = http('POST', '/api/user/login', {'username': SELLER[0], 'password': SELLER[1]})
    token = (login.get('data') or {}).get('token')
    if not token:
        print('  ✗ 登录失败，无法验证：' + str(login)[:200])
        return 1
    print(f'  发布者 {SELLER[0]} 已登录')

    category_id = sql("SELECT id FROM category WHERE deleted = 0 AND parent_id IS NULL ORDER BY id LIMIT 1")
    category_id = int(category_id.splitlines()[0]) if category_id else 1
    print(f'  使用分类 id={category_id}')

    before = int(sql("SELECT COUNT(*) FROM moderation_log") or 0)

    cases = [
        ('PASS', '【验证】九成新《数据结构》教材 无划痕', '东校区自取，书况良好', 25.00),
        ('REVIEW', '【验证】低价出教材 联系13812345678', '有意者联系，价格可谈', 20.00),
        ('REJECT', '【验证】代写课程论文 包过', '专业代写各类论文，包过', 100.00),
    ]

    print('\n  发布三条内容：')
    for expect, title, desc, price in cases:
        r = http('POST', '/api/product/publish',
                 {'title': title, 'description': desc, 'price': price,
                  'categoryId': category_id, 'conditionLevel': 3,
                  'campus': '东校区', 'tradePlace': '图书馆门口'}, token)
        print(f'    {expect:<7} 接口返回 code={r.get("code")}  msg={str(r.get("message"))[:34]}')

    time.sleep(0.6)
    after = int(sql("SELECT COUNT(*) FROM moderation_log") or 0)
    print(f'\n  moderation_log 行数：{before} → {after}（新增 {after - before}）')

    rows = sql("SELECT action, IFNULL(risk_rule,'-'), hit_count, LEFT(title, 22) "
               "FROM moderation_log ORDER BY id DESC LIMIT 3") or ''
    found = []
    print('\n  最近三条机审记录（倒序）：')
    for line in rows.splitlines():
        parts = line.split('\t')
        found.append(parts[0])
        print('    action=' + parts[0].ljust(7) + ' 规则=' + parts[1].ljust(14)
              + ' 命中=' + parts[2] + ' 标题=' + (parts[3] if len(parts) > 3 else ''))

    expect_order = ['REJECT', 'REVIEW', 'PASS']
    ok = (after - before == 3) and (found == expect_order)

    print('\n' + '-' * 92)
    if ok:
        print('  ✅ 验证通过：三种机审结论都已落库（REJECT / REVIEW / PASS 各 1 条）')
    else:
        print(f'  ✗ 验证失败：期望新增 3 条且倒序为 {expect_order}，实际新增 {after - before} 条，倒序 {found}')

    # 清理：删除本次验证创建的商品（PASS / REVIEW 会真正建商品），保留机审记录
    created = sql(f"SELECT id FROM product WHERE title LIKE '{MARK}%'")
    ids = [x for x in created.splitlines() if x.strip()]
    if ids:
        id_list = ','.join(ids)
        sql(f"DELETE FROM product_image WHERE product_id IN ({id_list})")
        sql(f"DELETE FROM favorite WHERE product_id IN ({id_list})")
        sql(f"DELETE FROM user_behavior WHERE product_id IN ({id_list})")
        sql(f"DELETE FROM product WHERE id IN ({id_list})")
        print(f'  已清理本次创建的商品 {len(ids)} 件（机审记录按只追加原则保留）')

    left = sql(f"SELECT COUNT(*) FROM product WHERE title LIKE '{MARK}%'")
    print(f'  清理后仍存在的「{MARK}」商品：{left} 件')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
