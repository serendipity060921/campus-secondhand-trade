#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「功能型故事 28 条 · 逐条场景与截图」独立报告（单文件 HTML，图片内嵌）

用途：导师只想看"28 条功能故事各自对应哪个场景、截图在哪"，不必翻整份测试报告。
数据来源：docs/测试报告-用户故事视角.md 的第五节表格 + docs/story-evidence/*.png
"""
import base64
import pathlib
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(r'D:\campus-secondhand-trade')
REPORT = ROOT / 'docs' / '测试报告-用户故事视角.md'
SHOTS = ROOT / 'docs' / 'story-evidence'
OUT = ROOT / 'docs' / '功能故事28条场景与截图.html'

md = REPORT.read_text(encoding='utf-8')
# 抓第五节表格行
rows = []
for line in md.splitlines():
    m = re.match(r'\| (\d+) \| \*\*(US-[A-Z]+-\d+)\*\* (.+?) \| (必须|应该|可以) \| (\d+) \| (.+?) \| (.+?) \| (.*?) \|$', line)
    if m:
        rows.append({'idx': m.group(1), 'sid': m.group(2), 'name': m.group(3),
                     'pri': m.group(4), 'ac': m.group(5), 'scene': m.group(6).strip(),
                     'shotcell': m.group(7).strip(), 'how': m.group(8)})

assert rows, '没抓到第五节表格，检查报告格式'

# 每个故事的截图
by_story = {}
for p in sorted(SHOTS.glob('US-*.png')):
    g = re.match(r'^(US-[A-Z]+-\d+)-', p.name)
    if g:
        by_story.setdefault(g.group(1), []).append(p)


def data_uri(p):
    b = p.read_bytes()
    return 'data:image/png;base64,' + base64.b64encode(b).decode()


cards = []
for r in rows:
    imgs = by_story.get(r['sid'], [])
    thumbs = '\n'.join(
        f'<figure><img src="{data_uri(p)}" alt="{p.stem}"><figcaption>{p.stem}</figcaption></figure>'
        for p in imgs)
    cards.append(f'''
<section class="card">
  <h3><span class="num">{r['idx']}</span> {r['sid']} <span class="pri">{r['pri']}</span></h3>
  <div class="meta">{r['name']}　·　验收标准 {r['ac']} 条　·　浏览器场景 <b>{r['scene']}</b>　·　截图 <b>{r['shotcell']}</b></div>
  <div class="how">其它覆盖方式：{r['how']}</div>
  <div class="shots">{thumbs or '<p class="none">（本故事截图见 docs/story-evidence/）</p>'}</div>
</section>''')

total_shots = sum(len(v) for v in by_story.values())
html = f'''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>功能型用户故事 28 条 · 逐条场景与截图</title>
<style>
 body{{margin:0;padding:32px 20px 80px;background:#f5f5f0;color:#1e241f;
   font-family:"Segoe UI","Microsoft YaHei",system-ui,sans-serif;line-height:1.65}}
 .wrap{{max-width:1100px;margin:0 auto}}
 h1{{font-size:26px;margin:0 0 6px}}
 .sub{{color:#5c6660;margin:0 0 6px}}
 .badge{{display:inline-block;background:#1e241f;color:#fff;border-radius:6px;padding:4px 10px;
   font-size:13px;margin-right:8px}}
 .card{{background:#fff;border:1px solid #e2e4dd;border-radius:12px;padding:18px 20px;margin:16px 0}}
 .card h3{{margin:0 0 6px;font-size:17px;display:flex;align-items:center;gap:8px}}
 .num{{background:#1e241f;color:#fff;border-radius:6px;width:26px;height:26px;display:inline-flex;
   align-items:center;justify-content:center;font-size:13px}}
 .pri{{font-size:12px;font-weight:400;color:#7a6a10;background:#fdf6d8;border-radius:4px;padding:2px 8px}}
 .meta{{color:#3d4741;font-size:14px;margin:4px 0}}
 .how{{color:#6b756f;font-size:13px;margin:2px 0 12px}}
 .shots{{display:grid;gap:14px}}
 figure{{margin:0}}
 figure img{{width:100%;border:1px solid #e2e4dd;border-radius:8px;display:block}}
 figcaption{{font-size:12px;color:#6b756f;margin-top:6px;word-break:break-all}}
 .none{{color:#9aa39d;font-size:13px}}
</style></head><body><div class="wrap">
<h1>功能型用户故事 28 条 · 逐条场景与截图</h1>
<p class="sub">校园二手交易平台 · 黎钟 · 2027 届软件工程　|　AI 驱动真实浏览器（Playwright + Edge）逐条操作</p>
<p><span class="badge">28 条功能故事</span><span class="badge">每条一个场景</span>
<span class="badge">共 {total_shots} 张截图</span></p>
<p class="sub">另有 9 条质量/非功能型需求（设计令牌、可访问性、信用分防刷等）断言对象不在界面上，
不在本报告范围；其证据见安全套件、部署验证、静态检查与压测。</p>
{''.join(cards)}
</div></body></html>'''

OUT.write_text(html, encoding='utf-8')
print(f'  ✓ 已生成 {OUT.name}：{len(rows)} 条故事，{total_shots} 张截图内嵌，'
      f'{OUT.stat().st_size // 1024} KB')
