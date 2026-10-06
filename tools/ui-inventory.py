# -*- coding: utf-8 -*-
"""前端一致性与设计令牌盘点（改版前后对比用）

量化指标（都是可复现、可写进论文的数字）：
  · 页面/组件规模
  · 硬编码颜色的去重数量（颜色越散，视觉越不统一）
  · 圆角 / 字号 / 间距取值的去重数量（是否存在设计尺度）
  · CSS 变量（设计令牌）使用量 —— 改版的核心目标之一
  · Element Plus 组件使用分布（混合策略下：核心页自研、后台保留）
  · 内联样式与 !important 数量（样式债）

用法：
  python tools/ui-inventory.py --label before
  python tools/ui-inventory.py --label after
"""
import argparse
import collections
import json
import pathlib
import re

ROOT = pathlib.Path(r'D:\campus-secondhand-trade\frontend\src')

HEX = re.compile(r'#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})\b')
RGB = re.compile(r'rgba?\([^)]*\)')
RADIUS = re.compile(r'border-radius:\s*([^;]+);')
FONT_SIZE = re.compile(r'font-size:\s*([^;]+);')
GAP = re.compile(r'\b(?:gap|margin|padding)(?:-[a-z]+)?:\s*([^;]+);')
CSSVAR_USE = re.compile(r'var\(--[a-z0-9-]+')
CSSVAR_DEF = re.compile(r'^\s*(--[a-z0-9-]+)\s*:', re.M)
EL_COMPONENT = re.compile(r'<(el-[a-z-]+)')
INLINE_STYLE = re.compile(r'\sstyle="')
IMPORTANT = re.compile(r'!important')


def norm_color(c):
    c = c.strip().lower()
    if c.startswith('#') and len(c) == 4:      # #abc -> #aabbcc
        c = '#' + ''.join(ch * 2 for ch in c[1:])
    return c


