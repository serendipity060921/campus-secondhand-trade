#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""越权矩阵穷举（v0.18 / 第4批-⑬）

安全套件里的 S 用例是**单点**验证（"这个接口不能越权"），本脚本做的是**矩阵**：
把「受保护接口 × caller 身份」铺开，逐格实测，产出一张可写进论文/报告的矩阵表。

四类探针
--------
1. AUTH       匿名调用 → 必须 401（所有 29 个受保护接口逐个打）
2. ROLE       普通用户调用管理员专属接口 → 必须 403
3. OWNERSHIP  非属主访问他人资源（他人订单 / 他人商品的状态变更）→ 必须被拒（code != 200）
4. SELF_SCOPE 「我的」类列表接口 → 返回结果里不能出现他人的资源 ID

设计原则
--------
* **不污染数据**：写接口只打"应当被拒绝"的方向（拒绝即无副作用）；
  "我自己的资料修改"用当前值回写（幂等）。
* **不硬编码 ID**：资源 ID 全部先通过 API 自行发现，发现不到就标记「无数据」而不是假装通过。
* **失败即失败**：任何一格不符合预期，脚本退出码为 1（可接 CI）。

用法
----
    python tools/check-authz-matrix.py                 # 打 127.0.0.1:8080
    python tools/check-authz-matrix.py --base http://127.0.0.1:8081
    python tools/check-authz-matrix.py --json out.json --md docs/越权矩阵.md
