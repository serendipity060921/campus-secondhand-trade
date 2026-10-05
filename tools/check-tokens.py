# -*- coding: utf-8 -*-
"""设计令牌纪律检查（把设计规范变成可机械验证的约束）

规则：
  E1 raw-color        组件/页面里出现裸色值（#hex / rgb() / rgba() / hsl()）
                      —— 设计系统层 frontend/src/styles/ 免检（那里是唯一允许原始色值的地方）
  E2 !important       在设计系统层之外使用 !important（reduced-motion 块内的必要覆盖除外）
  W1 off-scale-space  padding/margin/gap 使用了不在 4 的倍数档位上的像素值
  W2 off-scale-font   font-size 不在八档字号内
  W3 off-scale-radius border-radius 不在令牌圆角档位内
  W4 inline-color     内联 style 里带颜色

用法：
  python tools/check-tokens.py                 # 默认检查 frontend/src
  python tools/check-tokens.py --label before  # 结果写入 .impeccable/review/<label>/tokens-lint.json
  python tools/check-tokens.py --max-errors 0  # 供 CI 使用：有错误即非零退出
"""
import argparse
import json
import pathlib
import re
import sys

# 相对定位项目根目录，便于在 CI 与其他机器上运行（无绝对路径依赖）
ROOT = pathlib.Path(__file__).resolve().parent.parent / 'frontend' / 'src'
PROJECT = ROOT.parent.parent

# 设计系统层：允许出现原始色值
ALLOWLIST = ('frontend/src/styles/',)

SPACE_SCALE = {'0', '4px', '8px', '12px', '16px', '24px', '32px', '48px', '64px'}
FONT_SCALE = {'12px', '13px', '14px', '15px', '18px', '22px', '26px', '40px', '100%'}
RADIUS_SCALE = {'0', '2px', '4px', '6px', '50%', '999px', '50'}

RAW_COLOR = re.compile(r'#[0-9a-fA-F]{3,8}\b|\brgba?\s*\(|\bhsla?\s*\(')
SPACE_DECL = re.compile(r'\b(?:padding|margin|gap|row-gap|column-gap)(?:-[a-z]+)?\s*:\s*([^;{}]+)')
FONT_DECL = re.compile(r'\bfont-size\s*:\s*([^;{}]+)')
RADIUS_DECL = re.compile(r'\bborder-radius\s*:\s*([^;{}]+)')
INLINE_STYLE = re.compile(r'style="([^"]*)"')
REDUCED_BLOCK = re.compile(r'@media\s*\(prefers-reduced-motion[^)]*\)\s*\{.*?\n\}', re.S)
PX = re.compile(r'(\d+(?:\.\d+)?px)')


def strip_allowable(decl_value):
    """去掉 var()/calc()/auto/百分比等无法机械判定的写法后返回原始串"""
    return decl_value.strip()


def judge_scale(value, scale):
    """值里所有 px 是否都在档位上；含 var()/calc()/rem/%/auto 的一律放过"""
    v = value.strip()
    if 'var(' in v or 'calc(' in v or '%' in v or 'auto' in v or 'inherit' in v or 'initial' in v:
        return True
    px = PX.findall(v)
    if not px:
        return True
    return all(p in scale for p in px)


def check_file(path, errors, warnings):
    rel = path.relative_to(ROOT.parent.parent).as_posix()
    allowed = any(rel.startswith(a) for a in ALLOWLIST)
    text = path.read_text(encoding='utf-8', errors='ignore')
    lines = text.split('\n')

    def loc(pos):
        return text[:pos].count('\n') + 1

    if not allowed:
        for m in RAW_COLOR.finditer(text):
            errors.append({'rule': 'raw-color', 'file': rel, 'line': loc(m.start()),
                           'snippet': lines[loc(m.start()) - 1].strip()[:90]})

        without_reduced = REDUCED_BLOCK.sub('', text)
        for m in re.finditer(r'!important', without_reduced):
            errors.append({'rule': 'important', 'file': rel, 'line': loc(m.start()),
                           'snippet': lines[loc(m.start()) - 1].strip()[:90]})

        for m in SPACE_DECL.finditer(text):
            if not judge_scale(m.group(1), SPACE_SCALE):
                warnings.append({'rule': 'off-scale-space', 'file': rel, 'line': loc(m.start()),
                                 'value': m.group(1).strip()[:40]})
        for m in FONT_DECL.finditer(text):
            if not judge_scale(m.group(1), FONT_SCALE):
                warnings.append({'rule': 'off-scale-font', 'file': rel, 'line': loc(m.start()),
                                 'value': m.group(1).strip()[:40]})
        for m in RADIUS_DECL.finditer(text):
            if not judge_scale(m.group(1), RADIUS_SCALE):
                warnings.append({'rule': 'off-scale-radius', 'file': rel, 'line': loc(m.start()),
                                 'value': m.group(1).strip()[:40]})
        for m in INLINE_STYLE.finditer(text):
            if RAW_COLOR.search(m.group(1)):
                warnings.append({'rule': 'inline-color', 'file': rel, 'line': loc(m.start()),
                                 'value': m.group(1)[:40]})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--label', default='')
    ap.add_argument('--out', default=str(PROJECT / '.impeccable' / 'review'))
    ap.add_argument('--max-errors', type=int, default=-1,
                    help='错误数超过该值时以非零退出（CI 用）；默认 -1 表示只报告')
    args = ap.parse_args()

    errors, warnings = [], []
    files = sorted(ROOT.rglob('*.vue')) + sorted(ROOT.rglob('*.css')) + sorted(ROOT.rglob('*.js'))
    checked = 0
    for p in files:
        if 'node_modules' in p.as_posix():
            continue
        check_file(p, errors, warnings)
        checked += 1

    by_rule = {}
    for e in errors + warnings:
        by_rule[e['rule']] = by_rule.get(e['rule'], 0) + 1

    by_file = {}
    for e in errors:
        by_file[e['file']] = by_file.get(e['file'], 0) + 1

    print(f'===== 设计令牌纪律检查（{checked} 个文件）=====')
    print(f'  错误：{len(errors)} 条    警告：{len(warnings)} 条')
    for rule, n in sorted(by_rule.items(), key=lambda kv: -kv[1]):
        kind = '错误' if rule in ('raw-color', 'important') else '警告'
        print(f'    [{kind}] {rule:18s} {n}')
    if by_file:
        print('\n  裸色值最多的文件（页面阶段逐个清理）：')
        for f, n in sorted(by_file.items(), key=lambda kv: -kv[1])[:12]:
            print(f'    {n:4d}  {f}')
    if errors:
        print('\n  前 8 条错误明细：')
        for e in errors[:8]:
            print(f"    {e['file']}:{e['line']}  {e['rule']}  {e['snippet'][:70]}")

    if args.label:
        out = pathlib.Path(args.out) / args.label
        out.mkdir(parents=True, exist_ok=True)
        (out / 'tokens-lint.json').write_text(json.dumps({
            'label': args.label, 'filesChecked': checked,
            'errors': errors, 'warnings': warnings, 'byRule': by_rule, 'byFile': by_file,
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print(f"\n  已保存：{out / 'tokens-lint.json'}")

    if args.max_errors >= 0 and len(errors) > args.max_errors:
        print(f"\n  ✗ 错误数 {len(errors)} 超过阈值 {args.max_errors}")
        sys.exit(1)
    print('\n  ✓ 检查完成' if not errors else '')


if __name__ == '__main__':
    main()
