#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Markdown 渲染成单文件 HTML（用于会议/答辩共享屏幕）

为什么要这个：Markdown 用记事本打开是一堆符号，发给老师不方便；
而现有工具链里没有 pandoc / LibreOffice（已确认未安装），
所以这里用一个不依赖任何外部库的小渲染器，够用即可：
  标题 / 段落 / 列表 / 有序列表 / 表格 / 代码块 / 引用 / 行内代码 / 加粗 / 链接 / 图片

用法：
    python tools/md-to-html.py docs/user-stories.md docs/会议材料/用户故事.html
    python tools/md-to-html.py spec.md docs/会议材料/需求文档.html --title "需求规格说明书"
"""
import argparse
import html
import pathlib
import re
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

CSS = """
:root{--ink:#1a1d19;--ink2:#4a4f47;--muted:#8a8f86;--rule:#d8dbd3;--paper:#f6f7f4;--card:#fff;--accent:#35603f}
*{box-sizing:border-box}
body{margin:0;background:var(--paper);color:var(--ink);
     font:16px/1.75 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif}
.wrap{max-width:1000px;margin:0 auto;padding:40px 28px 100px;background:var(--card);
      min-height:100vh;box-shadow:0 0 0 1px var(--rule)}
h1{font-size:30px;margin:0 0 8px;line-height:1.3}
h2{font-size:22px;margin:36px 0 12px;padding-bottom:8px;border-bottom:1.5px solid var(--rule)}
h3{font-size:18px;margin:26px 0 8px}
h4{font-size:16px;margin:18px 0 6px;color:var(--ink2)}
p{margin:10px 0}
ul,ol{margin:10px 0;padding-left:26px}
li{margin:4px 0}
table{width:100%;border-collapse:collapse;margin:14px 0;font-size:14px}
th,td{border:1px solid var(--rule);padding:8px 10px;text-align:left;vertical-align:top}
th{background:var(--paper);font-weight:600}
tr:nth-child(even) td{background:#fbfcfa}
code{background:#f0f2ee;padding:1px 5px;border-radius:3px;font-size:13px;
     font-family:Consolas,"Courier New",monospace}
pre{background:#f0f2ee;padding:14px;border-radius:6px;overflow:auto;font-size:13px;line-height:1.6}
pre code{background:none;padding:0}
blockquote{margin:14px 0;padding:10px 16px;border-left:3px solid var(--accent);
           background:#f7f9f6;color:var(--ink2)}
img{max-width:100%;border:1px solid var(--rule);border-radius:4px;margin:6px 0}
hr{border:none;border-top:1px solid var(--rule);margin:28px 0}
a{color:var(--accent)}
.meta{color:var(--muted);font-size:13px;margin-bottom:24px}
@media print{
  body{background:#fff}
  .wrap{box-shadow:none;max-width:none;padding:0}
  h2{page-break-after:avoid}
  table,pre,img{page-break-inside:avoid}
}
"""


def inline(s):
    s = html.escape(s, quote=False)
    s = re.sub(r'`([^`]+)`', lambda m: f'<code>{m.group(1)}</code>', s)
    s = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', s)
    s = re.sub(r'(?<!\*)\*([^*]+)\*(?!\*)', r'<em>\1</em>', s)
    s = re.sub(r'!\[([^\]]*)\]\(([^)]+)\)', r'<img alt="\1" src="\2">', s)
    s = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', s)
    return s


def render(md):
    lines = md.splitlines()
    out, i = [], 0
    in_code = False
    code_buf = []
    while i < len(lines):
        ln = lines[i]
        if ln.startswith('```'):
            if in_code:
                out.append('<pre><code>' + html.escape('\n'.join(code_buf)) + '</code></pre>')
                code_buf, in_code = [], False
            else:
                in_code = True
            i += 1
            continue
        if in_code:
            code_buf.append(ln)
            i += 1
            continue

        # 表格（连续以 | 开头的行）
        if ln.strip().startswith('|') and i + 1 < len(lines) and re.match(r'^\s*\|[\s:|-]+\|\s*$', lines[i + 1]):
            header = [c.strip() for c in ln.strip().strip('|').split('|')]
            i += 2
            rows = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                rows.append([c.strip() for c in lines[i].strip().strip('|').split('|')])
                i += 1
            out.append('<table><thead><tr>' + ''.join(f'<th>{inline(c)}</th>' for c in header) + '</tr></thead><tbody>')
            for r in rows:
                out.append('<tr>' + ''.join(f'<td>{inline(c)}</td>' for c in r) + '</tr>')
            out.append('</tbody></table>')
            continue

        m = re.match(r'^(#{1,6})\s+(.*)$', ln)
        if m:
            lvl = len(m.group(1))
            out.append(f'<h{lvl}>{inline(m.group(2))}</h{lvl}>')
            i += 1
            continue

        if re.match(r'^\s*---+\s*$', ln):
            out.append('<hr>')
            i += 1
            continue

        if ln.startswith('>'):
            buf = []
            while i < len(lines) and lines[i].startswith('>'):
                buf.append(lines[i].lstrip('>').strip())
                i += 1
            out.append('<blockquote>' + '<br>'.join(inline(x) for x in buf) + '</blockquote>')
            continue

        m = re.match(r'^\s*[-*]\s+(.*)$', ln)
        if m:
            out.append('<ul>')
            while i < len(lines) and re.match(r'^\s*[-*]\s+', lines[i]):
                out.append('<li>' + inline(re.sub(r'^\s*[-*]\s+', '', lines[i])) + '</li>')
                i += 1
            out.append('</ul>')
            continue

        m = re.match(r'^\s*\d+\.\s+(.*)$', ln)
        if m:
            out.append('<ol>')
            while i < len(lines) and re.match(r'^\s*\d+\.\s+', lines[i]):
                out.append('<li>' + inline(re.sub(r'^\s*\d+\.\s+', '', lines[i])) + '</li>')
                i += 1
            out.append('</ol>')
            continue

        if not ln.strip():
            i += 1
            continue

        out.append('<p>' + inline(ln) + '</p>')
        i += 1
    return '\n'.join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src')
    ap.add_argument('dst')
    ap.add_argument('--title', default='')
    args = ap.parse_args()

    src = pathlib.Path(args.src)
    dst = pathlib.Path(args.dst)
    md = src.read_text(encoding='utf-8')
    title = args.title or next((l.lstrip('# ').strip() for l in md.splitlines() if l.startswith('# ')), src.stem)

    body = render(md)
    # 图片路径改为相对 HTML 所在目录（md 里的相对路径是相对 md 的）
    try:
        rel = pathlib.os.path.relpath(src.parent, dst.parent).replace('\\', '/')
    except Exception:
        rel = '.'
    if rel not in ('.', ''):
        body = body.replace('src="', f'src="{rel}/')

    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(
        f'<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">'
        f'<meta name="viewport" content="width=device-width,initial-scale=1">'
        f'<title>{html.escape(title)}</title><style>{CSS}</style></head><body><div class="wrap">'
        f'<div class="meta">来源：{html.escape(str(src))}　|　'
        f'打印成 PDF：Ctrl+P → 目标选「另存为 PDF」</div>'
        f'{body}</div></body></html>',
        encoding='utf-8')
    print(f'  ✓ {dst}  （{dst.stat().st_size // 1024} KB，'
          f'{len(re.findall("<h[1-4]>", body))} 个标题 / {body.count("<table>")} 张表 / '
          f'{body.count("<img ")} 张图）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
