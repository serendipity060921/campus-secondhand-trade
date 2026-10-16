# -*- coding: utf-8 -*-
"""演示数据快照与重置（A3）

背景：演示库会随手工测试/自动化测试产生漂移（例如行数与文档不变量 4/17/4/4/9 不一致），
      导致「部署验证」里的数据完整性断言长期为红。本脚本提供两条命令：

  --capture   把当前库导出为基线快照 db/demo-data/demo-baseline.sql
              · 自动排除 e2e 夹具（username LIKE 'e2e%'、title LIKE 'E2E%'）
              · 可用 --exclude-user / --exclude-product / --exclude-message 排除历史遗留数据
              · 其余依赖表会按外键列自动过滤，避免快照里留下指向被排除数据的孤儿行
  --reset     用基线快照重置数据库
              · ★ 重置前自动备份当前数据到 db/demo-data/backup-<时间戳>.sql
              · 按外键安全顺序清空后导入基线
              · 结束后核对行数与文档不变量是否一致
  --verify    只核对行数，不改数据

用法：
  python tools/seed-demo-data.py --verify
  python tools/seed-demo-data.py --capture --exclude-user 390 --exclude-product 495 --exclude-message 300-305
  python tools/seed-demo-data.py --reset --dry-run
  python tools/seed-demo-data.py --reset
"""

# ★ Windows 控制台的默认编码是 GBK，而本脚本会输出 ✓/✗/✅ 等字符 ——
#   不重新配置 stdout 的话，手动执行会直接抛 UnicodeEncodeError 中断。
import sys as _sys
try:
    _sys.stdout.reconfigure(encoding='utf-8')
    _sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass
import argparse
import datetime as dt
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / 'db' / 'demo-data'
BASELINE = OUT_DIR / 'demo-baseline.sql'
MYSQL_BIN = pathlib.Path(r'D:\major\tool\mysql-8.4.4-winx64\bin')
MYSQL = MYSQL_BIN / 'mysql.exe'
MYSQLDUMP = MYSQL_BIN / 'mysqldump.exe'
DB = 'campus_trade'
AUTH = ['-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456']

# 文档不变量（见 tools/clean-test-data.py 的 DEMO_EXPECT）
EXPECT = {'user': 4, 'product': 17, 'favorite': 4, 'orders': 4, 'message': 9}

# 清空顺序（先子表后父表）与导入顺序（先父表后子表）
TABLES = ['admin_log', 'report', 'user_behavior', 'product_image', 'favorite', 'orders',
          'message', 'product', 'user', 'category']
DELETE_ORDER = ['admin_log', 'report', 'user_behavior', 'product_image', 'favorite', 'orders',
                'message', 'product', 'user', 'category']
INSERT_ORDER = ['category', 'user', 'product', 'product_image', 'favorite', 'orders',
                'message', 'user_behavior', 'report', 'admin_log']


def run(cmd, sql=None, allow_fail=False):
    p = subprocess.run(cmd, input=sql, capture_output=True, text=True,
                       encoding='utf-8', timeout=180)
    if p.returncode != 0 and not allow_fail:
        raise RuntimeError((p.stderr or p.stdout or '')[:400])
    return p.stdout


def q(sql):
    out = run([str(MYSQL), *AUTH, '--default-character-set=utf8mb4', '-N', '-B', DB, '-e', sql])
    return out.strip()


def exec_sql(sql):
    return run([str(MYSQL), *AUTH, '--default-character-set=utf8mb4', DB], sql=sql)


def counts():
    return {t: int(q(f'SELECT COUNT(*) FROM `{t}`')) for t in TABLES}


def show_counts(title, c):
    print(f'\n  【{title}】')
    ok_all = True
    for t in TABLES:
        line = f'    {t:<16} {c[t]:>4}'
        if t in EXPECT:
            mark = '✓' if c[t] == EXPECT[t] else f'✗ 期望 {EXPECT[t]}'
            ok_all &= (c[t] == EXPECT[t])
            line += f'   {mark}'
        print(line)
    return ok_all


def fk_filter(table, excluded):
    """按外键列生成过滤条件：排除指向被排除 user/product 的行"""
    if not excluded:
        return None
    cols = q(f"""SELECT COLUMN_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
                 WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='{table}'
                   AND REFERENCED_TABLE_NAME IN ('user','product')""")
    conds = []
    for line in cols.splitlines():
        parts = line.split('\t')
        if len(parts) != 2:
            continue
        col, ref = parts
        ids = excluded.get(ref)
        if ids:
            conds.append(f"`{col}` NOT IN ({','.join(str(i) for i in ids)})")
    return ' AND '.join(conds) if conds else None


