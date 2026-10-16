# -*- coding: utf-8 -*-
"""缓存击穿实验 + 写路径压测（C1）

背景：项目已用「互斥重建（分布式锁）」防缓存击穿 —— 热点 key 过期瞬间，
      只允许一个请求去查库并回填，其余请求等待复用，而不是一起把数据库打穿。
      本脚本用可测量的方式证明它真的生效。

一、击穿实验（--mode stampede，默认）
    步骤：① 预热该商品详情缓存
          ② 删除该 key（模拟热点 key 恰好过期）
          ③ N 个线程在同一个栅栏后**同时**发起请求（真正的并发冲击）
          ④ 用 MySQL `Questions` 增量衡量"这 N 个并发到底打了几次数据库"
    判读：互斥重建生效 → 增量 ≈ 1；若失效 → 增量 ≈ N（数据库被瞬间打穿）

二、写路径压测（--mode write）
    对「收藏 / 取消收藏」做循环压测（写库 + 唯一索引 + 缓存失效联动），
    统计 QPS、延迟分位值与错误数；结束后清理本脚本产生的收藏记录。

用法：
  <python> tools/cache-stampede-test.py --mode stampede --product 6 --concurrency 50 --rounds 3
  <python> tools/cache-stampede-test.py --mode write --duration 15 --concurrency 10
产物：tools/perf-<mode>.json
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
import http.client
import json
import os
import pathlib
import re
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request

MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
REDIS_CLI = os.environ.get('REDIS_CLI', r'D:\major\tool\redis\redis-cli.exe')
OUT_DIR = pathlib.Path(__file__).parent

SELLER = ('stu_test01', 'abc12345')
BUYER = ('stu_demo', '123456')
PREFIX = 'campus'


# ---------------------------------------------------------------- 基础设施

def q(sql):
    r = subprocess.run([MYSQL, '-h127.0.0.1', '-uroot', '-p123456', '-N', '-B', 'campus_trade', '-e', sql],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    return (r.stdout or '').strip()


def db_questions():
    """MySQL 累计执行语句数（用它衡量"打了几次库"）"""
    out = q("SHOW GLOBAL STATUS LIKE 'Questions'")
    return int(out.split()[-1])


def redis_del(key):
    r = subprocess.run([REDIS_CLI, '-h', '127.0.0.1', '-p', '6379', 'DEL', key],
                       capture_output=True, text=True, encoding='utf-8', timeout=30)
    return (r.stdout or '').strip()


def redis_exists(key):
    r = subprocess.run([REDIS_CLI, '-h', '127.0.0.1', '-p', '6379', 'EXISTS', key],
                       capture_output=True, text=True, encoding='utf-8', timeout=30)
    return (r.stdout or '').strip() == '1'


def http_get(host, port, path, timeout=15):
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.request('GET', path)
        resp = conn.getresponse()
        body = resp.read()
        return resp.status, body
    finally:
        conn.close()


def http_post(host, port, path, payload=None, token=None, timeout=15):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(f'http://{host}:{port}{path}',
                                 data=json.dumps(payload or {}).encode(),
                                 headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode() or '{}')
        except Exception:
            return {'code': e.code}


def login(host, port, username, password):
    r = http_post(host, port, '/api/user/login', {'username': username, 'password': password})
    return (r.get('data') or {}).get('token')


# ---------------------------------------------------------------- 击穿实验

def single_rebuild_cost(host, port, product_id, key, path):
    """测量「一次缓存重建」会消耗多少条数据库语句（作为判读基线）。

    注意：一次重建往往不止一条 SQL（商品 + 卖家 + 图片等），
    因此不能直接用"增量==1"来判断是否被击穿，必须先测出单次重建的成本。
    """
    redis_del(key)
    before = db_questions()
    http_get(host, port, path)
    after = db_questions()
    return max(1, after - before)


def stampede(host, port, product_id, concurrency, rounds):
    key = f'{PREFIX}:product:detail:{product_id}'
    path = f'/api/product/{product_id}'
    all_rounds = []

    print('=' * 96)
    print(f'缓存击穿实验：商品 {product_id}，{concurrency} 并发同时冲击「刚过期」的热点 key，共 {rounds} 轮')
    print(f'  Redis key = {key}')
    print('=' * 96)

    # ⓪ 预热进程与连接池（数据丢弃），避免 JVM/连接池冷启动污染第一轮
    print('  预热中（本轮数据丢弃）…', end='', flush=True)
    for _ in range(3):
        http_get(host, port, path)
    print('完成')

    # ① 基线：一次缓存重建的数据库成本
    baseline = single_rebuild_cost(host, port, product_id, key, path)
    print(f'  基线：一次缓存重建 ≈ {baseline} 条数据库语句（重建会查商品、卖家、图片等多张表）')
    print('-' * 96)

    for i in range(1, rounds + 1):
        # ② 预热：确保是"有缓存"的状态
        http_get(host, port, path)
        warm = redis_exists(key)

        # ③ 删除 key，模拟热点 key 恰好过期
        redis_del(key)
        deleted = not redis_exists(key)

        # ④ 并发冲击：所有线程在同一个栅栏后同时出发
        barrier = threading.Barrier(concurrency)
        latencies, errors, statuses = [], [0], []
        lock = threading.Lock()

        def worker():
            try:
                barrier.wait(timeout=15)          # 等所有线程就位
                t0 = time.perf_counter()
                status, _ = http_get(host, port, path)
                dt = (time.perf_counter() - t0) * 1000
                with lock:
                    latencies.append(dt)
                    statuses.append(status)
            except Exception:
                with lock:
                    errors[0] += 1

        db_before = db_questions()
        threads = [threading.Thread(target=worker) for _ in range(concurrency)]
        t_start = time.perf_counter()
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        elapsed = time.perf_counter() - t_start
        db_after = db_questions()

        lat = sorted(latencies) or [0]
        delta = db_after - db_before
        # 用基线换算"实际发生了几次缓存重建"
        rebuilds = round(delta / baseline)
        rec = dict(round=i, concurrency=concurrency,
                   requests=len(latencies), errors=errors[0],
                   db_questions_delta=delta,
                   baseline_per_rebuild=baseline,
                   rebuilds=rebuilds,
                   elapsed_ms=round(elapsed * 1000, 1),
                   avg_ms=round(statistics.mean(lat), 2),
                   p50_ms=round(lat[len(lat) // 2], 2),
                   p95_ms=round(lat[min(len(lat) - 1, int(len(lat) * 0.95))], 2),
                   max_ms=round(lat[-1], 2),
                   http_ok=sum(1 for s in statuses if s == 200),
                   warm_cache_before=warm, key_deleted=deleted,
                   refilled_after=redis_exists(key))
        all_rounds.append(rec)

        verdict = '✅ 互斥重建生效（只重建 1 次）' if rebuilds <= 1 else f'⚠️ 发生了 {rebuilds} 次重建'
        print(f"  第 {i} 轮  请求={rec['requests']:>3}  错误={rec['errors']:>2}  "
              f"DB语句={delta:>4}  ≈{rebuilds:>2} 次重建  总耗时={rec['elapsed_ms']:>7}ms  "
              f"P95={rec['p95_ms']:>7}ms  最大={rec['max_ms']:>7}ms  {verdict}")

    rebuilds_list = [r['rebuilds'] for r in all_rounds]
    avg_rebuilds = statistics.mean(rebuilds_list)
    summary = dict(mode='stampede', product_id=product_id, concurrency=concurrency, rounds=rounds,
                   redis_key=key,
                   baseline_per_rebuild=baseline,
                   rebuilds_each_round=rebuilds_list,
                   rebuilds_avg=round(avg_rebuilds, 2),
                   db_delta_each_round=[r['db_questions_delta'] for r in all_rounds],
                   naive_expectation=concurrency,
                   protection_ratio=round(concurrency / max(1, avg_rebuilds), 1),
                   details=all_rounds,
                   generated_at=time.strftime('%Y-%m-%d %H:%M:%S'))
    print('-' * 96)
    print(f"  结论：{concurrency} 个并发同时冲击同一个过期 key，实际只发生 "
          f"{summary['rebuilds_avg']} 次缓存重建（无互斥保护时期望为 {concurrency} 次）")
    print(f"        重复查库的次数降低了约 {summary['protection_ratio']} 倍")
    return summary


# ---------------------------------------------------------------- 多热点键击穿实验

def hot_product_ids(limit):
    """从库里挑 limit 个「在售」商品 id 当热点 key（避免硬编码不存在的 id）"""
    rows = q(f'SELECT id FROM product WHERE deleted = 0 AND status = 1 ORDER BY id LIMIT {limit};')
    ids = []
    for line in str(rows).splitlines():
        line = line.strip()
        if line.isdigit():
            ids.append(int(line))
    return ids


LOG_PATH = pathlib.Path(os.environ.get(
    'APP_LOG', r'D:\campus-secondhand-trade\deploy\windows\logs\campus-trade.log'))


def log_offset():
    """记录日志当前长度，作为"只看这次实验新增日志"的起点"""
    try:
        return LOG_PATH.stat().st_size
    except OSError:
        return 0


def rebuilds_from_log(offset):
    """从日志增量里逐 key 统计缓存重建次数。

    应用在把商品详情写进缓存时会打 `[缓存重建] 商品详情 productId=N key=...` ——
    有了它就能**精确**统计每个 key 重建了几次，而不是靠数据库语句增量估算（估算有噪声）。
    没有这条日志时返回 None，脚本会退回估算口径。
    """
    if not LOG_PATH.exists():
        return None
    counts = {}
    try:
        with open(LOG_PATH, 'r', encoding='utf-8', errors='replace') as f:
            f.seek(offset)
            for line in f:
                m = re.search(r'\[缓存重建\] 商品详情 productId=(\d+)', line)
                if m:
                    pid = int(m.group(1))
                    counts[pid] = counts.get(pid, 0) + 1
    except OSError:
        return None
    return counts if counts else None


def burst(host, port, key_paths, per_key):
    """对多组 (key, path) 同时发起 per_key 个并发请求（共享同一个栅栏后一起出发）。

    返回总耗时、DB 语句增量、错误数，以及每个 key 的延迟列表。
    """
    barrier = threading.Barrier(len(key_paths) * per_key)
    lat = {p: [] for _, p in key_paths}
    errors = [0]
    lock = threading.Lock()

    def worker(path):
        try:
            barrier.wait(timeout=20)                 # 所有线程就位后同时出发
            t0 = time.perf_counter()
            status, _ = http_get(host, port, path)
            dt = (time.perf_counter() - t0) * 1000
            with lock:
                lat[path].append(dt)
                if status != 200:
                    errors[0] += 1
        except Exception:
            with lock:
                errors[0] += 1

    db_before = db_questions()
    threads = []
    for _, path in key_paths:
        threads += [threading.Thread(target=worker, args=(path,)) for _ in range(per_key)]
    t_start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = (time.perf_counter() - t_start) * 1000
    db_after = db_questions()
    return dict(elapsed_ms=round(elapsed, 1), db_delta=db_after - db_before,
                errors=errors[0], latencies={k: sorted(v) for k, v in lat.items()})


def multi_key_stampede(host, port, key_count, per_key, rounds):
    """★ 多热点键击穿实验：验证互斥锁是**按 key** 的，而不是一把全局锁。

    判别方法（关键设计）：
      · 对照组 T1：**1 个** key，起 `key_count × per_key` 个并发（总并发与实验组一致）
      · 实验组 TN：**key_count 个** key，每个起 `per_key` 个并发
      · 若锁按 key 分（不同 key 可并行重建）→ TN ≈ T1
        若是一把全局锁（重建被串行化）      → TN ≈ key_count × T1
    同时用 DB 语句增量 / 单次重建成本，验证"每个 key 只重建 1 次"（合计 ≈ key_count 次，
    而不是 key_count × per_key 次）。
    """
    ids = hot_product_ids(key_count)
    if len(ids) < key_count:
        print(f'  ✗ 在售商品不足 {key_count} 个（只找到 {len(ids)} 个），无法做多键实验')
        return dict(mode='multi', error='not_enough_products', found=len(ids))

    total_concurrency = key_count * per_key
    paths = [f'/api/product/{i}' for i in ids]
    keys = [f'{PREFIX}:product:detail:{i}' for i in ids]

    print('=' * 96)
    print(f'多热点键击穿实验：{key_count} 个热点商品，总并发 {total_concurrency}（每键 {per_key}），共 {rounds} 轮')
    print(f'  商品 id = {ids}')
    print(f'  Redis keys = {keys}')
    print('  判别标准：实验组耗时 ≈ 单键对照组 → 锁按 key 分（可并行重建）；'
          f'≈ {key_count} 倍 → 存在全局锁')
    print('=' * 96)

    # 预热（丢弃）
    print('  预热中（本轮数据丢弃）…', end='', flush=True)
    for p in paths:
        for _ in range(2):
            http_get(host, port, p)
    print('完成')

    # 基线：一次重建的数据库成本
    baseline = single_rebuild_cost(host, port, ids[0], keys[0], paths[0])
    print(f'  基线：一次缓存重建 ≈ {baseline} 条数据库语句')
    print('-' * 96)

    records = []
    for i in range(1, rounds + 1):
        # ---------- 对照组 T1：单键 + 全部并发 ----------
        for p in paths:
            http_get(host, port, p)                # 预热
        redis_del(keys[0])
        t1 = burst(host, port, [(keys[0], paths[0])], total_concurrency)
        t1_rebuilds = round(t1['db_delta'] / baseline)

        # ---------- 实验组 TN：多键 + 每键并发 ----------
        for p in paths:
            http_get(host, port, p)                # 预热
        for k in keys:
            redis_del(k)                           # 全部 key 同时"过期"
        log_at = log_offset()                      # ★ 记录日志起点，用于逐 key 精确统计
        tn = burst(host, port, list(zip(keys, paths)), per_key)
        by_log = rebuilds_from_log(log_at)
        tn_rebuilds = round(tn['db_delta'] / baseline)
        ratio = round(tn['elapsed_ms'] / max(1.0, t1['elapsed_ms']), 2)

        # 日志口径优先（精确）；没有日志锚点时才用数据库增量估算
        exact = by_log if by_log else {}
        exact_total = sum(exact.values()) if exact else None
        max_per_key = max(exact.values()) if exact else None

        rec = dict(round=i, key_count=key_count, per_key=per_key,
                   total_concurrency=total_concurrency,
                   baseline_per_rebuild=baseline,
                   single_key=dict(elapsed_ms=t1['elapsed_ms'], db_delta=t1['db_delta'],
                                   rebuilds=t1_rebuilds, errors=t1['errors']),
                   multi_key=dict(elapsed_ms=tn['elapsed_ms'], db_delta=tn['db_delta'],
                                  rebuilds=tn_rebuilds, errors=tn['errors'],
                                  rebuilds_by_log=exact,
                                  rebuilds_total_log=exact_total,
                                  max_rebuilds_per_key=max_per_key,
                                  http_ok=sum(len(v) for v in tn['latencies'].values()) - tn['errors']),
                   elapsed_ratio_multi_over_single=ratio,
                   naive_expectation_per_key=per_key,
                   protection_ratio_per_key=round(per_key / max(1, (exact_total or tn_rebuilds) / key_count), 1))
        records.append(rec)

        verdict = ('✅ 锁按 key 分：多键并行重建' if ratio < 2.0
                   else f'⚠️ 多键耗时是单键的 {ratio} 倍 —— 疑似存在全局锁')
        log_info = (f"  日志逐键={exact} 合计={exact_total} 单键最多={max_per_key}"
                    if exact else '  （日志无重建锚点，用 DB 增量估算）')
        print(f"  第 {i} 轮  单键(T1)={t1['elapsed_ms']:>7}ms/{t1_rebuilds} 次重建   "
              f"多键(TN)={tn['elapsed_ms']:>7}ms/{tn_rebuilds} 次重建   TN/T1={ratio:>5}  {verdict}")
        print(f"          {log_info.strip()}")

    avg_ratio = round(statistics.mean([r['elapsed_ratio_multi_over_single'] for r in records]), 2)
    avg_rebuilds = round(statistics.mean([r['multi_key']['rebuilds'] for r in records]), 2)
    log_totals = [r['multi_key']['rebuilds_total_log'] for r in records
                  if r['multi_key']['rebuilds_total_log'] is not None]
    max_per_key_all = [r['multi_key']['max_rebuilds_per_key'] for r in records
                       if r['multi_key']['max_rebuilds_per_key'] is not None]
    avg_rebuilds_log = round(statistics.mean(log_totals), 2) if log_totals else None
    worst_per_key = max(max_per_key_all) if max_per_key_all else None

    summary = dict(mode='multi', key_count=key_count, per_key=per_key,
                   total_concurrency=total_concurrency, rounds=rounds,
                   product_ids=ids, redis_keys=keys, baseline_per_rebuild=baseline,
                   avg_rebuilds=avg_rebuilds, expected_rebuilds=key_count,
                   avg_rebuilds_from_log=avg_rebuilds_log,
                   max_rebuilds_per_key_observed=worst_per_key,
                   avg_elapsed_ratio=avg_ratio,
                   per_key_protection=round(per_key / max(1, avg_rebuilds_log or avg_rebuilds) , 1)
                   if (avg_rebuilds_log or avg_rebuilds) else None,
                   details=records, generated_at=time.strftime('%Y-%m-%d %H:%M:%S'))
    print('-' * 96)
    if avg_rebuilds_log is not None:
        print(f"  结论（日志口径，精确）：{key_count} 个热点 key、每键 {per_key} 并发，"
              f"合计只发生 {avg_rebuilds_log} 次重建（期望恰好 {key_count} 次）；"
              f"任一 key 单轮最多重建 {worst_per_key} 次")
    else:
        print(f"  结论（DB 增量口径）：{key_count} 个热点 key、每键 {per_key} 并发，"
              f"合计约 {avg_rebuilds} 次重建（期望 {key_count} 次；无互斥时期望 {total_concurrency} 次）")
    print(f"        多键总耗时是单键的 {avg_ratio} 倍 —— "
          + ('说明不同 key 的重建是**并行**的（锁按 key 分，而非全局锁）' if avg_ratio < 2.0
             else '说明重建被串行化，需检查锁粒度'))
    return summary


# ---------------------------------------------------------------- 写路径压测

def write_path(host, port, duration, concurrency):
    token = login(host, port, *BUYER)
    if not token:
        print('  ✗ 登录失败，无法做写路径压测')
        return None

    # 选一个在售商品做收藏/取消循环（不改商品状态，只增删 favorite 行）
    out = q("SELECT id FROM product WHERE status = 1 AND deleted = 0 ORDER BY id LIMIT 1")
    product_id = int(out.splitlines()[0])

    def one_cycle():
        r1 = http_post(host, port, '/api/favorite/operate', {'productId': product_id}, token)
        r2 = http_post(host, port, '/api/favorite/operate', {'productId': product_id}, token)
        return r1.get('code'), r2.get('code')

    print('=' * 96)
    print(f'写路径压测：收藏/取消循环（商品 {product_id}，{duration} 秒 × {concurrency} 并发）')
    print(f'  每个循环 = 1 次收藏写库 + 1 次取消写库 + 2 次缓存失效')
    print('=' * 96)

    latencies, errors, codes, lock = [], [0], [], threading.Lock()
    deadline = time.time() + duration
    db_before = db_questions()

    def worker():
        while time.time() < deadline:
            t0 = time.perf_counter()
            try:
                c1, c2 = one_cycle()
                dt = (time.perf_counter() - t0) * 1000
                with lock:
                    latencies.append(dt)
                    codes.extend([c1, c2])
            except Exception:
                with lock:
                    errors[0] += 1

    threads = [threading.Thread(target=worker) for _ in range(concurrency)]
    start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - start
    db_after = db_questions()

    lat = sorted(latencies) or [0]
    summary = dict(mode='write', product_id=product_id, concurrency=concurrency,
                   duration=round(elapsed, 2), cycles=len(latencies),
                   cycles_per_second=round(len(latencies) / elapsed, 1),
                   avg_ms=round(statistics.mean(lat), 2),
                   p95_ms=round(lat[min(len(lat) - 1, int(len(lat) * 0.95))], 2),
                   max_ms=round(lat[-1], 2), errors=errors[0],
                   ok_responses=sum(1 for c in codes if c == 200),
                   other_codes=sorted({c for c in codes if c != 200}),
                   db_questions_delta=db_after - db_before,
                   db_qps=round((db_after - db_before) / elapsed, 1),
                   generated_at=time.strftime('%Y-%m-%d %H:%M:%S'))

    print(f"  循环数={summary['cycles']}  每秒={summary['cycles_per_second']}  "
          f"平均={summary['avg_ms']}ms  P95={summary['p95_ms']}ms  最大={summary['max_ms']}ms")
    print(f"  成功响应={summary['ok_responses']}  其他返回码={summary['other_codes']}  错误={summary['errors']}")
    print(f"  DB 语句增量={summary['db_questions_delta']}（DB QPS≈{summary['db_qps']}）")

    # 清理：本脚本产生的收藏记录（保证演示数据不变量不被破坏）
    q(f"DELETE FROM favorite WHERE product_id = {product_id} AND user_id = "
      f"(SELECT id FROM user WHERE username = '{BUYER[0]}')")
    left = q(f"SELECT COUNT(*) FROM favorite WHERE product_id = {product_id}")
    print(f'  已清理本次压测产生的收藏记录（该商品剩余收藏 {left} 条）')
    return summary


def main():
    ap = argparse.ArgumentParser(description='缓存击穿实验 + 多热点键实验 + 写路径压测')
    ap.add_argument('--mode', choices=['stampede', 'multi', 'write'], default='stampede')
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=8080)
    ap.add_argument('--product', type=int, default=6, help='单键击穿实验用的商品 id')
    ap.add_argument('--concurrency', type=int, default=50)
    ap.add_argument('--keys', type=int, default=5, help='多热点键实验的热点 key 个数')
    ap.add_argument('--per-key', type=int, default=10, help='多热点键实验每个 key 的并发数')
    ap.add_argument('--rounds', type=int, default=3)
    ap.add_argument('--duration', type=int, default=15)
    args = ap.parse_args()

    if args.mode == 'stampede':
        s = stampede(args.host, args.port, args.product, args.concurrency, args.rounds)
    elif args.mode == 'multi':
        s = multi_key_stampede(args.host, args.port, args.keys, args.per_key, args.rounds)
        if s.get('error'):
            return 1
    else:
        s = write_path(args.host, args.port, args.duration, args.concurrency)
        if s is None:
            return 1

    out = OUT_DIR / f"perf-{args.mode}.json"
    out.write_text(json.dumps(s, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'  结果已保存：{out}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
