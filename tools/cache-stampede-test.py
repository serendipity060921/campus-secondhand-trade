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
    ap = argparse.ArgumentParser(description='缓存击穿实验 + 写路径压测')
    ap.add_argument('--mode', choices=['stampede', 'write'], default='stampede')
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=8080)
    ap.add_argument('--product', type=int, default=6, help='击穿实验用的商品 id')
    ap.add_argument('--concurrency', type=int, default=50)
    ap.add_argument('--rounds', type=int, default=3)
    ap.add_argument('--duration', type=int, default=15)
    args = ap.parse_args()

    if args.mode == 'stampede':
        s = stampede(args.host, args.port, args.product, args.concurrency, args.rounds)
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
