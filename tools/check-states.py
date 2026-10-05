# -*- coding: utf-8 -*-
"""状态完备性检查（空 / 加载 / 错误 / 无权限）

把"页面状态是否完备"从主观判断变成可统计的检查：
按页面类型给出应有的状态集合，逐页扫描源码，输出覆盖率与缺失清单。
改版前用它出基线，改版后用它验收（配合 --max-missing 棘轮）。

页面类型与应有状态：
  list   列表页 —— 骨架屏 + 空态 + 错误态
  detail 详情页 —— 骨架屏 + 错误态（内部列表还应有空态）
  form   表单页 —— 提交中的按钮态 + 错误提示
  misc   其他   —— 至少不能白屏（无强制要求）

用法：
  python tools/check-states.py
  python tools/check-states.py --label before
  python tools/check-states.py --max-missing 5      # 供 CI/验收使用
"""
import argparse
import json
import pathlib
import re
import sys

PROJECT = pathlib.Path(__file__).resolve().parent.parent
SRC = PROJECT / 'frontend' / 'src'

# 页面类型（依据 .impeccable/work/state-matrix.md 的分类）
PAGE_TYPES = {
    'views/product/List.vue': 'list',
    'views/product/Search.vue': 'list',
    'views/product/MyProducts.vue': 'list',
    'views/message/ConversationList.vue': 'list',
    'views/order/OrderList.vue': 'list',
    'views/user/MyFavorites.vue': 'list',
    'views/admin/ProductAudit.vue': 'list',
    'views/admin/UserManage.vue': 'list',
    'views/admin/ReportManage.vue': 'list',
    'views/product/Detail.vue': 'detail',
    'views/order/Detail.vue': 'detail',
    'views/message/Chat.vue': 'detail',
    'views/user/Profile.vue': 'detail',
    'views/admin/Dashboard.vue': 'detail',
    'views/Home.vue': 'detail',
    'views/product/Publish.vue': 'form',
    'views/user/ProfileEdit.vue': 'form',
    'views/Login.vue': 'form',
    'views/Register.vue': 'form',
    'views/NotFound.vue': 'misc',
    'views/Placeholder.vue': 'misc',
    'components/RecommendPanel.vue': 'list',
}

REQUIRED = {
    'list': ['loading', 'empty', 'error'],
    'detail': ['loading', 'error'],
    'form': ['loading', 'error'],
    'misc': [],
}

# 检测规则：新组件与既有写法都算数
PATTERNS = {
    # 加载态：骨架屏、转圈遮罩、或组件上的 :loading 属性（表单页提交态常用）
    'loading': [r'v-loading', r'el-skeleton', r'<Skeleton[A-Z]', r'<skeleton-', r':loading='],
    'empty': [r'el-empty', r'<StateEmpty', r'<state-empty'],
    'error': [r'ElMessage\.error', r'<StateError', r'<state-error', r'error-text', r'loadError'],
    'forbidden': [r'<StateForbidden', r'<state-forbidden', r'403'],
}

# 编辑期允许并存：既有页面用 v-loading 转圈，改版后换骨架屏


def detect(text):
    found = {}
    for key, pats in PATTERNS.items():
        found[key] = any(re.search(p, text) for p in pats)
    found['catch'] = len(re.findall(r'catch\s*\(', text)) > 0
    found['catchCount'] = len(re.findall(r'catch\s*\(', text))
    found['skeleton'] = bool(re.search(r'el-skeleton|<Skeleton[A-Z]|<skeleton-', text))
    found['spinner'] = bool(re.search(r'v-loading', text))
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--label', default='')
    ap.add_argument('--out', default=str(PROJECT / '.impeccable' / 'review'))
    ap.add_argument('--max-missing', type=int, default=-1,
                    help='缺失项超过该值时非零退出（验收/CI 棘轮用）')
    args = ap.parse_args()

    rows = []
    missing_total = 0
    for rel, ptype in sorted(PAGE_TYPES.items()):
        p = SRC / rel
        if not p.exists():
            continue
        text = p.read_text(encoding='utf-8', errors='ignore')
        found = detect(text)
        need = REQUIRED[ptype]
        missing = [s for s in need if not found[s]]
        missing_total += len(missing)
        rows.append({
            'page': rel, 'type': ptype,
            'loading': found['loading'], 'empty': found['empty'], 'error': found['error'],
            'skeleton': found['skeleton'], 'spinner': found['spinner'],
            'catchCount': found['catchCount'],
            'missing': missing,
            'ok': not missing,
        })

    skeleton_pages = sum(1 for r in rows if r['skeleton'])
    spinner_pages = sum(1 for r in rows if r['spinner'])
    empty_pages = sum(1 for r in rows if r['empty'])
    error_pages = sum(1 for r in rows if r['error'])
    ok_pages = sum(1 for r in rows if r['ok'])

    print(f'===== 状态完备性检查（{len(rows)} 个页面/组件）=====')
    print(f'  用骨架屏的页面：{skeleton_pages}/{len(rows)}      用转圈遮罩的页面：{spinner_pages}/{len(rows)}')
    print(f'  有空态的页面：  {empty_pages}/{len(rows)}      有可见错误反馈的页面：{error_pages}/{len(rows)}')
    print(f'  达到应有状态的页面：{ok_pages}/{len(rows)}      缺失项合计：{missing_total}')
    print()
    print(f"  {'页面':44s} {'类型':6s} {'骨架':4s} {'空态':4s} {'错误':4s} 缺失")
    for r in rows:
        print(f"  {r['page']:44s} {r['type']:6s} "
              f"{'有' if r['skeleton'] else '无':4s} {'有' if r['empty'] else '无':4s} "
              f"{'有' if r['error'] else '无':4s} {'、'.join(r['missing']) or '—'}")

    if args.label:
        out = pathlib.Path(args.out) / args.label
        out.mkdir(parents=True, exist_ok=True)
        (out / 'states.json').write_text(json.dumps({
            'label': args.label,
            'pages': len(rows), 'skeletonPages': skeleton_pages, 'spinnerPages': spinner_pages,
            'emptyPages': empty_pages, 'errorPages': error_pages,
            'okPages': ok_pages, 'missingTotal': missing_total, 'rows': rows,
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print(f"\n  已保存：{out / 'states.json'}")

    if args.max_missing >= 0 and missing_total > args.max_missing:
        print(f"\n  ✗ 缺失项 {missing_total} 超过阈值 {args.max_missing}")
        sys.exit(1)


if __name__ == '__main__':
    main()
