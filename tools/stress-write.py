#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""写路径多账号压测（v0.18 / 第4批-⑭）

为什么需要"多账号"
------------------
本项目所有写接口都按**账号维度**限流（发布 10/300s、下单 10/300s、收藏 60/60s）。
单账号压测很快就会撞到 429，测出来的是"限流器"，不是"写路径"。
所以这里注册一批一次性账号，把并发压力摊到多个账号上。

四个场景
--------
S1 **并发抢购同一件商品 → 必须恰好 1 单成交**（★ 最重要）
   下单用的是条件更新（`update product set status=4 where id=? and status=1`），
   靠 `locked == 1` 判定是否抢到。6 个账号同时抢，应当只有 1 个成功，其余拿到
   `6001 商品已下架或已被预订`。这是"不超卖"的直接证据。

S2 **同一账号并发重复收藏 → 恰好 1 条记录**
   靠"先查再插 + 数据库唯一索引 uk_user_product"双保险；
   并发下冲突应转成业务码 4001，而不是 500。

S3 **限流按账号生效，不是全局**
   3 个账号各发 5 件商品全部成功 → 说明限流键含账号；
   再由 1 个账号连发 12 次 → 必须出现 429 → 说明单账号上限确实生效。

S4 **并发下写接口不出现 5xx**
   统计所有请求的 HTTP 状态与业务码，5xx 必须为 0。

数据卫生
--------
本脚本会**创建真实数据**（新账号、商品、订单）。跑完请执行：

    python tools/seed-demo-data.py --reset

用法
----
    python tools/stress-write.py                    # 6 个买家并发抢购
    python tools/stress-write.py --buyers 10 --repeat 3
    python tools/stress-write.py --json out.json
