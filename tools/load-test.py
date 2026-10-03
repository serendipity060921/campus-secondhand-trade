# -*- coding: utf-8 -*-
"""校园二手交易平台 v0.12 缓存效果压测脚本

目的：对比"开缓存 / 关缓存"两种配置下同一批接口的吞吐与延迟，
      并统计数据库实际执行的语句数（Questions），量化缓存对数据库的减压效果。

方法：
  · 每个场景用 N 个线程持续压 D 秒（线程内复用 HTTP 长连接，避免连接建立开销干扰）；
  · 统计 QPS、平均/P50/P95/P99/最大 延迟、错误数；
  · 通过 MySQL `SHOW GLOBAL STATUS LIKE 'Questions'` 前后差值算出数据库 QPS。

用法：
  python tools/load-test.py --label cache-on                    # 当前配置（缓存开启）
  python tools/load-test.py --label cache-off                   # 需先用 --campus.cache.enabled=false 启动后端
  python tools/load-test.py --label cache-on --duration 15 --concurrency 20

产物：tools/load-test-<label>.json
"""
import argparse
import http.client
import json
import pathlib
import statistics
import subprocess
import threading
import time

MYSQL = r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe'
OUT_DIR = pathlib.Path(__file__).parent

SCENARIOS = [
    ('首页商品列表', '/api/product/list?page=1&size=12'),
    ('商品详情', '/api/product/6'),
    ('猜你喜欢(推荐)', '/api/product/recommend?size=8'),
    ('分类列表', '/api/category/list'),
]


def mysql_questions():
    """数据库累计执行语句数（用于计算数据库 QPS）"""
    try:
        r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-P', '3306', '-u', 'root', '-p123456',
                            '-N', '-B', '-e', "SHOW GLOBAL STATUS LIKE 'Questions'"],
                           capture_output=True, text=True, encoding='utf-8', timeout=30)
        return int((r.stdout or '0\t0').strip().split('\t')[-1])
    except Exception:  # noqa: BLE001
        return 0


class Worker(threading.Thread):
    """单个压测线程：复用一条 HTTP 长连接，持续请求直到时间到"""

    def __init__(self, host, port, path, deadline, latencies, errors, lock):
        super().__init__(daemon=True)
        self.host, self.port, self.path = host, port, path
        self.deadline = deadline
        self.latencies, self.errors, self.lock = latencies, errors, lock

    def run(self):
        conn = http.client.HTTPConnection(self.host, self.port, timeout=15)
        local_lat, local_err = [], 0
        while time.time() < self.deadline:
            start = time.perf_counter()
            try:
                conn.request('GET', self.path)
                resp = conn.getresponse()
                resp.read()
                if resp.status != 200:
                    local_err += 1
            except Exception:  # noqa: BLE001
                local_err += 1
                try:
                    conn.close()
                except Exception:  # noqa: BLE001
                    pass
                conn = http.client.HTTPConnection(self.host, self.port, timeout=15)
            local_lat.append((time.perf_counter() - start) * 1000)
        with self.lock:
            self.latencies.extend(local_lat)
            self.errors[0] += local_err
        try:
            conn.close()
        except Exception:  # noqa: BLE001
            pass


def run_scenario(host, port, name, path, duration, concurrency):
    """跑一个场景，返回指标字典"""
    latencies, errors, lock = [], [0], threading.Lock()
    deadline = time.time() + duration
    db_before = mysql_questions()
    start = time.time()
    workers = [Worker(host, port, path, deadline, latencies, errors, lock) for _ in range(concurrency)]
    for w in workers:
        w.start()
    for w in workers:
        w.join()
    elapsed = time.time() - start
    db_after = mysql_questions()

    lat = sorted(latencies) or [0]
    return dict(scenario=name, path=path, concurrency=concurrency, duration=round(elapsed, 2),
                requests=len(latencies), qps=round(len(latencies) / elapsed, 1),
                avg_ms=round(statistics.mean(lat), 2),
                p50_ms=round(lat[int(len(lat) * 0.50)], 2),
                p95_ms=round(lat[min(len(lat) - 1, int(len(lat) * 0.95))], 2),
                p99_ms=round(lat[min(len(lat) - 1, int(len(lat) * 0.99))], 2),
                max_ms=round(lat[-1], 2),
                errors=errors[0],
                db_questions_delta=db_after - db_before,
                db_qps=round((db_after - db_before) / elapsed, 1))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--label', default='run')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--duration', type=int, default=15, help='每个场景压测秒数')
    parser.add_argument('--concurrency', type=int, default=20, help='并发线程数')
    args = parser.parse_args()

    print('=' * 104)
    print(f'v0.12 压测 [{args.label}]：每个场景 {args.duration} 秒 × {args.concurrency} 并发（HTTP 长连接）')
    print('=' * 104)
    results = []
    for name, path in SCENARIOS:
        r = run_scenario(args.host, args.port, name, path, args.duration, args.concurrency)
        results.append(r)
        print(f"  {name:16s} QPS={r['qps']:>8} 平均={r['avg_ms']:>7}ms P95={r['p95_ms']:>7}ms "
              f"P99={r['p99_ms']:>7}ms 请求={r['requests']:>6} 错误={r['errors']:>3} "
              f"DB语句={r['db_questions_delta']:>6} (DB QPS≈{r['db_qps']})")

    total_requests = sum(r['requests'] for r in results)
    total_time = sum(r['duration'] for r in results)
    total_db = sum(r['db_questions_delta'] for r in results)
    summary = dict(label=args.label, duration=args.duration, concurrency=args.concurrency,
                   total_requests=total_requests, total_seconds=round(total_time, 2),
                   overall_qps=round(total_requests / total_time, 1),
                   total_db_questions=total_db, overall_db_qps=round(total_db / total_time, 1),
                   avg_ms=round(statistics.mean([r['avg_ms'] for r in results]), 2),
                   scenarios=results, generated_at=time.strftime('%Y-%m-%d %H:%M:%S'))
    print('-' * 104)
    print(f"  合计：请求 {total_requests}，平均 QPS {summary['overall_qps']}，"
          f"数据库语句 {total_db}（DB QPS≈{summary['overall_db_qps']}），平均延迟 {summary['avg_ms']}ms")

    out = OUT_DIR / f'load-test-{args.label}.json'
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'  结果已保存：{out}')


if __name__ == '__main__':
    main()