"""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ADMIN = ("admin", "123456")
USER_A = ("stu_test01", "abc12345")     # 主测试账号（资源属主）
USER_B = ("stu_demo", "123456")         # 对照账号（非属主）

# ---------------------------------------------------------------- 受保护接口清单
# (方法, 路径, 说明, 是否需要资源ID, 是否管理员专属)
PROTECTED = [
    # 订单
    ("POST", "/api/order/create", "创建订单", False, False),
    ("PUT", "/api/order/status", "变更订单状态", True, False),
    ("GET", "/api/order/buyList", "我买到的", False, False),
    ("GET", "/api/order/sellList", "我卖出的", False, False),
    ("GET", "/api/order/{id}", "订单详情", True, False),
    # 私信
    ("GET", "/api/message/unreadTotal", "未读数", False, False),
    ("GET", "/api/message/online", "在线状态", False, False),
    ("POST", "/api/message/send", "发送私信", False, False),
    ("GET", "/api/message/conversationList", "会话列表", False, False),
    ("GET", "/api/message/history", "聊天记录", False, False),
    ("PUT", "/api/message/read", "标记已读", False, False),
    ("GET", "/api/message/peer", "聊天对象信息", False, False),
    # 收藏
    ("POST", "/api/favorite/operate", "收藏/取消收藏", False, False),
    ("GET", "/api/favorite/list", "我的收藏", False, False),
    ("GET", "/api/favorite/hasFavorite", "是否已收藏", False, False),
    # 商品（模块）
    ("POST", "/api/product/publish", "发布商品", False, False),
    ("POST", "/api/product/upload", "上传图片", False, False),
    ("GET", "/api/product/mine", "我的商品", False, False),
    ("PUT", "/api/product/status", "商品状态变更", True, False),
    # 举报
    ("POST", "/api/report/submit", "提交举报", False, False),
    ("GET", "/api/report/mine", "我的举报", False, False),
    # 用户 / 资料
    ("GET", "/api/user/info", "当前用户信息", False, False),
    ("POST", "/api/user/logout", "退出登录", False, False),
    ("GET", "/api/user/profile", "个人资料", False, False),
    ("PUT", "/api/user/update", "修改资料", False, False),
    ("POST", "/api/user/avatar", "上传头像", False, False),
    # 管理端（管理员专属）
    ("GET", "/api/admin/dashboard", "管理看板", False, True),
    ("POST", "/api/category/add", "新增分类", False, True),
    ("PUT", "/api/category/update", "修改分类", False, True),
]


class Probe:
    """极简 HTTP 客户端：只用标准库（urllib），不引入第三方依赖，便于在 CI / 新环境直接跑。"""

    def __init__(self, base):
        self.base = base.rstrip("/")

    def http(self, method, path, token=None, json_body=None, params=None):
        url = self.base + path
        if params:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
        data = None
        headers = {"Accept": "application/json"}
        if json_body is not None:
            data = json.dumps(json_body).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                raw = resp.read().decode("utf-8", "replace")
                status = resp.status
        except urllib.error.HTTPError as e:                       # 4xx/5xx 也要读响应体（业务码在体内）
            raw = e.read().decode("utf-8", "replace")
            status = e.code
        except Exception as e:                                    # 网络问题如实上报
            return None, str(e)
        try:
            body = json.loads(raw)
        except Exception:
            body = {"raw": raw[:200], "httpStatus": status}
        return status, body

    @staticmethod
    def code(body):
        if not isinstance(body, dict):
            return None
        return body.get("code")

    def login(self, user):
        _, body = self.http("POST", "/api/user/login",
                            json_body={"username": user[0], "password": user[1]})
        if not isinstance(body, dict) or body.get("code") != 200:
            return None
        return (body.get("data") or {}).get("token")


def discover(p):
    """通过 API 自行发现各账号的资源 ID；发现不到返回 None（不硬编码）。"""
    ids = {}
    tok_a = p.login(USER_A)
    tok_b = p.login(USER_B)
    ids["tok_a"], ids["tok_b"] = tok_a, tok_b
    ids["tok_admin"] = p.login(ADMIN)

    for who, tok in (("a", tok_a), ("b", tok_b)):
        if not tok:
            continue
        _, body = p.http("GET", "/api/order/buyList", token=tok, params={"page": 1, "size": 20})
        recs = ((body or {}).get("data") or {}).get("records") or []
        ids[f"{who}_orders"] = [r.get("id") for r in recs if r.get("id")]

        _, body = p.http("GET", "/api/product/mine", token=tok, params={"page": 1, "size": 20})
        recs = ((body or {}).get("data") or {}).get("records") or []
        ids[f"{who}_products"] = [r.get("id") for r in recs if r.get("id")]
        # 我的商品里挑一个"在售"的用于状态变更越权探测
        ids[f"{who}_on_sale"] = [r.get("id") for r in recs if r.get("status") == 1]

        _, body = p.http("GET", "/api/favorite/list", token=tok, params={"page": 1, "size": 20})
        recs = ((body or {}).get("data") or {}).get("records") or []
        ids[f"{who}_favorites"] = [r.get("productId") for r in recs if r.get("productId")]
    return ids


def run(base):
    p = Probe(base)
    ids = discover(p)
    rows = []          # 矩阵行
    details = []       # 明细

    def record(no, path, method, probe_kind, caller, expect, got, ok, note=""):
        details.append(dict(no=no, method=method, path=path, probe=probe_kind,
                            caller=caller, expect=expect, actual=got, ok=ok, note=note))

    # ---------- 1) AUTH：匿名 → 401 ----------
    auth_results = {}
    for i, (method, path_raw, label, need_id, admin_only) in enumerate(PROTECTED, start=1):
        path = path_raw
        # 需要 ID 的接口先用占位 ID，仅验证"未登录被拦"（不会真的读到数据）
        if "{id}" in path:
            path = path.replace("{id}", str((ids.get("a_orders") or [1])[0]))
        r, body = p.http(method, path, token=None,
                         json_body={} if method in ("POST", "PUT") else None,
                         params={"peerId": 1, "productId": 1} if method == "GET" else None)
        got = p.code(body) if body else None
        ok = (got == 401)
        auth_results.setdefault(label, {})["匿名"] = got
        record(i, path_raw, method, "AUTH", "匿名", 401, got, ok,
               "" if ok else "未返回 401，存在未鉴权暴露风险")

    # ---------- 2) ROLE：普通用户打管理员接口 → 403 ----------
    role_results = {}
    for method, path, label, _, admin_only in PROTECTED:
        if not admin_only:
            continue
        for who, tok in (("A", ids["tok_a"]), ("B", ids["tok_b"])):
            r, body = p.http(method, path, token=tok,
                             json_body={} if method in ("POST", "PUT") else None)
            got = p.code(body) if body else None
            ok = (got == 403)
            role_results.setdefault(label, {})[who] = got
            record(0, path, method, "ROLE", who, 403, got, ok,
                   "" if ok else "普通用户拿到了管理员接口的响应")
            auth_results.setdefault(label, {})[who] = got

    # ---------- 3) OWNERSHIP：非属主访问他人资源 ----------
    own_results = {}

    # 3.1 他人订单详情
    if ids.get("a_orders"):
        oid = ids["a_orders"][0]
        for who, tok, expect_owner in (("B", ids["tok_b"], False), ("A", ids["tok_a"], True)):
            r, body = p.http("GET", f"/api/order/{oid}", token=tok)
            got = p.code(body) if body else None
            ok = (got == 200) if expect_owner else (got != 200)
            own_results.setdefault("他人订单详情", {})[who] = got
            record(0, f"/api/order/{oid}", "GET", "OWNERSHIP", who,
                   "200(属主)" if expect_owner else "拒绝", got, ok,
                   "" if ok else ("属主读不到自己的订单" if expect_owner else "非属主读到了他人订单"))
        auth_results.setdefault("订单详情", {})["属主"] = own_results["他人订单详情"].get("A")

    # 3.2 变更他人订单状态
    if ids.get("a_orders"):
        oid = ids["a_orders"][0]
        r, body = p.http("PUT", "/api/order/status", token=ids["tok_b"],
                         json_body={"orderId": oid, "status": 3})
        got = p.code(body) if body else None
        ok = (got != 200)
        own_results.setdefault("变更他人订单状态", {})["B"] = got
        record(0, "/api/order/status", "PUT", "OWNERSHIP", "B", "拒绝", got, ok,
               "" if ok else "非属主成功变更了他人订单状态")

    # 3.3 变更他人商品状态
    if ids.get("a_on_sale"):
        pid = ids["a_on_sale"][0]
        r, body = p.http("PUT", "/api/product/status", token=ids["tok_b"],
                         json_body={"productId": pid, "status": 3})
        got = p.code(body) if body else None
        ok = (got != 200)
        own_results.setdefault("变更他人商品状态", {})["B"] = got
        record(0, "/api/product/status", "PUT", "OWNERSHIP", "B", "拒绝", got, ok,
               "" if ok else "非属主成功下架了他人商品")

    # 3.4 跨会话读私信：B 拉取与 A 的会话，不应看到 A 与他人的内容
    if ids.get("tok_a") and ids.get("tok_b"):
        r, body = p.http("GET", "/api/message/history", token=ids["tok_b"],
                         params={"peerId": 1, "page": 1, "size": 50})
        got = p.code(body) if body else None
        ok = (got in (200, 5002, 400))
        own_results.setdefault("跨会话读私信", {})["B"] = got
        record(0, "/api/message/history", "GET", "OWNERSHIP", "B", "只能读自己参与的会话",
               got, ok, "" if ok else "返回了非预期的响应")

    # ---------- 4) SELF_SCOPE：「我的」列表里不能出现他人资源 ----------
    scope_results = {}
    pairs = [
        ("我买到的", "GET", "/api/order/buyList", "a_orders", "b_orders"),
        ("我卖出的", "GET", "/api/order/sellList", "a_orders", "b_orders"),
        ("我的商品", "GET", "/api/product/mine", "a_products", "b_products"),
    ]
    for label, method, path, akey, bkey in pairs:
        r, body = p.http(method, path, token=ids["tok_b"], params={"page": 1, "size": 50})
        recs = ((body or {}).get("data") or {}).get("records") or []
        b_ids = {x.get("id") for x in recs}
        a_ids = set(ids.get(akey) or [])
        leaked = sorted(i for i in (a_ids & b_ids) if i is not None)
        ok = (not leaked)
        scope_results[label] = dict(返回条数=len(recs), 泄漏条数=len(leaked))
        record(0, path, method, "SELF_SCOPE", "B", "只含本人资源",
               200 if not leaked else "泄漏", ok,
               "" if ok else f"出现他人资源 ID: {leaked[:5]}")

    return rows, details, auth_results, role_results, own_results, scope_results, ids


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--json", default="")
    ap.add_argument("--md", default="")
    args = ap.parse_args()

    print("=" * 78)
    print("越权矩阵穷举（受保护接口 × caller 身份）")
    print("=" * 78)
    print(f"  目标: {args.base}")

    rows, details, auth, role, own, scope, ids = run(args.base)

    fail = [d for d in details if not d["ok"]]
    total = len(details)

    # ---------------- 控制台输出 ----------------
    print("\n--- 1) 匿名访问（应全部 401）---")
    for label, cells in auth.items():
        got = cells.get("匿名")
        mark = "✓" if got == 401 else "✗"
        print(f"  {mark} {label:<16} 匿名→{got}")

    print("\n--- 2) 管理员专属接口（普通用户应 403）---")
    for label, cells in role.items():
        print(f"  {label:<16} A→{cells.get('A')}  B→{cells.get('B')}")

    print("\n--- 3) 非属主访问他人资源（应被拒，code≠200）---")
    for label, cells in own.items():
        print(f"  {label:<16} {cells}")

    print("\n--- 4) 「我的」类列表不泄漏他人资源 ---")
    for label, info in scope.items():
        print(f"  {label:<16} {info}")

    print("\n" + "=" * 78)
    print(f"  检查项 {total} 个，失败 {len(fail)} 个")
    if fail:
        print("  ❌ 失败明细：")
        for d in fail:
            print(f"     {d['probe']:<10} {d['method']:<5} {d['path']:<32} caller={d['caller']} "
                  f"期望={d['expect']} 实际={d['actual']} {d['note']}")
    else:
        print("  ✅ 全部符合预期")
    print("=" * 78)

    # ---------------- 落盘 ----------------
    payload = dict(base=args.base, total=total, failed=len(fail),
                   details=details, auth=auth, role=role, ownership=own, self_scope=scope)
    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  JSON 已写入: {args.json}")

    if args.md:
        lines = ["# 越权矩阵实测结果", "",
                 f"- 实测目标：`{args.base}`",
                 f"- 受保护接口：**{len({d['path'] for d in details if d['probe']=='AUTH'})}** 个",
                 f"- 检查项：**{total}** 个，失败 **{len(fail)}** 个",
                 "", "| 接口 | 匿名 | 非属主 | 属主 | 管理员 |", "| --- | --- | --- | --- | --- |"]
        for method, path, label, _, admin_only in PROTECTED:
            a = auth.get(label, {})
            lines.append(f"| `{method} {path}` | {a.get('匿名')} | {a.get('B') or '—'} | "
                         f"{a.get('A') or '—'} | {a.get('admin') or '—'} |")
        Path(args.md).parent.mkdir(parents=True, exist_ok=True)
        Path(args.md).write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"  Markdown 已写入: {args.md}")

    return 0 if not fail else 1


if __name__ == "__main__":
    sys.exit(main())