"""
import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ADMIN = ("admin", "123456")
SELLER = ("stu_test01", "abc12345")     # 已在演示数据里，负责发布待抢商品
PASSWORD = "abc12345"
CATEGORY_ID = 1


def http(base, method, path, token=None, body=None, params=None):
    url = base.rstrip("/") + path
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw, status = r.read().decode("utf-8", "replace"), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode("utf-8", "replace"), e.code
    except Exception as e:
        return dict(http=None, code=None, ms=(time.time() - t0) * 1000, err=str(e))
    try:
        body_json = json.loads(raw)
    except Exception:
        body_json = {"raw": raw[:120]}
    return dict(http=status, code=body_json.get("code"), body=body_json,
                ms=(time.time() - t0) * 1000)


def login(base, user):
    r = http(base, "POST", "/api/user/login",
             body={"username": user[0], "password": user[1]})
    if r["code"] != 200:
        return None
    return (r["body"].get("data") or {}).get("token")


def register_and_login(base, username):
    http(base, "POST", "/api/user/register",
         body={"username": username, "password": PASSWORD, "nickname": "压测" + username[-3:]})
    return login(base, (username, PASSWORD))


def stats_of(results):
    ok = [r for r in results if r["code"] == 200]
    ms = sorted(r["ms"] for r in results)
    codes = {}
    http_codes = {}
    for r in results:
        codes[r["code"]] = codes.get(r["code"], 0) + 1
        http_codes[r["http"]] = http_codes.get(r["http"], 0) + 1
    return dict(
        total=len(results), ok=len(ok),
        p50=round(statistics.median(ms), 1) if ms else 0,
        p95=round(ms[int(len(ms) * 0.95) - 1], 1) if ms else 0,
        max=round(ms[-1], 1) if ms else 0,
        codes=codes, http_codes=http_codes,
        server_errors=sum(1 for r in results if (r["http"] or 0) >= 500),
    )


def publish(base, token, title):
    return http(base, "POST", "/api/product/publish", token=token, body={
        "title": title,
        "description": "多账号写路径压测自动创建，跑完后会 reset 演示数据",
        "categoryId": CATEGORY_ID,
        "price": 1.00,
        "conditionLevel": 1,
        "campus": "东校区",
        "tradePlace": "图书馆门口",
    })


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--buyers", type=int, default=6, help="并发抢购的账号数")
    ap.add_argument("--repeat", type=int, default=1, help="S1 重复轮数（每轮用新商品）")
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    report = dict(base=args.base, started=time.strftime("%Y-%m-%d %H:%M:%S"), scenarios={})
    print("=" * 78)
    print("写路径多账号压测")
    print("=" * 78)
    print(f"  目标: {args.base}   并发买家: {args.buyers}   抢购轮数: {args.repeat}")

    admin_tok = login(args.base, ADMIN)
    seller_tok = login(args.base, SELLER)
    if not admin_tok or not seller_tok:
        print("  ❌ 管理员或卖家登录失败，先确认服务与演示数据")
        return 2

    # 注册一批一次性买家账号（注册限流 10/300s，按需串行注册）
    stamp = int(time.time()) % 100000
    buyers = []
    for i in range(args.buyers):
        name = f"perf{stamp}_{i}"
        tok = register_and_login(args.base, name)
        if tok:
            buyers.append(dict(username=name, token=tok))
    print(f"  注册并登录买家账号: {len(buyers)} / {args.buyers}")
    if len(buyers) < 2:
        print("  ❌ 可用账号不足，无法验证并发抢购")
        return 2

    # ---------------- S1 并发抢购：必须恰好 1 单成交 ----------------
    print("\n--- S1 并发抢购同一件商品（期望恰好 1 单成功）---")
    s1_rounds = []
    for rnd in range(args.repeat):
        pr = publish(args.base, seller_tok, f"压测-抢购-{stamp}-{rnd}")
        pid = (pr["body"].get("data") or {}).get("id")
        if pr["code"] != 200 or not pid:
            print(f"  第{rnd + 1}轮发布失败: {pr['code']} {pr['body'].get('message')}")
            continue
        # 发布后是待审核，需管理员通过才可下单
        # 发布后可能是待审核(0)，也可能被机审直接放行上架(1)。
        # 8003 = "只有待审核状态的商品才能审核"，说明机审已放行 —— 这不是错误。
        ar = http(args.base, "PUT", "/api/admin/product/audit", token=admin_tok,
                  body={"productId": pid, "approve": True})
        if ar["code"] not in (200, 8003):
            print(f"  第{rnd + 1}轮审核失败: {ar['code']} {ar['body'].get('message')}")
            continue

        barrier = threading.Barrier(len(buyers))

        def grab(b):
            barrier.wait()
            return http(args.base, "POST", "/api/order/create", token=b["token"],
                        body={"productId": pid})

        with ThreadPoolExecutor(max_workers=len(buyers)) as pool:
            results = list(pool.map(grab, buyers))

        s = stats_of(results)
        # 成交数：用每个买家的"我买到的"里是否出现该商品来判定（比只看 code 更硬）
        winners = 0
        for b in buyers:
            bl = http(args.base, "GET", "/api/order/buyList", token=b["token"],
                      params={"page": 1, "size": 20})
            recs = ((bl["body"].get("data") or {}).get("records") or [])
            if any(x.get("productId") == pid for x in recs):
                winners += 1

        prod = http(args.base, "GET", f"/api/product/{pid}")
        pstatus = ((prod["body"] or {}).get("data") or {}).get("status")

        ok = (winners == 1)
        print(f"  第{rnd + 1}轮 商品#{pid}: 成功code={s['ok']} 成交={winners} "
              f"商品状态={pstatus} p95={s['p95']}ms 业务码={s['codes']} 5xx={s['server_errors']} "
              f"{'✅' if ok else '❌ 超卖或没卖出去'}")
        s1_rounds.append(dict(productId=pid, ok_code=s["ok"], winners=winners,
                              productStatus=pstatus, stats=s))

    report["scenarios"]["S1_并发抢购"] = s1_rounds

    # ---------------- S2 同账号并发重复收藏 ----------------
    print("\n--- S2 同一账号并发重复收藏（期望恰好 1 条记录）---")
    pr = publish(args.base, seller_tok, f"压测-收藏-{stamp}")
    pid2 = (pr["body"].get("data") or {}).get("id")
    http(args.base, "PUT", "/api/admin/product/audit", token=admin_tok,
         body={"productId": pid2, "approve": True})
    target = buyers[0]
    barrier2 = threading.Barrier(20)

    def fav_same(_):
        barrier2.wait()
        return http(args.base, "POST", "/api/favorite/operate", token=target["token"],
                    body={"productId": pid2, "type": 1})

    with ThreadPoolExecutor(max_workers=20) as pool:
        fav_results = list(pool.map(fav_same, range(20)))

    s2 = stats_of(fav_results)
    detail = http(args.base, "GET", f"/api/product/{pid2}")
    fav_count = ((detail["body"] or {}).get("data") or {}).get("favoriteCount")
    ok2 = (s2["ok"] == 1 and s2["server_errors"] == 0)
    print(f"  20 次并发收藏: 成功={s2['ok']} 收藏数={fav_count} "
          f"业务码={s2['codes']} 5xx={s2['server_errors']} {'✅' if ok2 else '❌'}")
    report["scenarios"]["S2_并发重复收藏"] = dict(stats=s2, favoriteCount=fav_count, ok=ok2)

    # ---------------- S3 限流按账号生效 ----------------
    print("\n--- S3 限流按账号生效（3 账号 × 5 件应全成功；单账号 12 件应出现 429）---")
    per_account = []
    for b in buyers[:3]:
        barrier3 = threading.Barrier(5)

        def pub_one(_i, who=b):
            barrier3.wait()
            return publish(args.base, who["token"], f"压测-限流-{stamp}-{who['username'][-2:]}")

        with ThreadPoolExecutor(max_workers=5) as pool:
            rs = list(pool.map(pub_one, range(5)))
        st = stats_of(rs)
        per_account.append(dict(username=b["username"], ok=st["ok"], codes=st["codes"],
                                server_errors=st["server_errors"]))
        print(f"  {b['username']}: 成功 {st['ok']}/5  业务码={st['codes']} 5xx={st['server_errors']}")

    # 单账号连发 12 次（限流 10/300s）→ 应出现 429
    burst = [publish(args.base, buyers[0]["token"], f"压测-爆限流-{stamp}-{i}") for i in range(12)]
    burst_codes = {}
    for r in burst:
        key = r["code"]
        burst_codes[key] = burst_codes.get(key, 0) + 1
    hit_429 = burst_codes.get(429, 0) > 0
    print(f"  单账号连发 12 次: 业务码={burst_codes} {'✅ 触发限流' if hit_429 else '❌ 未触发限流'}")
    report["scenarios"]["S3_限流维度"] = dict(perAccount=per_account,
                                              burstCodes=burst_codes, hit429=hit_429)

    # ---------------- S4 5xx 汇总 ----------------
    all_server_errors = sum(x["stats"]["server_errors"] for x in s1_rounds) + \
        s2["server_errors"] + sum(x["server_errors"] for x in per_account) + \
        sum(1 for r in burst if (r["http"] or 0) >= 500)
    print(f"\n--- S4 5xx 汇总: {all_server_errors} 次 {'✅' if all_server_errors == 0 else '❌'} ---")
    report["scenarios"]["S4_5xx总数"] = all_server_errors

    # ---------------- 结论 ----------------
    s1_ok = all(x["winners"] == 1 for x in s1_rounds) and bool(s1_rounds)
    s3_ok = all(x["ok"] == 5 for x in per_account) and hit_429
    passed = s1_ok and ok2 and s3_ok and all_server_errors == 0
    report["passed"] = passed

    print("\n" + "=" * 78)
    print(f"  结论: {'✅ 全部符合预期' if passed else '❌ 存在不符合预期的项'}")
    print(f"  · S1 并发抢购不超卖: {'✅' if s1_ok else '❌'}")
    print(f"  · S2 并发重复收藏幂等: {'✅' if ok2 else '❌'}")
    print(f"  · S3 限流按账号生效: {'✅' if s3_ok else '❌'}")
    print(f"  · S4 无 5xx: {'✅' if all_server_errors == 0 else '❌'}")
    print("  提醒: 本次压测创建了真实数据，请执行 python tools/seed-demo-data.py --reset")
    print("=" * 78)

    if args.json:
        from pathlib import Path
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                   encoding="utf-8")
        print(f"  JSON: {args.json}")

    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
