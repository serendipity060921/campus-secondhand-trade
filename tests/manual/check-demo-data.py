# -*- coding: utf-8 -*-
"""只读核查：演示数据的现状与"多出来"的记录是什么（不删除任何数据）

用法：<python> tests/manual/check-demo-data.py
"""
import os
import pathlib
import subprocess

MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
EXPECT = {'user': 4, 'product': 17, 'favorite': 4, 'orders': 4, 'message': 9}


def q(sql, db='campus_trade'):
    r = subprocess.run([MYSQL, '-h127.0.0.1', '-uroot', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', db, '-e', sql],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or '')[:200])
    return [line for line in (r.stdout or '').strip().splitlines() if line]


print('=== 一、不变量对照 ===')
for table, want in EXPECT.items():
    got = int(q(f'SELECT COUNT(*) FROM `{table}`')[0])
    print(f'  {table:<10} 实际 {got:>3}   期望 {want:>3}   '
          + ('✓' if got == want else f'✗ 多出 {got - want}'))

print('\n=== 二、用户表逐条（找出多出来的那个）===')
for row in q("SELECT id, username, nickname, role, status, "
             "DATE_FORMAT(create_time,'%Y-%m-%d %H:%i') FROM user ORDER BY id"):
    print('  ' + row)

print('\n=== 三、商品表（只看非演示口径的/标题可疑的）===')
for row in q("SELECT id, title, seller_id, status, deleted, "
             "DATE_FORMAT(create_time,'%Y-%m-%d %H:%i') FROM product "
             "WHERE deleted = 0 ORDER BY id"):
    print('  ' + row)

print('\n=== 四、消息表逐条（找出多出来的 6 条）===')
for row in q("SELECT m.id, m.from_user_id, m.to_user_id, LEFT(COALESCE(m.content,''),22), "
             "m.is_read, DATE_FORMAT(m.create_time,'%m-%d %H:%i') FROM message m ORDER BY m.id"):
    print('  ' + row)

print('\n=== 五、是否存在 e2e / E2E 夹具残留 ===')
print('  e2e 账号: ' + (q("SELECT COUNT(*) FROM user WHERE username LIKE 'e2e%'")[0]) +
      '    E2E 商品: ' + (q("SELECT COUNT(*) FROM product WHERE title LIKE 'E2E%'")[0]))