def scan_file(p, stats):
    text = p.read_text(encoding='utf-8', errors='ignore')
    rel = p.relative_to(ROOT.parent).as_posix()
    # src/styles/ 是设计系统层：tokens.css 是**唯一允许**出现原始色值的地方，
    # 因此它不计入"组件层裸色值"（否则会把令牌定义误报成硬编码，前后对比失真）。
    in_design_system_layer = '/src/styles/' in '/' + rel
    if not in_design_system_layer:
        stats['colors'] += [(rel, norm_color(c)) for c in HEX.findall(text)]
        stats['colors'] += [(rel, c.replace(' ', '')) for c in RGB.findall(text)]
        stats['radius'] += [(rel, v.strip()) for v in RADIUS.findall(text)]
        stats['fontsize'] += [(rel, v.strip()) for v in FONT_SIZE.findall(text)]
        stats['gap'] += [(rel, v.strip()) for v in GAP.findall(text)]
        stats['inline'] += len(INLINE_STYLE.findall(text))
        stats['important'] += len(IMPORTANT.findall(text))
    else:
        stats['style_layer_important'] += len(IMPORTANT.findall(text))
    stats['var_use'] += [(rel, v) for v in CSSVAR_USE.findall(text)]
    stats['var_def'] += [(rel, v) for v in CSSVAR_DEF.findall(text)]
    stats['el'] += [(rel, c) for c in EL_COMPONENT.findall(text)]
    # 组件层（.vue/.js）对令牌的采用量：改版成效的直接指标
    if p.suffix in ('.vue', '.js') and not in_design_system_layer:
        stats['var_use_component'] += [(rel, v) for v in CSSVAR_USE.findall(text)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--label', default='before')
    ap.add_argument('--out', default=r'D:\campus-secondhand-trade\.impeccable\review')
    args = ap.parse_args()

    stats = collections.defaultdict(list)
    stats['inline'] = 0
    stats['important'] = 0
    stats['style_layer_important'] = 0

    files = sorted(ROOT.rglob('*.vue')) + sorted(ROOT.rglob('*.css')) + sorted(ROOT.rglob('*.js'))
    for p in files:
        if 'node_modules' in p.as_posix():
            continue
        scan_file(p, stats)

    views = sorted((ROOT / 'views').rglob('*.vue'))
    comps = sorted((ROOT / 'components').rglob('*.vue'))
    colors = collections.Counter(c for _, c in stats['colors'])
    radius = collections.Counter(v for _, v in stats['radius'])
    fs = collections.Counter(v for _, v in stats['fontsize'])
    gap = collections.Counter(v for _, v in stats['gap'])
    el = collections.Counter(c for _, c in stats['el'])
    var_use = collections.Counter(v for _, v in stats['var_use'])
    var_def = collections.Counter(v for _, v in stats['var_def'])
    var_use_comp = collections.Counter(v for _, v in stats['var_use_component'])

    report = {
        'label': args.label,
        'files': {'vue': len([f for f in files if f.suffix == '.vue']),
                  'css': len([f for f in files if f.suffix == '.css']),
                  'js': len([f for f in files if f.suffix == '.js']),
                  'views': len(views), 'components': len(comps)},
        'colors': {'distinct': len(colors), 'totalOccurrences': len(stats['colors']),
                   'note': '仅统计组件/页面层，src/styles/ 设计系统层不计入',
                   'top': colors.most_common(20)},
        'radius': {'distinct': len(radius), 'top': radius.most_common(15)},
        'fontSize': {'distinct': len(fs), 'top': fs.most_common(15)},
        'spacing': {'distinct': len(gap), 'top': gap.most_common(15)},
        'tokens': {'definitions': len(var_def), 'definitionsOccurrences': sum(var_def.values()),
                   # usages 是**出现次数**（"多少处在用令牌"），distinctUsages 是不同变量名个数。
                   # 之前误把 len(Counter) 当次数报，导致"组件层采用 15 处"这种失真读数。
                   'usages': sum(var_use.values()), 'distinctUsages': len(var_use),
                   'componentUsages': sum(var_use_comp.values()),
                   'componentDistinct': len(var_use_comp),
                   'defined': var_def.most_common(30)},
        'elementPlus': {'distinctComponents': len(el), 'totalUsages': len(stats['el']),
                        'top': el.most_common(15)},
        'styleDebt': {'inlineStyles': stats['inline'], 'importantCount': stats['important'],
                      'importantInStyleLayer': stats['style_layer_important'],
                      'note': 'importantInStyleLayer 为设计系统层中 reduced-motion 的必要覆盖'},
        'perFileColors': collections.Counter(f for f, _ in stats['colors']).most_common(12),
    }

    out = pathlib.Path(args.out) / args.label
    out.mkdir(parents=True, exist_ok=True)
    (out / 'inventory.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    print(f'===== 前端一致性盘点（{args.label}）=====')
    print(f"文件：.vue {report['files']['vue']} 个（视图 {report['files']['views']}、组件 {report['files']['components']}）、.css {report['files']['css']} 个、.js {report['files']['js']} 个")
    print(f"颜色：{report['colors']['distinct']} 种不同取值，出现 {report['colors']['totalOccurrences']} 次")
    print('       最常见：', ', '.join(f'{c}×{n}' for c, n in colors.most_common(8)))
    print(f"圆角：{report['radius']['distinct']} 种不同取值 → " + ', '.join(f'{v}×{n}' for v, n in radius.most_common(8)))
    print(f"字号：{report['fontSize']['distinct']} 种不同取值 → " + ', '.join(f'{v}×{n}' for v, n in fs.most_common(8)))
    print(f"间距：{report['spacing']['distinct']} 种不同取值 → " + ', '.join(f'{v}×{n}' for v, n in gap.most_common(8)))
    print(f"设计令牌：定义 {report['tokens']['definitions']} 个 CSS 变量，使用 {report['tokens']['usages']} 处"
          f"（其中组件/页面层采用 {report['tokens']['componentUsages']} 处）")
    print(f"Element Plus：{report['elementPlus']['distinctComponents']} 种组件、{report['elementPlus']['totalUsages']} 次使用")
    el_top = ', '.join(f'{c}×{n}' for c, n in el.most_common(10))
    print(f'       最多：{el_top}')
    print(f"样式债：内联 style {report['styleDebt']['inlineStyles']} 处、!important {report['styleDebt']['importantCount']} 处")
    print(f'\n已保存：{out / "inventory.json"}')


if __name__ == '__main__':
    main()
