# -*- coding: utf-8 -*-
"""before / after 对比表自动生成（论文表格直接可用，不手抄数字）

读取 .impeccable/review/<档位>/ 下的 audit.json / inventory.json /
tokens-lint.json / detect.json，输出 Markdown 对比表 + compare.json。

用法：
  python tools/ui-report.py --labels before tokens after
  python tools/ui-report.py --labels before after --out docs/UI优化对比.md
"""
import argparse
import json
import pathlib

PROJECT = pathlib.Path(__file__).resolve().parent.parent
REVIEW = PROJECT / '.impeccable' / 'review'

LABEL_NAME = {
    'before': '优化前',
    'tokens': '仅令牌层',
    'after': '优化后',
}


def load(label, name):
    p = REVIEW / label / name
    if not p.exists():
        return None
    try:
        if name == 'detect.json':
            return json.loads(p.read_text(encoding='utf-8-sig').lstrip('\ufeff'))
        return json.loads(p.read_text(encoding='utf-8'))
    except Exception:
        return None


def gather(label):
    m = {}
    audit = load(label, 'audit.json')
    inv = load(label, 'inventory.json')
    lint = load(label, 'tokens-lint.json')
    det = load(label, 'detect.json')

    if audit:
        a = audit['aggregate']
        s = audit['scores']
        checked = max(1, a['contrastChecked'])
        m['总分'] = f"{s['total']}/{s.get('totalScale', 20)}"
        m['可访问性(A11y)'] = f"{s['accessibility']}/4"
        m['性能'] = f"{s['performance']}/4"
        m['主题'] = f"{s['theming']}/4"
        m['响应式'] = f"{s['responsive']}/4"
        m['一致性'] = '未测量' if s.get('integrity') is None else f"{s['integrity']}/4"
        m['对比度检查处数'] = str(a['contrastChecked'])
        m['对比度不达标'] = f"{a['contrastFail']}（{a['contrastFail'] * 100 // checked}%）"
        m['图片无 alt'] = str(a['imgNoAlt'])
        m['可交互元素总数'] = str(a['interactiveTotal'])
        m['无可访问名称'] = str(a['interactiveNoName'])
        m['焦点不可见元素'] = str(a['focusInvisible'])
        m['标题跳级'] = str(a['headingJumps'])
        m['390px 溢出页数'] = f"{a['overflowPages']}/8"
        m['过小触控目标'] = str(a['smallTouchTargets'])
        m['最大 DOM 节点'] = str(a['maxDom'])
        m['大阴影/滤镜/背景模糊'] = (f"{a['heavy']['bigShadow']}/{a['heavy']['filter']}/"
                                     f"{a['heavy']['backdrop']}")
    if inv:
        t = inv['tokens']
        m['令牌定义'] = str(t['definitions'])
        m['令牌使用'] = str(t['usages'])
        m['组件层令牌采用'] = str(t.get('componentUsages', 0))
        m['硬编码颜色(组件层)'] = f"{inv['colors']['distinct']} 种 / {inv['colors']['totalOccurrences']} 次"
        m['间距取值去重'] = str(inv['spacing']['distinct'])
        m['字号取值去重'] = str(inv['fontSize']['distinct'])
        m['圆角取值去重'] = str(inv['radius']['distinct'])
        m['Element Plus 组件'] = f"{inv['elementPlus']['distinctComponents']} 种 / {inv['elementPlus']['totalUsages']} 次"
        m['内联 style / !important'] = (f"{inv['styleDebt']['inlineStyles']} / "
                                        f"{inv['styleDebt']['importantCount']}")
    if det is not None:
        m['设计缺陷检测'] = f"{len(det)} 条"
        rules = {}
        for d in det:
            rules[d.get('antipattern', '?')] = rules.get(d.get('antipattern', '?'), 0) + 1
        if rules:
            m['检测规则分布'] = '、'.join(f'{k}×{v}' for k, v in sorted(rules.items(), key=lambda kv: -kv[1]))
    if lint:
        m['令牌纪律错误'] = f"{len(lint['errors'])} 条"
        m['令牌纪律警告'] = f"{len(lint['warnings'])} 条"
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--labels', nargs='+', default=['before', 'tokens', 'after'])
    ap.add_argument('--out', default=str(PROJECT / 'docs' / 'UI优化对比.md'))
    args = ap.parse_args()

    data = {lb: gather(lb) for lb in args.labels}
    measured = [lb for lb in args.labels if data[lb]]
    missing = [lb for lb in args.labels if not data[lb]]

    if not measured:
        print('✗ 没有任何档位的数据，先跑 tools/ui-audit.py 与 tools/ui-inventory.py')
        return

    # 行顺序按第一个有数据的档位的键顺序
    order = list(data[measured[0]].keys())
    for lb in measured[1:]:
        for k in data[lb]:
            if k not in order:
                order.append(k)

    headers = ['指标'] + [LABEL_NAME.get(lb, lb) for lb in measured]
    lines = ['| ' + ' | '.join(headers) + ' |',
             '| ' + ' | '.join(['---'] * len(headers)) + ' |']
    for k in order:
        row = [k] + [data[lb].get(k, '—') for lb in measured]
        lines.append('| ' + ' | '.join(row) + ' |')

    table = '\n'.join(lines)
    title = ('# UI 优化前后对比（自动生成）\n\n'
             '> 由 `python tools/ui-report.py --labels '
             + ' '.join(args.labels) + '` 生成；数字全部来自各档位的机械测量，未手工修改。\n'
             '> 原始证据：`.impeccable/review/<档位>/`（截图 + manifest + audit.json + inventory.json + '
             'detect.json + tokens-lint.json）。\n\n')
    if missing:
        title += '> 尚未测量的档位：' + '、'.join(missing) + '（表中不出现）。\n\n'
    title += '## 对比表\n\n'

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(title + table + '\n', encoding='utf-8')

    (REVIEW / 'compare.json').write_text(
        json.dumps({'labels': args.labels, 'measured': measured, 'data': data},
                   ensure_ascii=False, indent=2), encoding='utf-8')

    print(f'已生成对比表：{out}')
    print(f'已保存原始汇总：{REVIEW / "compare.json"}')
    print()
    print(table)


if __name__ == '__main__':
    main()