def capture(args):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    excluded = {
        'user': [int(x) for x in (args.exclude_user or [])],
        'product': [int(x) for x in (args.exclude_product or [])],
    }
    # 用户名/标题层面的夹具过滤
    own_where = {
        'user': "username NOT LIKE 'e2e%'" + (f" AND id NOT IN ({','.join(map(str, excluded['user']))})" if excluded['user'] else ''),
        'product': "title NOT LIKE 'E2E%'" + (f" AND id NOT IN ({','.join(map(str, excluded['product']))})" if excluded['product'] else ''),
    }
    if args.exclude_message:
        rng = args.exclude_message.replace('-', ' AND ')
        own_where['message'] = f'id NOT BETWEEN {rng}' if 'AND' in rng else f'id <> {rng}'

    chunks = [f"""-- 演示数据基线快照
-- 生成时间：{dt.datetime.now():%Y-%m-%d %H:%M:%S}
-- 用途：python tools/seed-demo-data.py --reset
-- 说明：已排除 e2e 夹具""" + (f"；额外排除 user {excluded['user']}" if excluded['user'] else '')
              + (f"、product {excluded['product']}" if excluded['product'] else '')
              + (f"、message {args.exclude_message}" if args.exclude_message else '') + '\n',
              'SET NAMES utf8mb4;',
              'SET FOREIGN_KEY_CHECKS=0;\n']

    for t in TABLES:
        where = own_where.get(t) or fk_filter(t, excluded)
        cmd = [str(MYSQLDUMP), *AUTH, '--no-create-info', '--complete-insert',
               '--skip-extended-insert', '--skip-add-locks', '--compact',
               '--default-character-set=utf8mb4']
        if where:
            cmd.append(f'--where={where}')
        cmd += [DB, t]
        dump = run(cmd)
        rows = dump.count('INSERT INTO')
        chunks.append(f'-- {t}（{rows} 行' + (f'，过滤：{where}' if where else '') + '）')
        chunks.append(dump.rstrip())
        chunks.append('')
    chunks.append('SET FOREIGN_KEY_CHECKS=1;')

    BASELINE.write_text('\n'.join(chunks), encoding='utf-8')
    print(f'  ✓ 基线已生成：{BASELINE}')
    print(f'    文件大小 {BASELINE.stat().st_size/1024:.1f} KB')
    ins = BASELINE.read_text(encoding='utf-8').count('INSERT INTO')
    print(f'    共 {ins} 条 INSERT（--skip-extended-insert 保证一行一条，便于比对）')


def reset(args):
    if not BASELINE.exists():
        print('  ✗ 找不到基线快照，请先执行 --capture')
        sys.exit(2)
    before = counts()
    show_counts('重置前', before)
    if args.dry_run:
        print('\n  （--dry-run：未做任何修改）')
        return

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    backup = OUT_DIR / f'backup-{dt.datetime.now():%Y%m%d-%H%M%S}.sql'
    dump = run([str(MYSQLDUMP), *AUTH, '--no-create-info', '--complete-insert',
                '--skip-extended-insert', '--compact', '--default-character-set=utf8mb4',
                '--databases', DB])
    backup.write_text(dump, encoding='utf-8')
    print(f'\n  ✓ 已自动备份当前数据：{backup.name}（{backup.stat().st_size/1024:.1f} KB）')

    sql = ['SET FOREIGN_KEY_CHECKS=0;']
    for t in DELETE_ORDER:
        sql.append(f'DELETE FROM `{t}`;')
    sql.append('SET FOREIGN_KEY_CHECKS=1;')
    exec_sql('\n'.join(sql))
    print('  ✓ 已清空业务表（外键检查临时关闭）')

    body = BASELINE.read_text(encoding='utf-8')
    exec_sql(body)
    print(f'  ✓ 已导入基线快照：{BASELINE.name}')

    after = counts()
    ok = show_counts('重置后（对照文档不变量）', after)
    print('\n  ' + ('✅ 全部符合不变量，部署验证的数据完整性断言应可恢复全绿'
                    if ok else '⚠️ 仍有偏差，请检查基线快照内容'))
    if not ok:
        sys.exit(1)


def main():
    ap = argparse.ArgumentParser(description='演示数据快照 / 重置')
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument('--capture', action='store_true', help='导出当前库为基线快照')
    g.add_argument('--reset', action='store_true', help='用基线快照重置数据库')
    g.add_argument('--verify', action='store_true', help='只核对行数')
    ap.add_argument('--dry-run', action='store_true', help='只显示将要做什么')
    ap.add_argument('--exclude-user', nargs='*', default=[], help='捕获时排除的用户 id')
    ap.add_argument('--exclude-product', nargs='*', default=[], help='捕获时排除的商品 id')
    ap.add_argument('--exclude-message', default=None, help='捕获时排除的消息 id 或区间，如 300-305')
    args = ap.parse_args()
    if args.capture:
        capture(args)
    elif args.reset:
        reset(args)
    else:
        ok = show_counts('当前行数', counts())
        print('\n  ' + ('✅ 符合不变量' if ok else '⚠️ 存在漂移，可用 --reset 按基线恢复'))
        sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
