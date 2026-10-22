#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文档一致性体检（v0.18）

背景：`docs/user-stories.md` 曾把「游客、用户、卖家、买家、管理员」并列成五个角色，
而 `spec.md` 写的是「2 个登录角色 + 1 个非登录角色」—— 两份文档互相矛盾。
这类问题人眼扫一遍抓不全，因此固化成规则脚本，随时可跑。

做法：把"文档里已经过时的断言"写成**规则**（正则 + 说明 + 应改成什么），
扫全仓库 Markdown，逐条报告命中位置与判定。规则分两类：
  · stale  —— 说法已过时（实现变了、文档没跟上）
  · conflict —— 与另一份文档/代码事实冲突

用法：
    python tools/check-docs-consistency.py            # 打印报告
    python tools/check-docs-consistency.py --md out.md # 同时写 markdown 报告
退出码：0 无问题；1 有 stale/conflict 命中。
"""
import argparse
import pathlib
import re
import subprocess
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parent.parent
# 历史语境豁免：里程碑文档、修订说明、工作日志等在"描述过去"，不算过时
HISTORY = re.compile(
    r'原写|原为|更正|修订|保留给后续|当时|曾是|里程碑|2026-10-0[1-6]|v0\.0[1-9]|v0\.1[0-6]|RESERVED_'
    r'|死状态|→|问题：|背景|并列|五个')   # ← 行首必须有 |，否则会与上一行拼成 RESERVED_死状态
HISTORY_DIRS: tuple = ('docs/milestones/', 'docs/文档一致性检查.md')
SKIP = re.compile(r'node_modules|\.git[\\/]|\.dsh[\\/]|frontend[\\/]dist|backend[\\/]target')

# (规则名, 正则, 判定, 说明 / 应改成什么)
RULES = [
    ('五种角色并列', r'游客[^\n]{0,24}用户[^\n]{0,24}卖家[^\n]{0,24}买家[^\n]{0,24}管理员', 'stale',
     '应为「2 个登录角色（user.role=0/1）+ 1 个非登录角色（游客）」；卖家/买家是交易关系，不是角色'),
    ('把用户当角色', r'[|「]\s*\*\*用户\*\*\s*（已注册', 'stale',
     '同上：用户不是角色，只有 游客/普通学生/管理员'),
    ('订单 1/2/5 未接入', r'1 待交付[^\n]{0,40}(永不产生|不会产生|预留)|RESERVED_|保留给后续', 'stale',
     'v0.18 已接入 6 态状态机（OrderStatusFlow），1/2/5 可流转'),
    ('只有完成/取消', r'status 只能为 3（已完成）', 'stale',
     'v0.18 起支持 1/2/3/4/5'),
    ('发布后待审核', r'发布后[^\n]{0,20}待审核|发布.{0,10}状态=待审核', 'conflict',
     'v0.16 实测：发布后**直接上架在售**（机审拦截违规）'),
    ('用户故事条数过时', r'(30|33)\s*条用户故事|必须 14|应该 11', 'stale',
     '当前 36 条（必须 16 / 应该 15 / 可以 5）'),
    ('功能清单条数过时', r'42 项功能', 'stale', '当前 46 项（F01–F46，含 F43–F46 评价与订单流转）'),
    ('组件测试未纳入', r'组件级未纳入|组件测试[^\n]{0,10}(未做|没有)', 'stale',
     '已有 Vitest 组件测试 15 条、覆盖率 98.15%，并接入 CI'),
    ('无用例总数', r'用例总数[^\n]{0,12}(665|852|867)', 'stale', '当前 910 条（后端 708 + 前端 91 + E2E 31 + 部署 28 + 安全 52）'),
    ('覆盖率数据过时', r'覆盖率[^\n]{0,16}(85\.69|86\.06)', 'stale', '当前 85.94%（棘轮 0.83 未变）'),
    ('截图数量过时', r'(21|28|55)\s*张截图|截图[^\n]{0,8}(21|28)\s*张', 'stale', '当前 59 张，覆盖 29 条故事'),
    ('后端单测条数过时', r'(640|665)\s*条?单测|单测[^\n]{0,8}(640|665)', 'stale', '当前 708 条'),
    ('角色权限矩阵缺游客', r'\|\s*功能\s*\|\s*(普通学生|学生)\s*\|', 'stale',
     '权限矩阵应以「游客 / 普通学生 / 管理员」三列呈现'),
]

# 与代码/DB 的事实对照（用于报告开头）
FACTS = [
    ('用户故事条数', 'docs/user-stories.md', r'^### US-'),
    ('后端单测（上次数）', None, None),
]


def count_stories():
    t = (ROOT / 'docs' / 'user-stories.md').read_text(encoding='utf-8')
    return len(re.findall(r'^### US-', t, re.M))


def md_files():
    for p in ROOT.rglob('*.md'):
        if SKIP.search(str(p)):
            continue
        yield p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--md', default='')
    args = ap.parse_args()

    hits = []
    for p in sorted(md_files()):
        if any(d in p.as_posix() for d in HISTORY_DIRS):
            continue
        try:
            lines = p.read_text(encoding='utf-8').splitlines()
        except Exception:
            continue
        for i, line in enumerate(lines, 1):
            if HISTORY.search(line):
                continue
            for name, pattern, kind, note in RULES:
                if re.search(pattern, line):
                    hits.append({'file': p.relative_to(ROOT).as_posix(), 'line': i,
                                 'rule': name, 'kind': kind, 'note': note,
                                 'text': line.strip()[:160]})

    print(f'  扫描 {len(list(md_files()))} 个 Markdown 文件；规则 {len(RULES)} 条；命中 {len(hits)} 处')
    print(f'  事实基准：用户故事 {count_stories()} 条')
    if hits:
        by_rule = {}
        for h in hits:
            by_rule.setdefault((h['rule'], h['kind']), []).append(h)
        print()
        for (rule, kind), items in sorted(by_rule.items(), key=lambda x: -len(x[1])):
            tag = '过时' if kind == 'stale' else '冲突'
            print(f'  [{tag}] {rule}（{len(items)} 处）')
            print(f'        应改成：{items[0]["note"]}')
            for it in items[:6]:
                print(f'        {it["file"]}:{it["line"]}  {it["text"][:110]}')
            if len(items) > 6:
                print(f'        …… 还有 {len(items) - 6} 处')
            print()

    if args.md:
        out = pathlib.Path(args.md)
        buf = ['# 文档一致性检查（自动生成）', '',
               f'> 由 `tools/check-docs-consistency.py` 生成。规则 {len(RULES)} 条，命中 {len(hits)} 处。',
               '> 判定：**过时** = 实现已变、文档没跟上；**冲突** = 与另一份文档或代码事实矛盾。', '']
        if not hits:
            buf.append('✅ 未发现过时或冲突表述。')
        else:
            buf += ['| 判定 | 规则 | 位置 | 原文 | 应为 |', '| --- | --- | --- | --- | --- |']
            for h in hits:
                buf.append(f'| {"过时" if h["kind"] == "stale" else "冲突"} | {h["rule"]} | '
                           f'`{h["file"]}:{h["line"]}` | {h["text"].replace("|", "\\|")[:120]} | {h["note"]} |')
        out.write_text('\n'.join(buf) + '\n', encoding='utf-8')
        print(f'  报告已写入 {out}')

    return 0 if not hits else 1


if __name__ == '__main__':
    sys.exit(main())
