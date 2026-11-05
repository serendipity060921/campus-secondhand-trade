#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Markdown → Word(docx) 转换器（够用版，零外部依赖，只要 python-docx）

支持：标题(1~6) / 段落 / 无序与有序列表 / 引用块 / 代码块 / 管道表格 /
      图片(本地路径，按正文宽度缩放) / 行内粗体与 `代码`(简化为纯文本加粗/等宽)
不支持（刻意）：脚注、HTML 内嵌、复杂嵌套列表 —— 报告类文档用不到

用法：
    python tools/md-to-docx.py 输入.md 输出.docx --title "文档标题"
"""
import argparse
import pathlib
import re
import sys

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parent.parent

CODE_RE = re.compile(r'`([^`]+)`')
BOLD_RE = re.compile(r'\*\*([^*]+)\*\*')
IMG_RE = re.compile(r'!\[([^\]]*)\]\(([^)]+)\)')


def set_cjk_font(run, name='微软雅黑', size=None, bold=None, mono=False):
    run.font.name = 'Consolas' if mono else name
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn('w:rFonts'))
    if rfonts is None:
        rfonts = OxmlElement('w:rFonts')
        rpr.append(rfonts)
    rfonts.set(qn('w:eastAsia'), name)
    if size:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold


def add_runs(par, text, base_size=10.5):
    """把 **粗体** 与 `代码` 拆成多个 run。"""
    pos = 0
    pattern = re.compile(r'(\*\*[^*]+\*\*|`[^`]+`)')
    for m in pattern.finditer(text):
        if m.start() > pos:
            r = par.add_run(text[pos:m.start()])
            set_cjk_font(r, size=base_size)
        token = m.group(0)
        if token.startswith('**'):
            r = par.add_run(token[2:-2])
            set_cjk_font(r, size=base_size, bold=True)
        else:
            r = par.add_run(token[1:-1])
            set_cjk_font(r, size=size_of(base_size) - 0.5, mono=True)
        pos = m.end()
    if pos < len(text):
        r = par.add_run(text[pos:])
        set_cjk_font(r, size=base_size)


def size_of(base):
    return base


def shade(cell, hex_color='F2F3EE'):
    tcpr = cell._tc.get_or_add_tcPr()
    el = OxmlElement('w:shd')
    el.set(qn('w:val'), 'clear')
    el.set(qn('w:color'), 'auto')
    el.set(qn('w:fill'), hex_color)
    tcpr.append(el)


def split_row(line):
    line = line.strip()
    if line.startswith('|'):
        line = line[1:]
    if line.endswith('|'):
        line = line[:-1]
    return [c.strip() for c in line.split('|')]


def is_sep(line):
    return bool(re.match(r'^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$', line.strip()))


def convert(md_path: pathlib.Path, out_path: pathlib.Path, title: str):
    lines = md_path.read_text(encoding='utf-8').splitlines()
    doc = Document()

    # 页面与默认字体
    sec = doc.sections[0]
    sec.left_margin = sec.right_margin = Cm(2.2)
    sec.top_margin = sec.bottom_margin = Cm(2.0)
    style = doc.styles['Normal']
    style.font.name = '微软雅黑'
    style.font.size = Pt(10.5)
    style.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')

    # 封面标题
    if title:
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        r = p.add_run(title)
        set_cjk_font(r, size=20, bold=True)
        doc.add_paragraph()

    i = 0
    n_img = n_tbl = 0
    while i < len(lines):
        line = lines[i].rstrip()
        stripped = line.strip()

        # 空行
        if not stripped:
            i += 1
            continue

        # 代码块
        if stripped.startswith('```'):
            i += 1
            buf = []
            while i < len(lines) and not lines[i].strip().startswith('```'):
                buf.append(lines[i])
                i += 1
            i += 1
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Cm(0.4)
            for k, code in enumerate(buf):
                r = p.add_run(code)
                set_cjk_font(r, size=9, mono=True)
                if k < len(buf) - 1:
                    r.add_break()
            continue

        # 表格
        if stripped.startswith('|') and i + 1 < len(lines) and is_sep(lines[i + 1]):
            header = split_row(stripped)
            i += 2
            rows = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                rows.append(split_row(lines[i].strip()))
                i += 1
            tbl = doc.add_table(rows=1, cols=len(header))
            tbl.style = 'Table Grid'
            tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
            for c, text in enumerate(header):
                cell = tbl.rows[0].cells[c]
                cell.text = ''
                par = cell.paragraphs[0]
                add_runs(par, text, base_size=9.5)
                for r in par.runs:
                    r.bold = True
                shade(cell)
            for row in rows:
                cells = tbl.add_row().cells
                for c in range(len(header)):
                    val = row[c] if c < len(row) else ''
                    cells[c].text = ''
                    add_runs(cells[c].paragraphs[0], val, base_size=9)
            doc.add_paragraph()
            n_tbl += 1
            continue

        # 图片
        m = IMG_RE.search(stripped)
        if m and stripped.startswith('!['):
            rel = m.group(2).strip()
            img = (md_path.parent / rel) if not pathlib.Path(rel).is_absolute() else pathlib.Path(rel)
            if img.exists() and img.suffix.lower() in ('.png', '.jpg', '.jpeg'):
                try:
                    doc.add_picture(str(img), width=Cm(15.5))
                    doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
                    cap = doc.add_paragraph()
                    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
                    r = cap.add_run(m.group(1) or img.stem)
                    set_cjk_font(r, size=8)
                    r.font.color.rgb = RGBColor(0x6B, 0x75, 0x6F)
                    n_img += 1
                except Exception:
                    pass
            i += 1
            continue

        # 标题
        mh = re.match(r'^(#{1,6})\s+(.*)$', stripped)
        if mh:
            level = len(mh.group(1))
            text = mh.group(2).strip()
            if level == 1:
                p = doc.add_paragraph()
                r = p.add_run(text)
                set_cjk_font(r, size=16, bold=True)
            else:
                h = doc.add_heading(level=min(level, 4))
                r = h.add_run(text)
                set_cjk_font(r, size={2: 14, 3: 12.5, 4: 11.5}.get(level, 11), bold=True)
            i += 1
            continue

        # 引用块
        if stripped.startswith('>'):
            text = re.sub(r'^>\s?', '', stripped)
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Cm(0.6)
            add_runs(p, text, base_size=9.5)
            for r in p.runs:
                r.font.color.rgb = RGBColor(0x5C, 0x66, 0x60)
            i += 1
            continue

        # 列表
        mo = re.match(r'^(\s*)[-*+]\s+(.*)$', line)
        if mo:
            p = doc.add_paragraph(style='List Bullet')
            add_runs(p, mo.group(2))
            i += 1
            continue
        mn = re.match(r'^(\s*)\d+[.)]\s+(.*)$', line)
        if mn:
            p = doc.add_paragraph(style='List Number')
            add_runs(p, mn.group(2))
            i += 1
            continue

        # 分隔线
        if re.match(r'^-{3,}$', stripped):
            i += 1
            continue

        # 普通段落
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(4)
        add_runs(p, stripped)
        i += 1

    doc.save(str(out_path))
    return n_img, n_tbl


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src')
    ap.add_argument('dst')
    ap.add_argument('--title', default='')
    a = ap.parse_args()
    src, dst = pathlib.Path(a.src), pathlib.Path(a.dst)
    img, tbl = convert(src, dst, a.title)
    kb = dst.stat().st_size // 1024
    print(f'  ✓ {dst.name}  {kb} KB  表格 {tbl} 张  图片 {img} 张')
    return 0


if __name__ == '__main__':
    sys.exit(main())
