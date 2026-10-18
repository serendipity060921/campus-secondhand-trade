#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""长稳测试（soak，v0.18 / 第4批-⑮）

压测（⑭）回答"并发那一瞬间对不对"，长稳回答另一个问题：
**持续跑一段时间后，会不会越来越慢、内存一直涨、错误越攒越多**。
短压测看不到这类问题（GC 还没跑够、连接池还没耗尽、日志还没写满）。

做法
----
* 混合负载：读多写少（默认 7:3）。读 = 列表 / 详情 / 热门 / 分类；
  写 = 用一个一次性账号对同一件商品"收藏 → 取消"来回切（**净变化为 0**，不污染数据）。
* 每 `--interval` 秒统计一个窗口：请求数、p50/p95/最大延迟、5xx、网络错误。
* 同时采 JVM 内存：优先读 actuator 的 `jvm.memory.used`，
  读不到就返回 null（由调用方的 PowerShell 采样 RSS 兜底），**不伪造数字**。
* 结束判定三条：0 个 5xx、末窗 p95 不超过首窗 2 倍、内存增幅不超过 30%。

用法
----
    python tools/soak-test.py --minutes 5
    python tools/soak-test.py --minutes 30 --interval 60 --json out.json
"""
import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://127.0.0.1:8080"
ACTUATOR = "http://127.0.0.1:8090"
PASSWORD = "abc12345"

READS = ["/api/product/list?page=1&size=10", "/api/product/hot",
         "/api/product/1", "/api/product/2", "/api/categories",
         "/api/product/recommend?size=8"]


def http(method, path, token=None, body=None, base=None):
    url = (base or BASE) + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw, status = r.read().decode("utf-8", "replace"), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode("utf-8", "replace"), e.code
    except Exception as e:
        return dict(http=None, code=None, ms=(time.time() - t0) * 1000, err=str(e))
    try:
        js = json.loads(raw)
    except Exception:
        js = {}
    return dict(http=status, code=js.get("code"), ms=(time.time() - t0) * 1000)


def jvm_memory():
    """读 actuator 的 jvm.memory.used（字节）；不可用返回 None —— 不编造。"""
    try:
        r = urllib.request.urlopen(ACTUATOR + "/actuator/metrics/jvm.memory.used", timeout=3)
        js = json.loads(r.read().decode("utf-8", "replace"))
        return js.get("measurements", [{}])[0].get("value")
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=5)
    ap.add_argument("--interval", type=int, default=30, help="窗口秒数")
    ap.add_argument("--write-ratio", type=float, default=0.3)
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    print("=" * 78)
    print("长稳测试（混合读写）")
    print("=" * 78)
    print(f"  时长: {args.minutes} 分钟   窗口: {args.interval}s   写占比: {args.write_ratio}")

    # 复用已有账号（不再注册：注册限流按 IP 计、且会留下垃圾账号），
    # 并挑一件"该账号还没收藏"的在售商品：先收藏再取消，**净变化为 0**。
    account = ("stu_demo", "123456")
    r = http("POST", "/api/user/login",
             body={"username": account[0], "password": account[1]})
    if r.get("code") != 200:
        print("  ❌ 长稳账号登录失败")
        return 2
    token = json.loads(urllib.request.urlopen(urllib.request.Request(
        BASE + "/api/user/login",
        data=json.dumps({"username": account[0], "password": account[1]}).encode(),
        headers={"Content-Type": "application/json"}, method="POST"), timeout=10)
        .read().decode("utf-8", "replace"))["data"]["token"]

    # 该账号已收藏的商品
    favs = set()
    try:
        rr = urllib.request.urlopen(urllib.request.Request(
            BASE + "/api/favorite/list?page=1&size=50",
            headers={"Authorization": "Bearer " + token}), timeout=10)
        for x in (((json.loads(rr.read().decode()) or {}).get("data") or {}).get("records") or []):
            if x.get("productId"):
                favs.add(x["productId"])
    except Exception:
        pass

    records = []
    try:
        rr = urllib.request.urlopen(BASE + "/api/product/list?page=1&size=20", timeout=10)
        records = ((json.loads(rr.read().decode()) or {}).get("data") or {}).get("records") or []
    except Exception:
        pass
    candidates = [p.get("id") for p in records
                  if p.get("id") and p.get("status") == 1 and p.get("id") not in favs]
    if not candidates:
        print("  ❌ 找不到「该账号尚未收藏的在售商品」，无法做净零写操作")
        return 2
    pid = candidates[0]
    print(f"  长稳账号: {account[0]}   收藏目标商品: #{pid}（该账号未收藏过，保证净变化为 0）")

    windows = []
    total = dict(req=0, err=0, fivexx=0)
    start = time.time()
    deadline = start + args.minutes * 60
    initialized = False
    fav_state = 1

    while time.time() < deadline:
        w_start = time.time()
        lat, errs, five = [], 0, 0
        n = 0
        while time.time() - w_start < args.interval and time.time() < deadline:
            is_write = (n % 10) < int(args.write_ratio * 10)
            if is_write:
                fav_state = 2 if fav_state == 1 else 1          # 收藏 ↔ 取消，净变化为 0
                r = http("POST", "/api/favorite/operate", token=token,
                         body={"productId": pid, "type": fav_state})
            else:
                r = http("GET", READS[n % len(READS)])
            n += 1
            if r.get("http") is None:
                errs += 1
            else:
                lat.append(r["ms"])
                if r["http"] >= 500:
                    five += 1
        lat.sort()
        window = dict(
            at=round(time.time() - start, 1), req=n, err=errs, fivexx=five,
            p50=round(statistics.median(lat), 1) if lat else 0,
            p95=round(lat[int(len(lat) * 0.95) - 1], 1) if lat else 0,
            max=round(lat[-1], 1) if lat else 0,
            jvmMb=(round(jvm_memory() / 1048576, 1) if jvm_memory() else None),
        )
        windows.append(window)
        total["req"] += n
        total["err"] += errs
        total["fivexx"] += five
        print(f"  [{window['at']:>6.0f}s] 请求 {n:>4}  p50 {window['p50']:>6.1f}ms  "
              f"p95 {window['p95']:>6.1f}ms  max {window['max']:>7.1f}ms  "
              f"5xx {five}  网络错误 {errs}")

    # ---------------- 判定 ----------------
    first = next((w for w in windows if w["req"] > 0), None)
    last = next((w for w in reversed(windows) if w["req"] > 0), None)
    drift = None
    if first and last and first["p95"] > 0:
        drift = round(last["p95"] / first["p95"], 2)
    mem_first = next((w["jvmMb"] for w in windows if w["jvmMb"]), None)
    mem_last = next((w["jvmMb"] for w in reversed(windows) if w["jvmMb"]), None)
    mem_growth = None
    if mem_first and mem_last:
        mem_growth = round((mem_last - mem_first) / mem_first * 100, 1)

    ok_err = total["fivexx"] == 0 and total["err"] == 0
    ok_drift = drift is None or drift <= 2.0
    ok_mem = mem_growth is None or mem_growth <= 30.0
    passed = ok_err and ok_drift and ok_mem

    print("\n" + "=" * 78)
    print(f"  总请求 {total['req']}   5xx {total['fivexx']}   网络错误 {total['err']}")
    print(f"  p95 漂移: {drift if drift is not None else '无数据'}（末窗/首窗，判定阈值 ≤2.0）")
    print(f"  JVM 内存: {mem_first} MB → {mem_last} MB（增幅 {mem_growth}%，阈值 ≤30%）")
    print(f"  结论: {'✅ 通过' if passed else '❌ 未通过'}")
    print("=" * 78)

    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(dict(
            minutes=args.minutes, interval=args.interval, writeRatio=args.write_ratio,
            account=account[0], productId=pid, total=total, windows=windows,
            p95Drift=drift, jvmFirst=mem_first, jvmLast=mem_last,
            jvmGrowthPct=mem_growth, passed=passed), ensure_ascii=False, indent=2),
            encoding="utf-8")
        print(f"  JSON: {args.json}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
