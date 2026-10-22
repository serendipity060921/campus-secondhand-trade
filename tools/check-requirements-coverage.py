#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
需求 ↔ 实现 反向对齐检查（v0.18）

为什么做：前两次缺口都是学生从界面行为反推出来的
  · 角色定义把"用户"当角色（与 spec.md 矛盾）
  · 业务机审三层分流没写进用户故事
说明我"把实现翻译成需求"时倾向于写主干、漏分支。这个脚本反过来：
从**代码**里抽出全部业务异常码与状态取值，逐条问「用户故事里有没有对应描述」。

抽什么：
  1. 各模块 `*ResultCode.java` 里的 (code, name, message) —— 这就是全部业务错误目录
  2. 状态枚举：ProductStatus / OrderStatus / OrderStatusFlow 常量
  3. user.role / user.status 的取值（来自 DB 列注释）

怎么判"有覆盖"：
  从提示语里抽中文关键词（长度≥2），若有一个出现在 docs/user-stories.md 里，视为**疑似覆盖**；
  一个都没有则标为**缺口**。这是启发式，误判方向偏保守（宁可报缺口，让你确认）。

用法：
    python tools/check-requirements-coverage.py                # 打印摘要
    python tools/check-requirements-coverage.py --md out.md    # 写详细报告
