#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「提交清单」自动写进 docs/工作日志.md（v0.18）

背景：工作日志是我手写的，2026-10-19 以来 39 个提交里只提到 14 个 ——
"挑重要的写"与"所有事情都要有记录"是冲突的，靠记性补也迟早再漏。
因此把清单交给脚本：每次提交后跑一次，日志里永远有完整清单。

行为：
  1. `git log` 取 master 上的全部提交；
  2. 逐条判断"正文里是否已详细描述"（正文 = 自动区块之前的内容）；
  3. 生成一段 Markdown 表格，用 `<!-- COMMITS:BEGIN -->` / `<!-- COMMITS:END -->` 包住，
     幂等替换（不重复堆积）；
  4. `--check` 模式只检查"自动区块是否与 git 一致"，不一致返回 1（可进 CI）。

用法：
    python tools/gen-worklog-commits.py                # 刷新工作日志里的清单
    python tools/gen-worklog-commits.py --check        # 只检查是否过期
"""
import argparse
import pathlib
import re
import subprocess
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOG = ROOT / 'docs' / '工作日志.md'
BEGIN = '<!-- COMMITS:BEGIN -->'
END = '<!-- COMMITS:END -->'

KIND = [('feat', '功能'), ('fix', '修复'), ('test', '测试'), ('docs', '文档'),
        ('refactor', '重构'), ('chore', '杂务'), ('perf', '性能'), ('style', '样式')]


def commits():
    out = subprocess.run(
        ['git', 'log', '--pretty=format:%h\x1f%ad\x1f%s', '--date=format:%Y-%m-%d %H:%M', 'master'],
        cwd=str(ROOT), capture_output=True, text=True, encoding='utf-8').stdout
    rows = []
    for line in out.splitlines():
        parts = line.split('\x1f')
        if len(parts) < 3:
            continue
        sha, date, subj = parts[0], parts[1], parts[2]
        # 去掉 BOM 与常规前缀，取类型
        subj = subj.lstrip('\ufeff').strip()
        m = re.match(r'^([a-z]+)(\([^)]*\))?[:：]\s*(.*)$', subj)
        kind, scope, text = ('其它', '', subj)
        if m:
            kind = dict(KIND).get(m.group(1), m.group(1))
            scope = (m.group(2) or '').strip('()')
            text = m.group(3)
# ★ 自引用问题：刷新清单的那个提交永远无法出现在自己的清单里，
        #   否则 --check 会永远差 1 个。这类提交（只改工作日志清单）直接排除。
# 只改了 docs/工作日志.md 的提交（本质就是清单刷新本身）不列入清单，
        # 否则 --check 永远差一个（清单无法包含"生成清单"这一动作自己的提交）。
        files = subprocess.run(['git', 'show', '--name-only', '--format=', sha],
                               cwd=str(ROOT), capture_output=True).stdout.decode('utf-8', 'replace')
        changed = [f for f in files.splitlines() if f.strip()]
        if changed and all(f.strip() == 'docs/工作日志.md' for f in changed):
            continue
        rows.append({'sha': sha, 'date': date, 'kind': kind, 'scope': scope, 'text': text})
    return rows


def build(rows, prose):
    lines = [BEGIN, '',
             '## 提交清单（自动生成，勿手改）',
             '',
             f'> 由 `tools/gen-worklog-commits.py` 生成，共 **{len(rows)}** 个提交。',
             '> 用途：保证"每个改动都有记录"，不依赖手写是否漏。',
             '> 标 ✅ 的表示正文里另有详细描述（改了什么/证据/教训）。',
             '',
             '| 提交 | 时间 | 类型 | 说明 | 正文详述 |',
             '| --- | --- | --- | --- | :---: |']
    detailed = 0
    for r in rows:
        has = r['sha'] in prose
        if has:
            detailed += 1
        scope = f'（{r["scope"]}）' if r['scope'] else ''
        text = r['text'].replace('|', '\\|')[:78]
        lines.append(f'| `{r["sha"]}` | {r["date"]} | {r["kind"]}{scope} | {text} | {"✅" if has else "—"} |')
    lines += ['', f'**统计**：{len(rows)} 个提交，其中 {detailed} 个在正文里有详细描述，'
                  f'{len(rows) - detailed} 个仅在本清单中留痕。', '', END]
    return '\n'.join(lines) + '\n', detailed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true')
    args = ap.parse_args()

    if not LOG.exists():
        print('  ✗ 工作日志不存在')
        return 1
    text = LOG.read_text(encoding='utf-8')
    rows = commits()
    prose = text.split(BEGIN)[0] if BEGIN in text else text
    block, detailed = build(rows, prose)

    if BEGIN in text and END in text:
        current = re.search('(?s)' + re.escape(BEGIN) + '.*?' + re.escape(END), text).group(0) + '\n'
    else:
        current = None

    if args.check:
        same = current is not None and current.strip() == block.strip()
        print(f'  提交 {len(rows)} 个；正文详述 {detailed} 个；清单{"已同步 ✓" if same else "已过期 ✗"}')
        return 0 if same else 1

    # ★ 清单必须永远在**文件末尾**：否则 prose 会被截断在清单之前，
    #   "正文详述"就统计不到下面的章节（实测曾把 83 行当成全部正文，只数到 7 个）。
    if current:
        text = text.replace(current, '')
    text = text.rstrip('\n') + '\n\n---\n\n' + block
    LOG.write_text(text, encoding='utf-8')

    # 回读校验
    final = LOG.read_text(encoding='utf-8')
    ok = all(r['sha'] in final for r in rows) and BEGIN in final and END in final
    print(f'  ✓ 已写入清单：{len(rows)} 个提交（正文详述 {detailed} 个，清单留痕 {len(rows)-detailed} 个）')
    print(f'  回读校验（每个 sha 都出现在日志里）: {ok}')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
