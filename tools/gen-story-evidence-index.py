#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 story-evidence 里的截图自动嵌进测试报告（v0.18）

为什么需要生成器而不是手写：
  · 截图会随采集脚本重跑而变化（增删、改名），手写引用一定会过期；
  · 报告需要"每条故事 → 对应哪几张图 → 图说明了什么"，逐条手抄容易漏、容易错。

做法：
  1. 从 `docs/user-stories.md` 解析出每条故事的编号/名称/优先级；
  2. 扫描 `docs/story-evidence/*.png`，按 `US-模块-序号-N-说明.png` 归到故事下；
  3. 生成一段 markdown（每条故事一个小节 + 图片 + 文件名作为图注），
     用 `<!-- STORY-EVIDENCE:BEGIN -->` / `<!-- STORY-EVIDENCE:END -->` 包起来；
  4. 该区间**整体替换**（幂等），首次运行则追加到报告末尾。

用法：
    python tools/gen-story-evidence-index.py            # 写入 docs/测试报告-用户故事视角.md
    python tools/gen-story-evidence-index.py --out X.md # 写到指定文件
    python tools/gen-story-evidence-index.py --check    # 只检查是否有故事缺图，不写文件
"""
import argparse
import pathlib
import re
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parent.parent
STORIES = ROOT / 'docs' / 'user-stories.md'
SHOTS = ROOT / 'docs' / 'story-evidence'
REPORT = ROOT / 'docs' / '测试报告-用户故事视角.md'

BEGIN = '<!-- STORY-EVIDENCE:BEGIN -->'
END = '<!-- STORY-EVIDENCE:END -->'

# 同一故事里图片的展示顺序：按文件名末尾的序号排
NUM = re.compile(r'^US-[A-Z]+-\d+-(\d+)-')

# 明确"不适合截图"的故事（与 docs/story-evidence/README.md 一致）
NOT_SUITABLE = {
    'US-SEARCH-03': '断言在接口返回值上（只看在售），界面看不出过滤在哪一层',
    'US-QUALITY-03': '断言是源码静态属性（零裸色值），没有界面可看',
    'US-QUALITY-04': '对比度/alt/键盘可达都是脚本计算结论',
    'US-QUALITY-07': '断言是 CI 结论，Actions 页需登录，本地截不到',
    'US-EVAL-03': '变化在数据层（±1、夹 [0,150]），界面只有静态数字',
    'US-ORDER-02': '已被 US-ORDER-01 的买家/卖家视图两张覆盖',
    'US-ORDER-03': '已被 US-ORDER-01 的"卖家确认完成"一张覆盖',
}


def parse_stories():
    text = STORIES.read_text(encoding='utf-8')
    out = []
    for m in re.finditer(r'^### (US-[A-Z]+-\d+)\s+(.+?)【(必须|应该|可以)】', text, re.M):
        out.append({'id': m.group(1), 'name': m.group(2).strip(), 'pri': m.group(3)})
    return out


def scan_shots():
    m = {}
    if not SHOTS.exists():
        return m
    for p in sorted(SHOTS.glob('*.png')):
        g = re.match(r'^(US-[A-Z]+-\d+)-', p.name)
        if not g:
            continue
        m.setdefault(g.group(1), []).append(p)
    for k in m:
        m[k].sort(key=lambda p: (int(NUM.match(p.name).group(1)) if NUM.match(p.name) else 99, p.name))
    return m


def build(stories, shots):
    lines = [BEGIN, '',
             '## 附：用户故事截图证据索引（自动生成，勿手改）', '',
             '> 本节由 `tools/gen-story-evidence-index.py` 生成：图片来自 `docs/story-evidence/`，',
             '> 故事清单来自 `docs/user-stories.md`。重跑采图脚本后重新生成即可，不要手工编辑。', '',
             '| 故事 | 优先级 | 截图数 | 状态 |', '| --- | :-: | :-: | --- |']
    with_shots, without = [], []
    for s in stories:
        n = len(shots.get(s['id'], []))
        if n:
            with_shots.append(s)
        else:
            without.append(s)
        status = f'✅ {n} 张' if n else ('➖ 不适合（见 README）' if s['id'] in NOT_SUITABLE else '❌ 缺图')
        lines.append(f"| {s['id']} {s['name']} | {s['pri']} | {n} | {status} |")

    lines += ['', f'**合计**：{len(stories)} 条故事，'
                  f'**{len(with_shots)} 条有截图**（{sum(len(v) for v in shots.values())} 张），'
                  f'{len(stories) - len(with_shots)} 条无截图。', '']

    lines += ['### 一、有截图的故事', '']
    for s in with_shots:
        lines.append(f"#### {s['id']} {s['name']}（{s['pri']}）")
        lines.append('')
        for p in shots[s['id']]:
            rel = p.relative_to(REPORT.parent).as_posix()
            caption = p.stem
            lines.append(f'![{caption}]({rel})')
            lines.append(f'*图：{caption}*')
            lines.append('')

    if without:
        lines += ['### 二、无截图的故事（附原因）', '']
        for s in without:
            reason = NOT_SUITABLE.get(s['id'], '尚未采集')
            lines.append(f"- **{s['id']} {s['name']}**：{reason}")
        lines.append('')

    lines += [END]
    return '\n'.join(lines) + '\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=str(REPORT))
    ap.add_argument('--check', action='store_true')
    args = ap.parse_args()

    stories = parse_stories()
    shots = scan_shots()
    if not stories:
        print('  ✗ 没解析到用户故事')
        return 1

    missing = [s['id'] for s in stories if not shots.get(s['id']) and s['id'] not in NOT_SUITABLE]
    print(f'  故事 {len(stories)} 条；有截图 {sum(1 for s in stories if shots.get(s["id"]))} 条；'
          f'截图 {sum(len(v) for v in shots.values())} 张')
    if missing:
        print('  ❌ 缺图（且不属于"不适合截图"）：' + ', '.join(missing))
    if args.check:
        return 1 if missing else 0

    section = build(stories, shots)
    out = pathlib.Path(args.out)
    text = out.read_text(encoding='utf-8') if out.exists() else ''
    if BEGIN in text and END in text:
        text = re.sub(r'(?s)' + re.escape(BEGIN) + r'.*?' + re.escape(END),
                      lambda _: section.rstrip('\n'), text, count=1)
        print('  ✓ 已替换报告中的截图索引区间')
    else:
        text = (text.rstrip('\n') + '\n\n---\n\n' + section) if text else section
        print('  ✓ 已追加截图索引到报告末尾')
    out.write_text(text, encoding='utf-8')
    print(f'  写入 {out}（{len(text.splitlines())} 行）')
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main())