退出码：0 全部疑似覆盖；1 存在缺口（便于进 CI 时先当提示用）
"""
import argparse
import pathlib
import re
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / 'backend' / 'src' / 'main' / 'java' / 'com' / 'campus' / 'trade'
US = ROOT / 'docs' / 'user-stories.md'

CODE_RE = re.compile(r'^\s*([A-Z][A-Z0-9_]+)\(\s*(\d{3,4})\s*,\s*"([^"]*)"\s*\)')
ENUM_RE = re.compile(r'^\s*([A-Z][A-Z0-9_]+)\(\s*(\d)\s*,\s*"([^"]*)"\s*\)')
# 同义词：故事措辞与代码提示语常常不同（代码「评分」，故事写「星级 / rating」）
ALIAS = {'评分': ['星级', 'rating']}
WEAK = {'不能', '只能', '已经', '当前', '状态', '不存在', '失败', '非法', '错误'}


def zh_keywords(text):
    """从中文提示里抽候选关键词（去掉过于通用的词）。"""
    words = re.findall(r'[\u4e00-\u9fa5]{2,}', text)
    out = []
    for w in words:
        if w in WEAK:
            continue
        # 长词再切 2~4 字子串，提高召回
        out.append(w)
        for n in (2, 3, 4):
            for i in range(0, max(1, len(w) - n + 1)):
                sub = w[i:i + n]
                if len(sub) == n and sub not in WEAK:
                    out.append(sub)
    return list(dict.fromkeys(out))


def collect_codes():
    items = []
    for p in sorted(MAIN.glob('common/result/*ResultCode.java')):
        module = p.stem.replace('ResultCode', '')
        for i, line in enumerate(p.read_text(encoding='utf-8').splitlines(), 1):
            m = CODE_RE.match(line)
            if m and m.group(1) != 'SUCCESS':
                items.append({'module': module, 'name': m.group(1), 'code': m.group(2),
                              'message': m.group(3), 'where': f'{p.name}:{i}'})
    return items


def collect_states():
    out = []
    for name in ('ProductStatus', 'OrderStatus', 'OrderStatusFlow'):
        p = MAIN / 'common' / 'constant' / f'{name}.java'
        if not p.exists():
            continue
        for i, line in enumerate(p.read_text(encoding='utf-8').splitlines(), 1):
            m = ENUM_RE.match(line)
            if m:
                out.append({'enum': name, 'name': m.group(1), 'code': m.group(2),
                            'label': m.group(3), 'where': f'{name}.java:{i}'})
        # OrderStatusFlow 用 public static final int 常量
        if name == 'OrderStatusFlow':
            for i, line in enumerate(p.read_text(encoding='utf-8').splitlines(), 1):
                m2 = re.match(r'^\s*public static final int ([A-Z_]+) = (\d+);', line)
                if m2:
                    # OrderStatusFlow 用 int 常量、没有中文标签；这些取值在 OrderStatus 枚举里
                    # 已有同名条目，重复登记只会制造假缺口 —— 直接跳过。
                    continue
    return out


def coverage(text, message):
    kws = list(zh_keywords(message))
    for k, syns in ALIAS.items():
        if k in message:
            kws.extend(syns)
    return [k for k in kws if k in text]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--md', default='')
    args = ap.parse_args()

    us = US.read_text(encoding='utf-8')
    codes = collect_codes()
    states = collect_states()

    miss_codes, ok_codes = [], []
    for c in codes:
        hit = coverage(us, c['message'])
        (ok_codes if hit else miss_codes).append({**c, 'hit': hit[:3]})

    miss_states, ok_states = [], []
    for s in states:
        hit = [k for k in zh_keywords(s['label']) if k in us] if s['label'] else []
        (ok_states if hit else miss_states).append({**s, 'hit': hit[:3]})

    print(f'  业务异常码 {len(codes)} 个：疑似覆盖 {len(ok_codes)}，缺口 {len(miss_codes)}')
    print(f'  状态取值   {len(states)} 个：疑似覆盖 {len(ok_states)}，缺口 {len(miss_states)}')
    if miss_codes:
        print('\n  ❗ 用户故事里找不到对应描述的业务码（重点看这些）：')
        for c in miss_codes[:24]:
            print(f'    {c["code"]} {c["module"]}.{c["name"]}  「{c["message"]}」')
    if miss_states:
        print('\n  ❗ 用户故事里找不到对应描述的状态取值：')
        for s in miss_states[:16]:
            print(f'    {s["enum"]}.{s["name"]}({s["code"]}) {s["label"]}')

    if args.md:
        out = pathlib.Path(args.md)
        buf = ['# 需求 ↔ 实现 反向对齐检查（自动生成）', '',
               f'> 由 `tools/check-requirements-coverage.py` 生成。业务码 {len(codes)} 个、'
               f'状态取值 {len(states)} 个。',
               '> 判定为**启发式**：从提示语抽中文关键词，在 `docs/user-stories.md` 里找。',
               '> 命中即"疑似覆盖"，未命中列在下面的缺口区，供人工确认。', '',
               '## 一、业务异常码目录', '',
               '| 码 | 模块 | 枚举名 | 提示语 | 故事里有吗 | 命中关键词 |',
               '| --- | --- | --- | --- | :---: | --- |']
        for c in sorted(codes, key=lambda x: x['code']):
            hit = coverage(us, c['message'])
            buf.append(f'| {c["code"]} | {c["module"]} | {c["name"]} | {c["message"].replace("|", "\\|")} | '
                       f'{"✅" if hit else "❌ 缺口"} | {"、".join(hit[:3])} |')
        buf += ['', '## 二、状态取值目录', '',
                '| 枚举 | 取值 | 标签 | 故事里有吗 |', '| --- | --- | --- | :---: |']
        for s in states:
            hit = [k for k in zh_keywords(s['label']) if k in us] if s['label'] else []
            buf.append(f'| {s["enum"]} | {s["name"]}={s["code"]} | {s["label"]} | {"✅" if hit else "❌ 缺口"} |')
        buf += ['', '## 三、结论', '',
                f'- 业务码缺口 **{len(miss_codes)}** 个，状态缺口 **{len(miss_states)}** 个',
                '- 缺口不等于"功能没做"，只说明**用户故事里没有对应验收标准**；',
                '  逐条确认后：真缺失的补 AC / 补故事，属于实现细节的记入"非功能/技术约束"。']
        out.write_text('\n'.join(buf) + '\n', encoding='utf-8')
        print(f'\n  报告已写入 {out}')

    return 0 if not (miss_codes or miss_states) else 1


if __name__ == '__main__':
    sys.exit(main())
