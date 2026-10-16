# -*- coding: utf-8 -*-
"""索引审查：在"同结构、大数据量"的独立库里跑 EXPLAIN（v0.18 / 第1批-④）

为什么不能只看生产库：
    演示库每张表只有 3~33 行，优化器在这个量级下**必然选全表扫描**，
    此时看 EXPLAIN 得到的是"假阳性"（没有索引问题也会显示 ALL）。
    所以这里建一个 **与生产库同结构** 的独立库 campus_trade_perf，
    灌入接近真实的量级（商品 5 万 / 行为 20 万 / 消息 5 万 / 订单 2 万），
    再对**项目真实使用的查询形态**逐条 EXPLAIN —— 这样得到的结论才有意义。

用法：
    python tools/index-review.py              # 建库灌数 + 审查（不改动生产库）
    python tools/index-review.py --keep       # 保留 perf 库便于人工查看
    python tools/index-review.py --explain-only  # 库已存在时跳过重建
产物：控制台报告 + docs/数据库性能与索引审查-数据.json
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
import json
import os
import pathlib
import re
import subprocess
import sys
import time

MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_JSON = ROOT / 'docs' / '数据库性能与索引审查-数据.json'
SRC_DB = 'campus_trade'
PERF_DB = 'campus_trade_perf'
TABLES = ['user', 'category', 'product', 'product_image', 'favorite', 'message',
          'orders', 'user_behavior', 'report', 'admin_log', 'moderation_log']

# 灌数规模：刻意选得比演示库大几个数量级，又不至于跑太久
SCALE = {
    'user': 5000,
    'category': 33,
    'product': 50000,
    'product_image': 100000,
    'favorite': 30000,
    'message': 50000,
    'orders': 20000,
    'user_behavior': 200000,
    'report': 500,
    'admin_log': 2000,
    'moderation_log': 30000,
}

# 项目真实使用的查询形态（与 Mapper / Service 中的写法对齐）
QUERIES = [
    ('商品列表(默认)', 'product',
     "SELECT id,title,price FROM product WHERE deleted=0 AND status=1 ORDER BY create_time DESC LIMIT 12"),
    ('商品列表(分类)', 'product',
     "SELECT id,title,price FROM product WHERE deleted=0 AND status=1 AND category_id=3 ORDER BY create_time DESC LIMIT 12"),
    ('商品列表(价格升序)', 'product',
     "SELECT id,title,price FROM product WHERE deleted=0 AND status=1 ORDER BY price ASC LIMIT 12"),
    ('商品列表(关键词模糊)', 'product',
     "SELECT id,title FROM product WHERE deleted=0 AND status=1 AND title LIKE '%教材%' LIMIT 12"),
    ('商品列表(关键词前缀)', 'product',
     "SELECT id,title FROM product WHERE deleted=0 AND status=1 AND title LIKE '教材%' LIMIT 12"),
    ('商品详情', 'product', "SELECT * FROM product WHERE id=12345 AND deleted=0"),
    ('我的商品', 'product',
     "SELECT id,title FROM product WHERE seller_id=777 AND deleted=0 ORDER BY create_time DESC LIMIT 12"),
    ('待审核商品(管理端)', 'product',
     "SELECT id,title FROM product WHERE deleted=0 AND status=0 ORDER BY create_time DESC LIMIT 20"),
    ('商品图片', 'product_image', "SELECT * FROM product_image WHERE product_id=12345"),
    ('收藏夹列表', 'favorite',
     "SELECT id,product_id FROM favorite WHERE user_id=777 AND deleted=0 ORDER BY create_time DESC LIMIT 20"),
    ('收藏判断(唯一索引)', 'favorite', "SELECT id FROM favorite WHERE user_id=777 AND product_id=12345"),
    ('买家订单列表', 'orders',
     "SELECT id,order_no FROM orders WHERE buyer_id=777 AND deleted=0 ORDER BY create_time DESC LIMIT 10"),
    ('卖家订单(按状态)', 'orders',
     "SELECT id,order_no FROM orders WHERE seller_id=888 AND status=1 AND deleted=0 ORDER BY create_time DESC LIMIT 10"),
    ('订单详情', 'orders', "SELECT * FROM orders WHERE id=9999"),
    ('会话列表(OR 条件)', 'message',
     "SELECT id FROM message WHERE deleted=0 AND (from_user_id=777 OR to_user_id=777) ORDER BY create_time DESC LIMIT 20"),
    ('聊天记录(双方+时间倒序)', 'message',
     "SELECT id,content FROM message WHERE from_user_id=777 AND to_user_id=888 AND deleted=0 ORDER BY create_time DESC LIMIT 20"),
    ('未读私信总数', 'message', "SELECT COUNT(*) FROM message WHERE to_user_id=777 AND is_read=0 AND deleted=0"),
    ('推荐-取用户行为', 'user_behavior',
     "SELECT product_id,behavior_type,weight FROM user_behavior WHERE user_id=777 ORDER BY update_time DESC LIMIT 50"),
    ('推荐-物品相似度', 'user_behavior', "SELECT user_id FROM user_behavior WHERE product_id=12345 LIMIT 200"),
    ('机审按天统计', 'moderation_log',
     "SELECT action, COUNT(*) FROM moderation_log WHERE create_time >= '2026-10-01' GROUP BY action"),
    ('登录按用户名', 'user', "SELECT * FROM user WHERE username='stu_demo' AND deleted=0"),
]


def sql(query, db=None, timeout=600):
    args = [MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
            '--default-character-set=utf8mb4', '-N', '-B']
    if db:
        args.append(db)
    # ★ 每个 sql() 都是独立的 mysql 进程，会话变量无法跨调用保留 ——
    #   因此必须把设置写在**同一条语句串**里。
    #   cte_max_recursion_depth 默认上限是 1000，灌 5000/50000 行会直接失败
    #   （最初只成功灌进 33 行的 category 表，就是这个原因）。
    stmt = 'SET SESSION cte_max_recursion_depth=500000; ' + query
    args += ['-e', stmt]
    r = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', timeout=timeout)
    # ★ 必须过滤掉密码警告：mysql CLI 会把 "Using a password ..." 写到 stderr，
    #   不过滤的话每次正常执行都会被当成失败（本次就是这么踩的）。
    err = '\n'.join(l for l in (r.stderr or '').splitlines()
                    if 'Using a password' not in l).strip()
    return (r.stdout or '').strip(), err


def build_perf_db():
    print(f'  ① 重建 {PERF_DB}（结构与 {SRC_DB} 完全一致，含全部索引）')
    sql(f'DROP DATABASE IF EXISTS {PERF_DB}; CREATE DATABASE {PERF_DB} DEFAULT CHARACTER SET utf8mb4;')
    for t in TABLES:
        sql(f'CREATE TABLE {PERF_DB}.{t} LIKE {SRC_DB}.{t};')
    print(f'    已克隆 {len(TABLES)} 张表')

    print('  ② 灌入接近真实的量级（递归 CTE 批量生成）')
    for t, n in SCALE.items():
        t0 = time.time()
        if t == 'category':
            sql(f'INSERT INTO {PERF_DB}.category (id,parent_id,name,sort_order,deleted,create_time) '
                f'SELECT n, IF(n<=8,0,1+n%8), CONCAT("分类",n), n, 0, NOW() FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'user':
            sql(f'INSERT INTO {PERF_DB}.user (id,username,password,nickname,role,status,credit_score,deleted,create_time) '
                f'SELECT n, CONCAT("perf_user_",n), "$2a$10$x", CONCAT("用户",n), 0, 1, 100, 0, '
                f'DATE_SUB(NOW(), INTERVAL n MINUTE) FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'product':
            sql(f'INSERT INTO {PERF_DB}.product (id,title,description,price,category_id,seller_id,status,'
                f'condition_level,view_count,deleted,create_time) '
                f'SELECT n, CONCAT("教材 第",n,"版 二手转让"), "描述", 10+n%500, 1+n%33, 1+n%5000, '
                f'IF(n%20=0,0,1), 1+n%5, n%1000, 0, DATE_SUB(NOW(), INTERVAL n SECOND) FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'product_image':
            sql(f'INSERT INTO {PERF_DB}.product_image (id,product_id,url,sort_order,deleted,create_time) '
                f'SELECT n, 1+n%50000, CONCAT("/upload/",n,".png"), 0, 0, NOW() FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'favorite':
            sql(f'INSERT INTO {PERF_DB}.favorite (id,user_id,product_id,deleted,create_time) '
                f'SELECT n, 1+n%5000, 1+n%50000, 0, DATE_SUB(NOW(), INTERVAL n SECOND) FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'message':
            sql(f'INSERT INTO {PERF_DB}.message (id,from_user_id,to_user_id,content,type,is_read,deleted,create_time) '
                f'SELECT n, 1+n%5000, 1+(n*7)%5000, "你好，还在吗？", 2, n%2, 0, '
                f'DATE_SUB(NOW(), INTERVAL n SECOND) FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'orders':
            # product_title 是 NOT NULL 且无默认值，必须显式给值（否则 ERROR 1364）
            sql(f'INSERT INTO {PERF_DB}.orders (id,order_no,buyer_id,seller_id,product_id,product_title,'
                f'amount,status,deleted,create_time) '
                f'SELECT n, CONCAT("PERF",n), 1+n%5000, 1+(n*3)%5000, 1+n%50000, CONCAT("商品",n), '
                f'10+n%500, n%4, 0, DATE_SUB(NOW(), INTERVAL n SECOND) FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'user_behavior':
            # ★ 该表有唯一索引 uk_user_product_type(user_id, product_id, behavior_type)，
            #   生成公式必须保证三元组互不重复，否则整条 INSERT 会被回滚（实测就是这样灌进去 0 行）。
            #   下面这组公式在 n < 200000 时是单射：product 用 (n DIV 4) % 50000 铺满，type 用 n % 4 区分。
            sql(f'INSERT INTO {PERF_DB}.user_behavior (id,user_id,product_id,behavior_type,weight,update_time,create_time) '
                f'SELECT n, 1+n%5000, 1+(n DIV 4)%50000, 1+n%4, 1, DATE_SUB(NOW(), INTERVAL n SECOND), NOW() FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'report':
            # 列名是 reason_type（不是 reason）；update_time 为 NOT NULL 无默认值
            sql(f'INSERT INTO {PERF_DB}.report (id,reporter_id,target_type,target_id,reason_type,content,status,deleted,create_time,update_time) '
                f'SELECT n, 1+n%5000, 1+n%2, 1+n%50000, 1+n%4, "违规内容描述", n%3, 0, NOW(), NOW() FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'admin_log':
            sql(f'INSERT INTO {PERF_DB}.admin_log (id,admin_id,action,target_type,target_id,detail,create_time) '
                f'SELECT n, 1, "AUDIT", 1, 1+n%50000, "通过", DATE_SUB(NOW(), INTERVAL n MINUTE) FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
            continue
        if t == 'moderation_log':
            sql(f'INSERT INTO {PERF_DB}.moderation_log (id,seller_id,title,action,risk_rule,hit_count,audit_required,create_time) '
                f'SELECT n, 1+n%5000, CONCAT("商品",n), ELT(1+n%3,"PASS","REVIEW","REJECT"), '
                f'IF(n%5=0,"R2-联系方式",NULL), n%3, n%20=0, DATE_SUB(NOW(), INTERVAL n MINUTE) FROM '
                f'(WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<{n}) SELECT n FROM s) x;')
    print('  ③ ANALYZE TABLE（让优化器拿到真实基数统计）')
    for t in TABLES:
        sql(f'ANALYZE TABLE {PERF_DB}.{t};')

    print('  ④ 校验灌数结果（不允许"以为灌进去了"）')
    bad = 0
    for t, n in SCALE.items():
        out, err = sql(f'SELECT COUNT(*) FROM {PERF_DB}.{t};')
        got = int(out) if out.isdigit() else -1
        mark = '✓' if got >= n * 0.95 else '✗'
        if mark == '✗':
            bad += 1
        print(f'    {mark} {t:<18}期望≈{n:>7}  实际 {got:>7}')
    if bad:
        print(f'    ⚠️ 有 {bad} 张表数量明显偏少，请检查生成语句')
    return bad


def explain(name, db_table, query):
    out, err = sql('EXPLAIN ' + query, PERF_DB)
    if err:
        return {'name': name, 'table': db_table, 'error': err[:200]}
    first = out.splitlines()[0].split('\t') if out else []
    d = dict(zip(['id', 'select_type', 'table', 'partitions', 'type', 'possible_keys',
                  'key', 'key_len', 'ref', 'rows', 'filtered', 'Extra'], first))
    return {
        'name': name, 'table': db_table,
        'type': d.get('type', '?'), 'key': d.get('key') or '(无)',
        'possible_keys': d.get('possible_keys') or '(无)',
        'rows': d.get('rows', '?'), 'extra': d.get('Extra', ''),
        'ok': d.get('type') not in ('ALL',) and not re.search(r'Using filesort|Using temporary', d.get('Extra', '')),
        'sql': query,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--keep', action='store_true')
    ap.add_argument('--explain-only', action='store_true')
    args = ap.parse_args()

    print('=' * 96)
    print('索引审查：同结构 + 大数据量下的 EXPLAIN（生产库不受影响）')
    print('=' * 96)

    if not args.explain_only:
        build_perf_db()

    print('\n  ④ 逐条 EXPLAIN 项目真实使用的查询形态')
    print('  ' + '-' * 92)
    print(f"  {'查询':<24}{'type':<9}{'命中索引':<26}{'扫描行数':>10}  Extra")
    print('  ' + '-' * 92)
    results = []
    for name, table, q in QUERIES:
        r = explain(name, table, q)
        results.append(r)
        if 'error' in r:
            print(f"  {name:<24}EXPLAIN 失败：{r['error'][:50]}")
            continue
        flag = '' if r['ok'] else '  ← 关注'
        print(f"  {name:<24}{r['type']:<9}{r['key']:<26}{r['rows']:>10}  {r['extra'][:30]}{flag}")

    problems = [r for r in results if not r.get('ok') and 'error' not in r]
    print('\n' + '=' * 96)
    print(f'  需关注的查询：{len(problems)} / {len(results)}')
    for r in problems:
        print(f"    · {r['name']}：type={r['type']} key={r['key']} extra={r['extra']}")

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps({'scale': SCALE, 'results': results},
                                   ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'\n  结果已写出：{OUT_JSON}')

    if not args.keep and not args.explain_only:
        print(f'\n  ⑤ 清理 {PERF_DB}（加 --keep 可保留）')
        sql(f'DROP DATABASE IF EXISTS {PERF_DB};')
    return 0


if __name__ == '__main__':
    sys.exit(main())
