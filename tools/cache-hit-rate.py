#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""缓存命中率实测（v0.18 / 第4批-⑯）

思路：`CacheServiceImpl` 每累计 500 次缓存读会在应用日志里打一行

    [缓存统计] 累计读=500 命中=498 未命中=2 错误=0 命中率=99.6%

本脚本先**预热**（把热点商品详情灌进缓存），再**制造命中为主的流量**，
最后从日志里读回那一行统计 —— 这样"缓存有效性"就有了实测数字，
而不是只写一句"已启用缓存"。

用法：
    python tools/cache-hit-rate.py                 # 打 127.0.0.1:8080
    python tools/cache-hit-rate.py --requests 1200 --hot 4
    python tools/cache-hit-rate.py --log deploy/windows/logs/campus-trade.log
"""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

STAT_LINE = re.compile(
    r"\[缓存统计\]\s*累计读=(\d+)\s*命中=(\d+)\s*未命中=(\d+)\s*错误=(\d+)\s*命中率=([\d.]+)%")


def get(base, path, params=""):
    url = base.rstrip("/") + path + (("?" + params) if params else "")
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            return e.code, None
    except Exception as e:
        return None, {"error": str(e)}


def find_log(explicit):
    """找到应用日志：优先用显式路径，否则在常见位置里挑最新的 .log。

    注意：prod 下 logback 会把日志写到 campus-trade.log，而 nohup/重定向产生的
    backend.out.log 可能同时存在且时间戳相近 —— 因此优先含 "campus-trade" 的文件名，
    否则很容易读到不含业务日志的那一个。
    """
    if explicit:
        p = Path(explicit)
        return p if p.exists() else None
    cands = []
    for pattern in ("deploy/windows/logs/*.log", "backend/logs/*.log", "logs/*.log"):
        cands.extend(Path(".").glob(pattern))
    cands = [c for c in cands if c.is_file()]
    if not cands:
        return None
    preferred = [c for c in cands if "campus-trade" in c.name]
    pool = preferred if preferred else cands
    return max(pool, key=lambda p: p.stat().st_mtime)


def last_stat(log_path):
    """从日志尾部读最后一条统计行（只读末尾 200KB，避免大文件卡住）。"""
    if log_path is None:
        return None
    size = log_path.stat().st_size
    with log_path.open("rb") as f:
        if size > 200_000:
            f.seek(size - 200_000)
        text = f.read().decode("utf-8", "replace")
    hits = STAT_LINE.findall(text)
    if not hits:
        return None
    total, hit, miss, err, rate = hits[-1]
    return dict(total=int(total), hit=int(hit), miss=int(miss), err=int(err), rate=float(rate))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--requests", type=int, default=1200, help="总请求数（需 >= 500 才会触发统计行）")
    ap.add_argument("--hot", type=int, default=4, help="热点商品数量（越小命中率越高）")
    ap.add_argument("--log", default="", help="应用日志路径（默认自动查找）")
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    print("=" * 78)
    print("缓存命中率实测")
    print("=" * 78)
    print(f"  目标: {args.base}   请求数: {args.requests}   热点商品: {args.hot}")

    # 1) 发现商品 ID
    status, body = get(args.base, "/api/products", "page=1&size=20")
    records = ((body or {}).get("data") or {}).get("records") or []
    ids = [r.get("id") for r in records if r.get("id")]
    if not ids:
        print("  ⚠️ 没有可用的商品，先用 seed-demo-data.py --reset 造数据")
        return 2
    ids = ids[: max(1, args.hot)]
    print(f"  热点商品 ID: {ids}")

    # 商品详情（★ 走缓存的接口是 /api/product/{id}；
    # 脚手架的 /api/products/{id} 直接查库，用它测命中率会得到 0 次缓存读）
    detail_path = "/api/product/{}"

    # 2) 预热：每个热点商品先读一次，让缓存里一定有值
    for pid in ids:
        get(args.base, detail_path.format(pid))
    time.sleep(0.2)

    # 3) 制造以命中为主的流量
    started = time.time()
    errors = 0
    for i in range(args.requests):
        pid = ids[i % len(ids)]
        st, _ = get(args.base, detail_path.format(pid))
        if st is None or st >= 500:
            errors += 1
    elapsed = time.time() - started
    qps = args.requests / elapsed if elapsed > 0 else 0.0
    print(f"  压测完成: {args.requests} 次请求 / {elapsed:.2f}s ≈ {qps:.0f} qps，HTTP 5xx/网络错误 {errors} 次")

    # 4) 从日志读回统计
    log_path = find_log(args.log)
    print(f"  日志文件: {log_path}")
    stat = last_stat(log_path)
    if stat is None:
        print("  ⚠️ 日志里还没有 [缓存统计] 行：可能请求数不足 500，或日志级别高于 INFO")
        return 3

    print("-" * 78)
    print(f"  ★ 实测命中率: {stat['rate']}%   （命中 {stat['hit']} / 未命中 {stat['miss']} / 错误 {stat['err']}）")
    print("-" * 78)
    print("  口径说明：")
    print("  · 未命中包含「命中空值缓存」（防穿透的 NULL 标记），它同样没有业务数据；")
    print("  · Redis 异常单独计数，不计入命中率分母；")
    print("  · 计数是单实例进程内计数，重启清零。")

    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(
            dict(base=args.base, requests=args.requests, hot=ids, qps=round(qps, 1),
                 errors=errors, stats=stat), ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  JSON: {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
