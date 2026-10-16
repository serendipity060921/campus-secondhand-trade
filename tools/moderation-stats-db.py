# -*- coding: utf-8 -*-
"""机审效果统计·落库版（C3）

数据来源：moderation_log 表（v0.18 起机审结论已落库）。
与 tools/moderation-stats.py（读应用日志）的区别：
  · 日志版：适合实时排查，但会随日志轮转消失、且受日志格式影响；
  · 落库版（本脚本）：数据持久、可任意时间范围聚合、可出按天趋势。

输出：
  · 三种结论的数量与占比（放行率 / 转审率 / 拒绝率）
  · 按天趋势（ASCII 柱状，便于直接贴进文档）
  · 命中规则分布与高频命中词
  · 最近若干条记录明细

用法：
  <python> tools/moderation-stats-db.py                 # 打印统计
  <python> tools/moderation-stats-db.py --days 30       # 只看最近 30 天
  <python> tools/moderation-stats-db.py --report        # 同时写出 docs/机审效果统计-落库版.md
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
import os
import pathlib
import re
import subprocess
import time
from collections import Counter, defaultdict

MYSQL = os.environ.get('MYSQL_CLI', r'D:\major\tool\mysql-8.4.4-winx64\bin\mysql.exe')
ROOT = pathlib.Path(__file__).resolve().parent.parent
DOC = ROOT / 'docs' / '机审效果统计-落库版.md'

ACTION_LABEL = {'PASS': '放行', 'REVIEW': '转人工复核', 'REJECT': '拒绝'}


def sql(query):
    r = subprocess.run([MYSQL, '-h', '127.0.0.1', '-u', 'root', '-p123456',
                        '--default-character-set=utf8mb4', '-N', '-B', 'campus_trade',
                        '-e', query],
                       capture_output=True, text=True, encoding='utf-8', timeout=60)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or '').strip()[:300])
    return [line.split('\t') for line in (r.stdout or '').strip().splitlines() if line.strip()]


def bar(n, total, width=28):
    if not total:
        return ''
    filled = max(1, round(n / total * width)) if n else 0
    return '█' * filled


def collect(days):
    where = f"WHERE create_time >= DATE_SUB(NOW(), INTERVAL {days} DAY)" if days else ''
    total = int(sql(f"SELECT COUNT(*) FROM moderation_log {where}")[0][0])
    by_action = {a: int(c) for a, c in sql(
        f"SELECT action, COUNT(*) FROM moderation_log {where} GROUP BY action")}
    trend = defaultdict(dict)
    for day, action, cnt in sql(f"SELECT DATE(create_time), action, COUNT(*) "
                                f"FROM moderation_log {where} GROUP BY DATE(create_time), action "
                                f"ORDER BY DATE(create_time)"):
        trend[day][action] = int(cnt)
    rules = [(r, int(c)) for r, c in sql(
        f"SELECT risk_rule, COUNT(*) FROM moderation_log {where} "
        f"{'AND' if where else 'WHERE'} risk_rule IS NOT NULL "
        f"GROUP BY risk_rule ORDER BY COUNT(*) DESC")]
    hits = sql(f"SELECT hits FROM moderation_log {where} "
               f"{'AND' if where else 'WHERE'} hits IS NOT NULL")
    words = Counter()
    for (h,) in hits:
        for seg in (h or '').split(';'):
            parts = seg.split('|')
            if len(parts) >= 3 and parts[2]:
                words[parts[2]] += 1
    recent = sql(f"SELECT id, action, IFNULL(risk_rule,'-'), hit_count, LEFT(title, 26), create_time "
                 f"FROM moderation_log {where} ORDER BY id DESC LIMIT 8")
    return total, by_action, trend, rules, words, recent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--days', type=int, default=0, help='只统计最近 N 天（0 = 全部）')
    ap.add_argument('--report', action='store_true', help='写出 Markdown 报告')
    args = ap.parse_args()

    total, by_action, trend, rules, words, recent = collect(args.days)
    scope = f'最近 {args.days} 天' if args.days else '全部历史'

    lines = []
    lines.append('=' * 88)
    lines.append(f'机审效果统计（数据来源：moderation_log 表 · 范围：{scope}）')
    lines.append('=' * 88)
    if total == 0:
        lines.append('  暂无机审记录。发布商品后自动写入（可执行 tools/verify-moderation-log.py 造三条）。')
        print('\n'.join(lines))
        return 0

    lines.append(f'  机审总次数：{total}')
    lines.append('')
    lines.append('  ── 一、结论分布 ──')
    for action in ('PASS', 'REVIEW', 'REJECT'):
        n = by_action.get(action, 0)
        pct = n / total * 100
        lines.append(f'    {ACTION_LABEL[action]:<10} {n:>5}  {pct:>6.2f}%  {bar(n, total)}')

    lines.append('')
    lines.append('  ── 二、按天趋势 ──')
    for day in sorted(trend):
        d = trend[day]
        day_total = sum(d.values())
        detail = ' '.join(f'{ACTION_LABEL[a]}={d.get(a, 0)}' for a in ('PASS', 'REVIEW', 'REJECT'))
        lines.append(f'    {day}  合计 {day_total:>4}  {bar(day_total, max(sum(v.values()) for v in trend.values()))}  {detail}')

    lines.append('')
    lines.append('  ── 三、命中规则分布 ──')
    if rules:
        for rule, cnt in rules:
            lines.append(f'    {rule:<16} {cnt:>5} 次')
    else:
        lines.append('    （无命中记录）')

    lines.append('')
    lines.append('  ── 四、高频命中词 Top 10 ──')
    if words:
        for w, c in words.most_common(10):
            lines.append(f'    {w:<20} {c:>5} 次')
    else:
        lines.append('    （无命中词）')

    lines.append('')
    lines.append('  ── 五、最近记录 ──')
    lines.append('    ' + 'ID'.ljust(6) + '结论'.ljust(9) + '规则'.ljust(17) + '命中'.ljust(6) + '标题')
    for rid, action, rule, hc, title, ct in recent:
        lines.append('    ' + rid.ljust(6) + action.ljust(9) + rule.ljust(17) + hc.ljust(6) + (title or ''))

    lines.append('')
    pass_rate = by_action.get('PASS', 0) / total * 100
    review_rate = by_action.get('REVIEW', 0) / total * 100
    reject_rate = by_action.get('REJECT', 0) / total * 100
    lines.append(f'  结论：放行 {pass_rate:.1f}% / 转人审 {review_rate:.1f}% / 拒绝 {reject_rate:.1f}%；'
                 f'机审拦下（转审+拒绝）共 {by_action.get("REVIEW", 0) + by_action.get("REJECT", 0)} 次')
    lines.append('=' * 88)

    text = '\n'.join(lines)
    print(text)

    if args.report:
        md = ['# 机审效果统计（落库版）', '',
              f'> 数据来源：`moderation_log` 表 · 范围：{scope} · 生成时间：{time.strftime("%Y-%m-%d %H:%M:%S")}',
              '',
              '## 一、结论分布', '',
              '| 结论 | 次数 | 占比 |', '| --- | ---: | ---: |']
        for action in ('PASS', 'REVIEW', 'REJECT'):
            n = by_action.get(action, 0)
            md.append(f'| {ACTION_LABEL[action]}（{action}） | {n} | {n / total * 100:.2f}% |')
        md += ['', f'合计 **{total}** 次机审。', '', '## 二、按天趋势', '',
               '| 日期 | 合计 | 放行 | 转人审 | 拒绝 |', '| --- | ---: | ---: | ---: | ---: |']
        for day in sorted(trend):
            d = trend[day]
            md.append(f"| {day} | {sum(d.values())} | {d.get('PASS', 0)} | "
                      f"{d.get('REVIEW', 0)} | {d.get('REJECT', 0)} |")
        md += ['', '## 三、命中规则分布', '', '| 规则 | 命中次数 |', '| --- | ---: |']
        for rule, cnt in (rules or [('（无）', 0)]):
            md.append(f'| {rule} | {cnt} |')
        md += ['', '## 四、高频命中词 Top 10', '', '| 命中词 | 次数 |', '| --- | ---: |']
        for w, c in (words.most_common(10) or [('（无）', 0)]):
            md.append(f'| {w} | {c} |')
        md += ['', '## 五、最近记录', '',
               '| ID | 结论 | 命中规则 | 命中数 | 标题 | 时间 |', '| ---: | --- | --- | ---: | --- | --- |']
        for rid, action, rule, hc, title, ct in recent:
            md.append(f'| {rid} | {action} | {rule} | {hc} | {title} | {ct} |')
        md += ['', '## 六、结论', '',
               f'- 放行率 **{pass_rate:.2f}%**、转人审率 **{review_rate:.2f}%**、拒绝率 **{reject_rate:.2f}%**；',
               f'- 机审共拦下（转审 + 拒绝）**{by_action.get("REVIEW", 0) + by_action.get("REJECT", 0)}** 次；',
               '- 本报告由 `tools/moderation-stats-db.py --report` 自动生成，数据可复算。', '']
        DOC.write_text('\n'.join(md), encoding='utf-8')
        print(f'\n  报告已写出：{DOC}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
