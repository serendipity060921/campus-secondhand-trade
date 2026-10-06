# -*- coding: utf-8 -*-
"""清理端到端测试留下的夹具数据（保护演示数据）

背景：tests/e2e/e2e-browser-test.py 每次运行都会注册 e2e* 账号、发布 E2E* 商品、
产生收藏/订单/消息/行为数据与上传文件，但原先**没有清理逻辑**，跑一次就会污染
演示数据（部署验证 D20 断言 4/17/4/4 会因此失败，答辩演示的首屏也会出现测试商品）。

用法：
  python tools/clean-test-data.py --all              # 清理所有 e2e* 夹具（含历史残留）
  python tools/clean-test-data.py --tag 4a58d        # 只清理某一次运行（标签为 TAG）
  python tools/clean-test-data.py --all --dry-run    # 只报告将删除什么，不执行

识别规则（限定得足够窄，不会误伤演示账号）：
  账号：username LIKE 'e2e%'（可选后缀 TAG）
  商品：title LIKE 'E2E%'（可选后缀 TAG）或卖家是上述账号
"""
import argparse
import os
import pathlib
import subprocess
import sys

MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
UPLOAD_ROOT = pathlib.Path(__file__).resolve().parent.parent / 'backend' / 'uploads'

DEMO_EXPECT = {'user': 4, 'product': 17, 'favorite': 4, 'orders': 4, 'message': 9}


def q(sql, db='campus_trade'):
    r = subprocess.run([MYSQL, '-h127.0.0.1', '-uroot', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', db, '-e', sql],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    if r.returncode != 0:
        raise RuntimeError(f'SQL 失败：{sql[:80]} → {(r.stderr or "")[:160]}')
    return (r.stdout or '').strip()


def scalar(sql, default=''):
    v = q(sql)
    return v.splitlines()[0] if v else default


def ids(sql):
    v = q(sql)
    return [x for x in v.replace('\n', ',').split(',') if x.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--all', action='store_true', help='清理所有 e2e* 夹具（含历史残留）')
    ap.add_argument('--tag', default='', help='只清理某次运行的 TAG（如 4a58d）')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    if not args.all and not args.tag:
        print('请指定 --all 或 --tag <TAG>')
        return 2

    upat = f"e2e%{args.tag}" if args.tag else 'e2e%'
    tpat = f"E2E%{args.tag}" if args.tag else 'E2E%'

    user_ids = ids(f"SELECT id FROM user WHERE username LIKE '{upat}'")
    prod_ids = ids(f"SELECT id FROM product WHERE title LIKE '{tpat}'")
    if user_ids:
        prod_ids += ids(f"SELECT id FROM product WHERE seller_id IN ({','.join(user_ids)})")
    prod_ids = sorted(set(prod_ids))

    print(f'识别到测试账号 {len(user_ids)} 个：{",".join(user_ids) or "无"}')
    print(f'识别到测试商品 {len(prod_ids)} 个：{",".join(prod_ids) or "无"}')
    if not user_ids and not prod_ids:
        print('没有需要清理的夹具数据 ✓')
        return 0

    uin = ','.join(user_ids) or '0'
    pin = ','.join(prod_ids) or '0'

    # 上传文件（cover_image 与 product_image.url 都形如 /upload/2026/10/xxx.png）
    files = []
    if prod_ids:
        for col_sql in (f"SELECT cover_image FROM product WHERE id IN ({pin})",
                        f"SELECT url FROM product_image WHERE product_id IN ({pin})"):
            for line in q(col_sql).splitlines():
                line = line.strip()
                if line.startswith('/upload/'):
                    files.append(UPLOAD_ROOT / line[len('/upload/'):].replace('/', os.sep))
    files = [f for f in files if f.exists()]

    print(f'关联上传文件 {len(files)} 个')
    if args.dry_run:
        for f in files:
            print('   将删除文件：', f)
        print('（dry-run，未执行任何删除）')
        return 0

    # 按外键依赖顺序硬删除
    steps = [
        ('message', f"DELETE FROM message WHERE from_user_id IN ({uin}) OR to_user_id IN ({uin}) OR product_id IN ({pin})"),
        ('favorite', f"DELETE FROM favorite WHERE user_id IN ({uin}) OR product_id IN ({pin})"),
        ('orders', f"DELETE FROM orders WHERE buyer_id IN ({uin}) OR seller_id IN ({uin}) OR product_id IN ({pin})"),
        ('user_behavior', f"DELETE FROM user_behavior WHERE user_id IN ({uin}) OR product_id IN ({pin})"),
        ('report', f"DELETE FROM report WHERE reporter_id IN ({uin}) OR handle_admin_id IN ({uin}) OR (target_id IN ({pin}))"),
        ('product_image', f"DELETE FROM product_image WHERE product_id IN ({pin})"),
        ('product', f"DELETE FROM product WHERE id IN ({pin})"),
        ('user', f"DELETE FROM user WHERE id IN ({uin})"),
    ]
    for name, stmt in steps:
        affected = scalar(stmt + "; SELECT ROW_COUNT();", '0')
        print(f'  {name:16s} 删除 {affected} 行')

    for f in files:
        try:
            f.unlink()
            print('  已删除文件：', f.name)
        except Exception as e:
            print('  文件删除失败：', f, e)

    # 清理 Redis 在线状态里的历史成员（与后端的懒惰清扫语义一致）
    redis = pathlib.Path(r'D:\major\tool\redis\redis-cli.exe')
    if redis.exists():
        import time
        now = int(time.time())
        r = subprocess.run([str(redis), 'ZREMRANGEBYSCORE', 'campus:online:zset', '-inf', str(now - 90)],
                           capture_output=True, text=True)
        print(f'  redis 在线状态 ZSet 清理 {r.stdout.strip() or 0} 个过期成员')

    print('\n=== 清理后核对 ===')
    ok = True
    for table, expect in DEMO_EXPECT.items():
        now_count = scalar(f'SELECT COUNT(*) FROM `{table}` WHERE deleted=0', '0')
        flag = '✓' if now_count == str(expect) else '✗'
        if now_count != str(expect):
            ok = False
        print(f'  {flag} {table:10s} {now_count}（期望 {expect}）')
    left_u = scalar(f"SELECT COUNT(*) FROM user WHERE username LIKE '{upat}' AND deleted=0", '0')
    left_p = scalar(f"SELECT COUNT(*) FROM product WHERE title LIKE '{tpat}' AND deleted=0", '0')
    print(f'  {"✓" if left_u == "0" and left_p == "0" else "✗"} 残留夹具：账号 {left_u} / 商品 {left_p}')
    if ok and left_u == '0' and left_p == '0':
        print('\n✓ 演示数据已恢复（部署验证 D20 断言 4/17/4/4 可重新通过）')
        return 0
    print('\n✗ 数据未完全恢复，请人工核对')
    return 1


if __name__ == '__main__':
    sys.exit(main())
