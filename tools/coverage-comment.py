#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 JaCoCo 报告转成「人话」覆盖率摘要（v0.18 / 第5批-⑳）

为什么需要它
------------
CI 里 `./mvnw -B package` 失败时只会给一行 "Rule violated: ratio covered is 0.79"，
看不出**差多少、哪个包拖后腿**；而覆盖率棘轮（pom 里的 minimum）本身是个数字，
评审的人不会去翻 XML。

这个脚本做两件事，用同一份 markdown：
1. **始终**写入 `$GITHUB_STEP_SUMMARY` —— 任何一次 CI 运行都能在页面上直接看到覆盖率；
2. 在 PR 上由 `marocchino/sticky-pull-request-comment` 贴成**粘性评论**（多次提交只保留一条）。

口径（与 docs/测试覆盖率说明.md 一致）
------------------------------------
* 只统计 JaCoCo 的 INSTRUCTION / BRANCH 两个计数器；
* 阈值来自 `backend/pom.xml` 的 `<minimum>`，脚本**读文件而不是硬编码**，
  避免改了棘轮却忘了改脚本；
* 未覆盖最多的包按 INSTRUCTION_MISSED 降序，最多列 8 个。

用法
----
    python3 tools/coverage-comment.py                    # 写 coverage-comment.md
    python3 tools/coverage-comment.py --summary          # 同时写 $GITHUB_STEP_SUMMARY
    python3 tools/coverage-comment.py --csv backend/target/site/jacoco/jacoco.csv \
                                      --pom backend/pom.xml --out coverage-comment.md
"""
import argparse
import csv
import os
import re
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def read_ratchet(pom_path):
    """从 pom.xml 的 jacoco check 配置里读出 INSTRUCTION 的最低覆盖率。"""
    try:
        text = Path(pom_path).read_text(encoding="utf-8")
    except Exception:
        return None
    # 只认 <limit> 段里的 INSTRUCTION / COVEREDRATIO 组合
    for m in re.finditer(r"<limit>(.*?)</limit>", text, re.S):
        block = m.group(1)
        if "<counter>INSTRUCTION</counter>" in block:
            mm = re.search(r"<minimum>\s*([\d.]+)\s*</minimum>", block)
            if mm:
                return float(mm.group(1))
    return None


def load(csv_path):
    rows = []
    with Path(csv_path).open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            try:
                rows.append(dict(
                    pkg=r["PACKAGE"], cls=r["CLASS"],
                    im=int(r["INSTRUCTION_MISSED"]), ic=int(r["INSTRUCTION_COVERED"]),
                    bm=int(r["BRANCH_MISSED"]), bc=int(r["BRANCH_COVERED"]),
                ))
            except (KeyError, ValueError):
                continue
    return rows


def pct(covered, total):
    return (covered / total * 100.0) if total else 100.0


def build(rows, ratchet):
    im = sum(r["im"] for r in rows)
    ic = sum(r["ic"] for r in rows)
    bm = sum(r["bm"] for r in rows)
    bc = sum(r["bc"] for r in rows)
    ins = pct(ic, im + ic)
    br = pct(bc, bm + bc)

    lines = []
    lines.append("## 📊 后端覆盖率（JaCoCo）")
    lines.append("")

    verdict = ""
    if ratchet is not None:
        delta = ins - ratchet * 100.0
        sign = "↑" if delta >= 0 else "↓"
        verdict = f"{delta:+.2f}pp"
        status = "✅ 通过" if delta >= 0 else "❌ 低于棘轮"
        lines.append(f"**{status}** ｜ 指令覆盖率 **{ins:.2f}%**"
                     f"（棘轮 {ratchet * 100:.0f}%，{sign} {verdict}）")
    else:
        lines.append(f"**指令覆盖率 {ins:.2f}%**（未能从 pom 读到棘轮阈值）")
    lines.append("")

    lines.append("| 指标 | 数值 |")
    lines.append("| --- | ---: |")
    lines.append(f"| 指令覆盖率 | **{ins:.2f}%**（{ic:,} / {im + ic:,}） |")
    lines.append(f"| 分支覆盖率 | **{br:.2f}%**（{bc:,} / {bm + bc:,}） |")
    if ratchet is not None:
        lines.append(f"| 棘轮阈值（pom） | {ratchet * 100:.0f}% |")
    lines.append("")

    # 按包聚合未覆盖最多的
    pkgs = {}
    for r in rows:
        p = pkgs.setdefault(r["pkg"], dict(im=0, ic=0))
        p["im"] += r["im"]
        p["ic"] += r["ic"]
    worst = sorted(pkgs.items(), key=lambda kv: -kv[1]["im"])[:8]
    lines.append("### 未覆盖最多的包（下一步该补哪里）")
    lines.append("")
    lines.append("| 包 | 覆盖率 | 未覆盖指令 |")
    lines.append("| --- | ---: | ---: |")
    for name, v in worst:
        if v["im"] == 0:
            continue
        short = name.replace("com.campus.trade.", "") or name
        lines.append(f"| `{short}` | {pct(v['ic'], v['im'] + v['ic']):.2f}% | {v['im']:,} |")
    lines.append("")
    lines.append("<sub>由 CI 自动生成 · 统计口径与规则见 <code>docs/测试覆盖率说明.md</code></sub>")
    return "\n".join(lines), ins, br


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="backend/target/site/jacoco/jacoco.csv")
    ap.add_argument("--pom", default="backend/pom.xml")
    ap.add_argument("--out", default="coverage-comment.md")
    ap.add_argument("--summary", action="store_true", help="同时写入 $GITHUB_STEP_SUMMARY")
    args = ap.parse_args()

    csv_path = Path(args.csv)
    if not csv_path.exists():
        msg = ("## 📊 后端覆盖率（JaCoCo）\n\n"
               f"⚠️ 未找到 `{csv_path}` —— 通常是上一步（测试/打包）失败导致没有生成报告。\n"
               "请先看同一 job 里失败的那一步。")
        Path(args.out).write_text(msg + "\n", encoding="utf-8")
        print(msg)
        if args.summary and os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
                f.write(msg + "\n")
        return 0            # 覆盖率报告缺失不应再让 job 多失败一次

    rows = load(csv_path)
    if not rows:
        print("jacoco.csv 里没有有效数据")
        return 1

    ratchet = read_ratchet(args.pom)
    md, ins, br = build(rows, ratchet)
    Path(args.out).write_text(md + "\n", encoding="utf-8")
    print(md)

    if args.summary and os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(md + "\n")
        print(f"\n[已写入] $GITHUB_STEP_SUMMARY")

    print(f"\n[汇总] 指令 {ins:.2f}%  分支 {br:.2f}%  棘轮 {ratchet}  输出 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
