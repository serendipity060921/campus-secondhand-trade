#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「带截图的自动化测试报告」（v0.18，对应指导老师第 3 条要求）

为什么需要它：
  项目里各套件都是"跑完打控制台 + 落 JSON"，截图则来自另一个采图脚本 ——
  三样东西分散在三处，报告只能手写拼接，一改就过期。
  本脚本把它们合成**一个自包含 HTML**：用例逐条（含输入/预期/实际）+ 按用户故事分组的截图。

输入：
  · 结果 JSON（自动在多个位置找）：security-result.json / v015_result.json /
    e2e_result.json / evaluation_result.json / evaluation_ui_result.json
  · 用例总数（解析 docs/测试用例表.md 的汇总表）
  · 截图：docs/story-evidence/*.png
  · 故事清单：docs/user-stories.md
输出：
  docs/自动化测试报告.html（相对路径引用截图，随仓库一起走，不需要起服务）

用法：
    python tools/gen-test-report-html.py
    python tools/gen-test-report-html.py --open     # 生成后用默认浏览器打开
"""
import argparse
import datetime
import html
import json
import pathlib
import platform
import re
import sys
import webbrowser

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs' / '自动化测试报告.html'
SHOTS = ROOT / 'docs' / 'story-evidence'
STORIES = ROOT / 'docs' / 'user-stories.md'
CASES = ROOT / 'docs' / '测试用例表.md'
TEMP = pathlib.Path(__import__('tempfile').gettempdir()) / 'dsh-sqlval'

# 套件定义：(显示名, 候选路径列表, 说明, 字段读取方式)
SUITES = [
    ('端到端测试（Playwright，真实浏览器）', ['e2e_result.json'],
     '31 条，覆盖主业务闭环与异常场景', 'rich'),
    ('部署与接口验证 D1–D28（经 Nginx）', ['v015_result.json'],
     '28 条，含数据完整性、限流、WebSocket', 'rich'),
    ('安全套件 S 系列', ['security-result.json', 'tests/api/security-result.json', 'tests/security-result.json'],
     '52 条，越权 / 注入 / 上传 / 信息泄漏', 'security'),
    ('双向评价 · 接口验证', ['evaluation_result.json'],
     '28 条，9xxx 业务码、信用分、匿名分层、汇总口径', 'name'),
    ('双向评价 · 界面验证（Playwright）', ['evaluation_ui_result.json'],
     '19 条，入口 → 弹窗 → 提交 → 重复拦截 → 商品页展示', 'name'),
]


def find(name):
    for base in (TEMP, ROOT, ROOT / 'tests'):
        p = base / name
        if p.exists():
            return p
    return None


def parse_stories():
    if not STORIES.exists():
        return {}
    out = {}
    for m in re.finditer(r'^### (US-[A-Z]+-\d+)\s+(.+?)【(必须|应该|可以)】',
                         STORIES.read_text(encoding='utf-8'), re.M):
        out[m.group(1)] = {'name': m.group(2).strip(), 'pri': m.group(3)}
    return out


def parse_totals():
    """从用例总表里读各套件条数，用于总览。"""
    tot = {}
    if not CASES.exists():
        return tot
    for line in CASES.read_text(encoding='utf-8').splitlines():
        m = re.match(r'\|\s*([^|]+?)\s*\|\s*(\d+)\s*\|', line)
        if m and any(k in m.group(1) for k in ('测试', '合计')):
            tot[m.group(1)] = int(m.group(2))
    return tot


def load_suite(paths):
    for n in paths:
        p = find(n)
        if not p:
            continue
        try:
            d = json.loads(p.read_text(encoding='utf-8'))
        except Exception:
            continue
        if isinstance(d, dict):
            if isinstance(d.get('results'), list):
                return p, d['results'], d
            # 各套件的 JSON 结构不统一（security 用的是别的键名）——
            # 退一步：找第一个"元素是 dict 的列表"，而不是写死键名
            for v in d.values():
                if isinstance(v, list) and v and isinstance(v[0], dict):
                    return p, v, d
        if isinstance(d, list):
            return p, d, {}
    return None, [], {}


def normalize(kind, rows):
    """统一成 {id, name, inputs, expected, actual, ok, shot} 便于渲染。"""
    out = []
    for r in rows:
        if not isinstance(r, dict):
            continue
        ok = bool(r.get('ok', r.get('pass', True)))
        if kind == 'rich':
            out.append({'id': r.get('id', ''), 'name': r.get('scenario') or r.get('name', ''),
                        'inputs': r.get('inputs', ''), 'expected': r.get('expected', ''),
                        'actual': r.get('actual', ''), 'ok': ok,
                        'shot': r.get('evidence', '')})
        elif kind == 'security':
            out.append({'id': r.get('id', ''), 'name': r.get('name') or r.get('scenario', ''),
                        'inputs': r.get('inputs', '') or r.get('request', ''),
                        'expected': r.get('expected', ''), 'actual': r.get('actual', ''),
                        'ok': ok, 'shot': r.get('evidence', '')})
        else:
            out.append({'id': r.get('id', ''), 'name': r.get('name', ''), 'inputs': '',
                        'expected': '', 'actual': r.get('detail', ''), 'ok': ok, 'shot': ''})
    return out


def esc(v):
    return html.escape(str(v or ''), quote=True)


CSS = """
:root{--ink:#1a1d19;--ink2:#5c6159;--muted:#8a8f86;--rule:#c9ccc4;--paper:#f5f6f3;
      --card:#fff;--ok:#35603f;--bad:#8c3b5a;--warn:#7a5a2e}
*{box-sizing:border-box}
body{margin:0;background:var(--paper);color:var(--ink);
     font:15px/1.7 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif}
.wrap{max-width:1180px;margin:0 auto;padding:32px 20px 80px}
h1{font-size:26px;margin:0 0 6px}
h2{font-size:19px;margin:38px 0 12px;padding-bottom:8px;border-bottom:.5px solid var(--rule)}
h3{font-size:16px;margin:22px 0 8px}
.sub{color:var(--ink2);margin:0 0 20px}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:18px 0 8px}
.kpi{background:var(--card);border:.5px solid var(--rule);border-radius:8px;padding:14px 16px}
.kpi b{display:block;font-size:24px;line-height:1.2}
.kpi span{font-size:12px;color:var(--muted)}
table{width:100%;border-collapse:collapse;background:var(--card);font-size:13px}
th,td{padding:8px 10px;border-bottom:.5px solid var(--rule);text-align:left;vertical-align:top}
th{background:var(--paper);color:var(--ink2);font-weight:600;position:sticky;top:0}
tr.bad td{background:#fbf0f3}
tr.bad td:first-child{border-left:3px solid var(--bad)}
.ok{color:var(--ok);font-weight:600}
.no{color:var(--bad);font-weight:600}
.tag{display:inline-block;padding:1px 7px;border:.5px solid var(--rule);border-radius:999px;
     font-size:12px;color:var(--ink2)}
.mut{color:var(--muted)}
.gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:16px;margin-top:12px}
figure{background:var(--card);border:.5px solid var(--rule);border-radius:8px;margin:0;overflow:hidden}
figure img{width:100%;display:block;border-bottom:.5px solid var(--rule);cursor:zoom-in}
figcaption{padding:8px 12px;font-size:12px;color:var(--ink2);word-break:break-all}
.story{background:var(--card);border:.5px solid var(--rule);border-radius:8px;
       padding:14px 16px;margin:14px 0}
.story h4{margin:0 0 10px;font-size:15px}
nav a{color:var(--ink2);margin-right:14px;font-size:13px}
.note{background:#fff;border-left:3px solid var(--warn);padding:10px 14px;margin:14px 0;font-size:13px}
"""

JS = """
document.querySelectorAll('figure img').forEach(function(img){
  img.addEventListener('click', function(){ window.open(img.getAttribute('src'), '_blank'); });
});
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--open', action='store_true')
    args = ap.parse_args()

    # 验证过程截图（US-EVAL-04..08）不属于独立的"故事"，归到它们实际所属的故事下展示，
    # 否则报告里会有 5 张图无处安放（等于漏掉）。
    SHOT_ALIAS = {'US-EVAL-04': 'US-EVAL-01', 'US-EVAL-05': 'US-EVAL-01',
                  'US-EVAL-06': 'US-EVAL-01', 'US-EVAL-07': 'US-EVAL-02',
                  'US-EVAL-08': 'US-EVAL-02'}
    stories = parse_stories()
    totals = parse_totals()
    shots = {}
    if SHOTS.exists():
        for p in sorted(SHOTS.glob('*.png')):
            g = re.match(r'^(US-[A-Z]+-\d+)-', p.name)
            if g:
                sid = SHOT_ALIAS.get(g.group(1), g.group(1))
                shots.setdefault(sid, []).append(p)

    # 载入各套件
    blocks, grand_total, grand_ok = [], 0, 0
    for title, paths, desc, kind in SUITES:
        path, rows, meta = load_suite(paths)
        cases = normalize(kind, rows)
        ok = sum(1 for c in cases if c['ok'])
        grand_total += len(cases)
        grand_ok += ok
        if not cases:
            blocks.append((title, desc, [], None, None, 0, 0))
            continue
        blocks.append((title, desc, cases, path, meta, len(cases), ok))

    now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M')
    total_shots = sum(len(v) for v in shots.values())
    covered = len([k for k in stories if shots.get(k)])

    h = []
    h.append('<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">')
    h.append('<meta name="viewport" content="width=device-width,initial-scale=1">')
    h.append('<title>校园二手交易平台 · 自动化测试报告</title>')
    h.append(f'<style>{CSS}</style></head><body><div class="wrap">')
    h.append('<h1>校园二手交易平台 · 自动化测试报告</h1>')
    h.append(f'<p class="sub">黎钟 · 2023030203055 · 软件工程 · 电子科技大学中山学院　|　'
             f'生成时间 {now}　|　生成脚本 tools/gen-test-report-html.py</p>')

    h.append('<div class="kpis">')
    h.append(f'<div class="kpi"><b>{grand_total}</b><span>本次报告收录的用例</span></div>')
    h.append(f'<div class="kpi"><b class="ok">{grand_ok}</b><span>通过</span></div>')
    h.append(f'<div class="kpi"><b class="{"no" if grand_total - grand_ok else "ok"}">{grand_total - grand_ok}</b>'
             f'<span>失败</span></div>')
    h.append(f'<div class="kpi"><b>{total_shots}</b><span>截图证据</span></div>')
    h.append(f'<div class="kpi"><b>{covered}/{len(stories)}</b><span>有截图的故事</span></div>')
    if totals:
        h.append(f'<div class="kpi"><b>{totals.get("合计", "-")}</b><span>全量自动化用例（含单测）</span></div>')
    h.append('</div>')

    h.append('<nav>' + ''.join(
        f'<a href="#s{i}">{esc(b[0])}</a>' for i, b in enumerate(blocks)) +
        '<a href="#gallery">截图证据墙</a></nav>')

    h.append('<div class="note">本报告由脚本从各套件的 JSON 结果与 <code>docs/story-evidence/</code> 的截图自动合成：'
             '用例逐条列出输入/预期/实际，截图按用户故事分组。<b>截图可点击放大</b>。'
             '失败的用例会整行标红。</div>')

    if totals:
        h.append('<h2>一、全量用例构成</h2><table><thead><tr><th>套件</th><th>条数</th></tr></thead><tbody>')
        for k, v in totals.items():
            h.append(f'<tr><td>{esc(k)}</td><td>{v}</td></tr>')
        h.append('</tbody></table>')

    h.append('<h2>二、本次收录的套件（逐条）</h2>')
    for i, (title, desc, cases, path, meta, n, ok) in enumerate(blocks):
        h.append(f'<h3 id="s{i}">{esc(title)} '
                 f'<span class="tag">{n} 条 · 通过 {ok}</span></h3>')
        # 结果文件可能落在仓库外（$TEMP/dsh-sqlval），relative_to 会抛错 —— 用安全的方式取显示名
        if path:
            try:
                shown = path.relative_to(ROOT).as_posix()
            except ValueError:
                shown = str(path)
            src = f'　·　来源 <code>{esc(shown)}</code>'
        else:
            src = '　·　<span class="no">未找到结果文件（该套件本次未运行）</span>'
        h.append(f'<p class="mut">{esc(desc)}{src}</p>')
        if not cases:
            continue
        h.append('<table><thead><tr><th>编号</th><th>场景</th><th>输入</th><th>预期</th>'
                 '<th>实际</th><th>结果</th></tr></thead><tbody>')
        for c in cases:
            cls = '' if c['ok'] else ' class="bad"'
            h.append(f'<tr{cls}><td>{esc(c["id"])}</td><td>{esc(c["name"])}</td>'
                     f'<td class="mut">{esc(c["inputs"])}</td><td class="mut">{esc(c["expected"])}</td>'
                     f'<td>{esc(c["actual"])}</td>'
                     f'<td class="{"ok" if c["ok"] else "no"}">{"通过" if c["ok"] else "失败"}</td></tr>')
        h.append('</tbody></table>')

    h.append('<h2 id="gallery">三、截图证据墙（按用户故事分组）</h2>')
    h.append(f'<p class="mut">共 {total_shots} 张，覆盖 {covered} 条用户故事。'
             '截图文件名本身即为断言（<code>US-模块-序号-N-说明.png</code>）。</p>')
    for sid, info in stories.items():
        imgs = shots.get(sid, [])
        if not imgs:
            continue
        h.append(f'<div class="story"><h4>{esc(sid)} {esc(info["name"])} '
                 f'<span class="tag">{esc(info["pri"])} · {len(imgs)} 张</span></h4>')
        h.append('<div class="gallery">')
        for p in imgs:
            rel = p.relative_to(ROOT / 'docs').as_posix()
            h.append(f'<figure><img src="{esc(rel)}" alt="{esc(p.stem)}" loading="lazy">'
                     f'<figcaption>{esc(p.stem)}</figcaption></figure>')
        h.append('</div></div>')

    no_shot = [f'{k} {v["name"]}' for k, v in stories.items() if not shots.get(k)]
    if no_shot:
        h.append('<h2>四、无界面的故事（不截图，另附证据）</h2><ul>')
        h.append('<li>' + '</li><li>'.join(esc(x) for x in no_shot) + '</li></ul>')
        h.append('<p class="mut">这些故事的断言对象是接口返回值 / 源码静态属性 / CI 结论，'
                 '截图无法证明；对应证据见 <code>docs/story-evidence/README.md</code>。</p>')

    h.append(f'<p class="mut" style="margin-top:40px">环境：{esc(platform.platform())} · '
             f'Python {esc(platform.python_version())}</p>')
    h.append(f'<script>{JS}</script></div></body></html>')

    OUT.write_text('\n'.join(h), encoding='utf-8')
    size = OUT.stat().st_size // 1024
    print(f'  套件 {len([b for b in blocks if b[2]])} 个；用例 {grand_total} 条（通过 {grand_ok}）；'
          f'截图 {total_shots} 张 / {covered} 条故事')
    print(f'  ✓ 已生成 {OUT.relative_to(ROOT).as_posix()}（{size} KB）')
    if args.open:
        webbrowser.open(OUT.as_uri())
    return 0 if grand_total and grand_ok == grand_total else 1


if __name__ == '__main__':
    sys.exit(main())
