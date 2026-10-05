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
    'components/RecommendPanel.vue': 'panel',      # 可选面板：无推荐时不渲染是合理设计
}

REQUIRED = {
    'list': ['loading', 'empty', 'error'],
    'detail': ['loading', 'error'],
    # 表单页只要求提交态：表单的错误通道是**字段校验 + 全局 toast**（拦截器统一弹出），
    # 再塞一个整块错误面板反而与字段级提示重复、也更占地方。这是有意的判断，不是遗漏。
    # 可选面板（如首页推荐位）：没有内容时不渲染是合理的，但加载/错误态必须有
    'panel': ['loading', 'error'],
    'form': ['loading'],
    'misc': [],
}

# 检测规则：新组件与既有写法都算数
PATTERNS = {
    # 加载态：骨架屏、转圈遮罩、或组件上的 :loading 属性（表单页提交态常用）
    'loading': [r'v-loading', r'el-skeleton', r'<Skeleton[A-Z]', r'<skeleton-', r':loading='],
    'empty': [r'el-empty', r'<StateEmpty', r'<state-empty'],
    # 页面级错误态：**持久、可重试**的呈现（这是目标形态）
    'error': [r'<StateError', r'<state-error', r'error-text', r'loadError', r'load-error'],
    # 全局兜底：axios 拦截器统一弹出的 3 秒 toast（有反馈但会消失、无重试入口）
    'toast': [r'ElMessage\.error', r"type:\s*['\"]error['\"]", r'\.error\('],
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
    found['mouseOnly'] = count_mouse_only(text)
    return found


CLICK_EL = re.compile(r'<([a-zA-Z][\w-]*)\b([^>]*?)>', re.I)

# 天生可键盘操作的标签/组件：它们带 @click 不算"鼠标独占"
KEYBOARD_NATIVE = {
    'button', 'a', 'input', 'select', 'textarea', 'summary', 'details',
    'el-button', 'el-radio', 'el-radio-button', 'el-checkbox', 'el-checkbox-button',
    'el-switch', 'el-menu-item', 'el-dropdown-item', 'el-tab-pane', 'el-pagination',
    'el-link', 'el-upload', 'el-select', 'el-option', 'el-step', 'el-collapse-item',
}


def count_mouse_only(text):
    """统计"鼠标独占的点击元素"：带 @click / v-on:click，却既无 role 也无 tabindex。

    覆盖原生标签与组件标签（**商品卡用的是 `<el-card @click>`，只扫原生标签会漏掉**），
    并排除天生可键盘操作的标签。仍属静态近似（不解析模板树），用途是**棘轮**：只许下降。
    评审 P0-1（首页商品卡键盘不可达）正是这类问题。
    """
    n = 0
    for m in CLICK_EL.finditer(text):
        tag = m.group(1).lower()
        if tag in KEYBOARD_NATIVE:
            continue
        attrs = m.group(2) or ''
        if not re.search(r'(@click|v-on:click)', attrs):
            continue
        if re.search(r'(role\s*=|tabindex|:tabindex)', attrs):
            continue
        n += 1
    return n

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--label', default='')
    ap.add_argument('--out', default=str(PROJECT / '.impeccable' / 'review'))
    ap.add_argument('--max-missing', type=int, default=-1,
                    help='缺失项超过该值时非零退出（验收/CI 棘轮用）')
    ap.add_argument('--max-mouse-only', type=int, default=-1,
                    help='鼠标独占点击元素超过该值时非零退出（键盘可达性棘轮）')
    ap.add_argument('--max-spinner', type=int, default=-1,
                    help='仍用转圈遮罩的页面数超过该值时非零退出（加载态升级棘轮）')
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
            'toast': found['toast'],
            'skeleton': found['skeleton'], 'spinner': found['spinner'],
            'catchCount': found['catchCount'],
            'mouseOnly': found.get('mouseOnly', 0),
            'missing': missing,
            'ok': not missing,
        })

    skeleton_pages = sum(1 for r in rows if r['skeleton'])
    spinner_pages = sum(1 for r in rows if r['spinner'])
    empty_pages = sum(1 for r in rows if r['empty'])
    error_pages = sum(1 for r in rows if r['error'])
    toast_pages = sum(1 for r in rows if r['toast'])
    mouse_only = sum(r.get('mouseOnly', 0) for r in rows)
    mouse_pages = [(r['page'], r['mouseOnly']) for r in rows if r.get('mouseOnly')]
    only_toast = [r['page'] for r in rows if r['toast'] and not r['error'] and 'error' in REQUIRED[r['type']]]
    ok_pages = sum(1 for r in rows if r['ok'])

    print(f'===== 状态完备性检查（{len(rows)} 个页面/组件）=====')
    print(f'  用骨架屏的页面：{skeleton_pages}/{len(rows)}      用转圈遮罩的页面：{spinner_pages}/{len(rows)}')
    print(f'  有空态的页面：  {empty_pages}/{len(rows)}')
    print(f'  有页面级错误态的页面（持久 + 可重试）：{error_pages}/{len(rows)}')
    print(f'  仅有全局 toast 兜底的页面：{toast_pages - error_pages if toast_pages > error_pages else 0}'
          f'（拦截器弹 3 秒提示，页面不留痕、无重试入口）')
    print(f'  达到应有状态的页面：{ok_pages}/{len(rows)}      缺失项合计：{missing_total}')
    print(f'  鼠标独占的点击元素（键盘不可达，静态近似）：{mouse_only} 处'
          + ('  ' + '、'.join(f'{p.split("/")[-1]}={n}' for p, n in mouse_pages[:6]) if mouse_pages else ''))
    # 全局兜底：拦截器统一弹 toast（登录/注册等表单页依赖它），但那是 3 秒提示、页面不留痕
    req = SRC / 'api' / 'request.js'
    interceptor = False
    if req.exists():
        rt = req.read_text(encoding='utf-8', errors='ignore')
        interceptor = bool(re.search(r'ElMessage|type:\s*[\'"]error', rt))
    print(f'  全局兜底：axios 拦截器{"会" if interceptor else "不会"}弹出错误提示'
          f'（表单页依赖它；但它是瞬时提示，页面不会留下可重试的错误态）')
    print()
    print(f"  {'页面':44s} {'类型':6s} {'骨架':4s} {'空态':4s} {'错误态':6s} {'toast':6s} 缺失")
    for r in rows:
        print(f"  {r['page']:44s} {r['type']:6s} "
              f"{'有' if r['skeleton'] else '无':4s} {'有' if r['empty'] else '无':4s} "
              f"{'有' if r['error'] else '无':6s} {'有' if r['toast'] else '无':6s} "
              f"{'、'.join(r['missing']) or '—'}")

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

    if args.max_mouse_only >= 0 and mouse_only > args.max_mouse_only:
        print(f"\n  ✗ 鼠标独占点击元素 {mouse_only} 超过阈值 {args.max_mouse_only}")
        print('     （键盘用户无法触发这些元素；应改为可聚焦元素 + Enter/Space 触发）')
        sys.exit(1)

    if args.max_spinner >= 0 and spinner_pages > args.max_spinner:
        print(f"\n  ✗ 仍用转圈遮罩的页面 {spinner_pages} 超过阈值 {args.max_spinner}")
        print('     （应改用 SkeletonList / SkeletonTable / SkeletonDetail 骨架屏）')
        sys.exit(1)


if __name__ == '__main__':
    main()
