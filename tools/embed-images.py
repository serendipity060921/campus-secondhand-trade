#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 HTML 里的图片内嵌成 base64，产出**真正的单文件**（v0.18）

为什么需要：`docs/自动化测试报告.html` 用相对路径引用 `story-evidence/*.png`。
一旦这个 HTML 被单独发给老师 / 拖到别处，57 张图全裂 —— 实测确实裂了 55 张。
内嵌后一个文件就能独立打开，适合会议共享与邮件附件。

用法：
    python tools/embed-images.py docs/会议材料/3-自动化测试报告.html
    python tools/embed-images.py a.html b.html --in-place
"""
import argparse
import base64
import mimetypes
import pathlib
import re
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

EXTS = {'.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg'}


def embed(html_path: pathlib.Path, in_place=False):
    text = html_path.read_text(encoding='utf-8')
    base = html_path.parent
    cache = {}
    embedded = missing = 0

    def repl(m):
        nonlocal embedded, missing
        quote, src = m.group(1), m.group(2)
        if src.startswith(('data:', 'http://', 'https://')):
            return m.group(0)
        target = (base / src).resolve()
        if target.suffix.lower() not in EXTS:
            return m.group(0)
        if not target.exists():
            missing += 1
            print(f'    ⚠️ 找不到图片: {src}')
            return m.group(0)
        key = str(target)
        if key not in cache:
            mime = mimetypes.guess_type(key)[0] or 'image/png'
            cache[key] = f'data:{mime};base64,' + base64.b64encode(target.read_bytes()).decode('ascii')
            embedded += 1
        return f'src={quote}{cache[key]}{quote}'

    text = re.sub(r'src=(["\'])([^"\']+)\1', repl, text)
    out = html_path if in_place else html_path.with_name(html_path.stem + '.单文件' + html_path.suffix)
    out.write_text(text, encoding='utf-8')
    print(f'  ✓ {html_path.name}: 内嵌 {embedded} 张（缺失 {missing}）→ {out.name} '
          f'（{out.stat().st_size // 1024} KB）')
    return out, embedded, missing


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--in-place', action='store_true')
    args = ap.parse_args()

    total_missing = 0
    for f in args.files:
        p = pathlib.Path(f)
        if not p.exists():
            print(f'  ✗ 不存在: {f}')
            total_missing += 1
            continue
        _, _, missing = embed(p, args.in_place)
        total_missing += missing
    return 0 if total_missing == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
